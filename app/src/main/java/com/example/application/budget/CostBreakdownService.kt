package com.example.application.budget

import androidx.room.withTransaction
import com.example.domain.core.budget.UsageAccountingInput
import com.example.infrastructure.persistence.AppDatabase
import com.example.infrastructure.persistence.entities.CostCalculationEntity
import com.example.infrastructure.persistence.entities.CostCalculationInputEntity
import com.example.infrastructure.persistence.entities.CostCalculationLineEntity
import com.example.infrastructure.persistence.entities.CostCalculationLineInputEntity
import com.example.infrastructure.persistence.entities.UsageMeasurementEntity
import com.example.infrastructure.persistence.entities.UsageRecordEntity
import java.math.BigDecimal
import java.security.MessageDigest

/**
 * ============================================================================
 * CostBreakdownService — generic usage metering + itemized cost
 * (roadmap Phase 2: "قياسات الاستخدام العامة، وحسابات التكلفة المفككة
 * بأسطرها ومدخلاتها")
 * ============================================================================
 * For every post-execution accounting event it records, in ONE
 * transaction:
 *
 *   usage_record           — the report (idempotent per attempt+source)
 *   usage_measurement ×4   — INPUT/OUTPUT/CACHED/TOTAL tokens (KNOWN
 *                            values only; UNKNOWN is a state, never 0)
 *   cost_calculation       — one itemized computation against a resolved
 *                            pricing version (absent when pricing is
 *                            genuinely unknown — usage facts still land)
 *   cost_calculation_input — the measurements that fed the computation
 *   cost_calculation_line  — one line per priced component, with the
 *                            exact pre-rounded decimal AND the rounded
 *                            micro integer (G08: BigDecimal, HALF_UP)
 *   cost_calculation_line_input — per line: the measurement, the rate
 *                            constant and the price-unit-quantity constant
 *
 * Failure isolation: the caller (EconomicGovernanceService) treats a
 * breakdown failure as best-effort — accounting itself must never break
 * (same discipline as telemetry).
 */
class CostBreakdownService(
    private val database: AppDatabase,
    private val pricingCatalog: PricingCatalogService,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** What the caller needs back: the audit anchors. */
    data class BreakdownResult(
        val usageRecordId: String,
        /** null = no pricing version covered the request (honest absence). */
        val costCalculationId: String?,
        /** null together with [costCalculationId], or when NOT_POSSIBLE. */
        val totalAmountMicroUnits: Long?,
        val calculationStatus: String?
    )

    /** Metric mapping: component code -> (metric id, usage field, source field). */
    private data class MetricSpec(val code: String, val metricId: String, val sourceField: String)

    private val inputMetric = MetricSpec("INPUT_TOKENS", "METRIC_INPUT_TOKENS", "inputTokens")
    private val outputMetric = MetricSpec("OUTPUT_TOKENS", "METRIC_OUTPUT_TOKENS", "outputTokens")
    private val cachedMetric = MetricSpec("CACHED_TOKENS", "METRIC_CACHED_TOKENS", "cachedTokens")
    private val totalMetric = MetricSpec("TOTAL_TOKENS", "METRIC_TOTAL_TOKENS", "totalTokens")

    suspend fun record(input: UsageAccountingInput): BreakdownResult = database.withTransaction {
        val now = clock()
        val reportSource = if (input.isActualProviderReport) SOURCE_PROVIDER else SOURCE_ORCHESTRATOR

        // ---- 1) usage_record (read-before-insert replay, v23 pattern) ----
        val usageRecordId = "ur_${input.executionId}_$reportSource"
        val existing = database.usageRecordDao()
            .byAttemptSourceVersion(input.executionId, reportSource, REPORT_VERSION)
        if (existing != null) {
            // already recorded: replay the recorded outcome, never re-write
            val calc = database.costCalculationDao().forAttempt(input.executionId).lastOrNull()
            return@withTransaction BreakdownResult(
                usageRecordId = existing.usageRecordId,
                costCalculationId = calc?.costCalculationId,
                totalAmountMicroUnits = calc?.totalAmountMicroUnits,
                calculationStatus = calc?.calculationStatus
            )
        }
        database.usageRecordDao().insert(
            UsageRecordEntity(
                usageRecordId = usageRecordId,
                requestAttemptId = input.executionId, // deferred FK (I-12)
                reportSource = reportSource,
                reportVersion = REPORT_VERSION,
                reportedAtEpochMs = input.timestampEpochMs,
                retrievedAtEpochMs = now,
                reportStatus = if (input.isActualProviderReport) STATUS_FINAL else STATUS_ESTIMATED,
                isFinalReport = 1,
                supersedesUsageRecordId = null,
                providerRequestId = input.executionId,
                recordedAtEpochMs = now
            )
        )

        // ---- 2) usage_measurement ×4 (KNOWN values; Int fields are
        //         non-null in TokenUsageRecord, 0 is a reported zero) ----
        val usage = input.usage
        fun measurement(spec: MetricSpec, value: Long) = UsageMeasurementEntity(
            measurementId = "um_${input.executionId}_${spec.code}",
            usageRecordId = usageRecordId,
            metricId = spec.metricId,
            valuePrecise = value.toString(),
            unitId = "UNIT_TOKEN",
            sourceAuthority = reportSource,
            sourceField = spec.sourceField,
            measurementStatus = STATUS_KNOWN,
            knowledgeStatus = KNOWLEDGE_KNOWN,
            unknownReasonCode = null,
            recordedAtEpochMs = now
        )
        val inputMeasurement = measurement(inputMetric, usage.inputTokens.toLong())
        val outputMeasurement = measurement(outputMetric, usage.outputTokens.toLong())
        val cachedMeasurement = measurement(cachedMetric, usage.cachedTokens.toLong())
        val totalMeasurement = measurement(
            totalMetric,
            (usage.providerTotalTokens ?: usage.totalTokens).toLong()
        )
        listOf(inputMeasurement, outputMeasurement, cachedMeasurement, totalMeasurement)
            .forEach { database.usageMeasurementDao().insert(it) }

        // ---- 3) pricing version (may mirror legacy entries once, I-13) ----
        val resolved = runCatching {
            pricingCatalog.ensureSyncedAndResolve(
                providerId = input.providerId ?: "",
                serviceId = input.serviceId,
                modelId = input.modelId,
                atEpochMs = input.timestampEpochMs
            )
        }.getOrNull()

        if (resolved == null || resolved.version.pricingStatus != PricingCatalogService.STATUS_CALCULABLE) {
            // honest absence: usage facts are recorded, cost is NOT_POSSIBLE
            // only when a version EXISTS but carries no usable price; with no
            // version at all there is nothing to calculate against.
            if (resolved == null) {
                return@withTransaction BreakdownResult(usageRecordId, null, null, null)
            }
            val calcId = "cc_${input.executionId}_${resolved.version.pricingProfileVersionId.hashCode()}"
            database.costCalculationDao().insert(
                CostCalculationEntity(
                    costCalculationId = calcId,
                    calculationLevel = LEVEL_REQUEST_ATTEMPT,
                    requestAttemptId = input.executionId,
                    usageRecordId = usageRecordId,
                    calculationScopeId = null,
                    periodStartEpochMs = null,
                    periodEndEpochMs = null,
                    pricingProfileVersionId = resolved.version.pricingProfileVersionId,
                    calculationEngineVersion = ENGINE_VERSION,
                    ruleSetVersion = CostMath.RULE_SET_VERSION,
                    inputHash = inputHash(resolved.version.pricingProfileVersionId, usage),
                    currency = resolved.profile.currency,
                    totalAmountMicroUnits = null,
                    calculationStatus = STATUS_NOT_POSSIBLE,
                    confidenceState = CONFIDENCE_DECLARED,
                    calculatedAtEpochMs = now,
                    recordedAtEpochMs = now,
                    supersedesCalculationId = null
                )
            )
            return@withTransaction BreakdownResult(usageRecordId, calcId, null, STATUS_NOT_POSSIBLE)
        }

        // ---- 4) itemized lines (G08: exact decimals + HALF_UP rounding) ----
        val componentsByCode = resolved.components.associateBy { it.componentCode }
        data class PricedLine(
            val component: com.example.infrastructure.persistence.entities.PricingComponentEntity,
            val quantity: Long,
            val measurementId: String
        )
        val pricedLines = listOf(
            componentsByCode["INPUT_TOKENS"]?.let { PricedLine(it, usage.inputTokens.toLong(), inputMeasurement.measurementId) },
            componentsByCode["OUTPUT_TOKENS"]?.let { PricedLine(it, usage.outputTokens.toLong(), outputMeasurement.measurementId) },
            componentsByCode["CACHED_TOKENS"]?.let { PricedLine(it, usage.cachedTokens.toLong(), cachedMeasurement.measurementId) }
        ).filterNotNull()

        val computed = pricedLines.map { priced ->
            val rate = BigDecimal(priced.component.rateAmountPrecise)
            CostMath.computeLine(priced.quantity, rate) to priced
        }
        val totalMicro = CostMath.sumLines(computed.map { it.first.finalAmountMicroUnits })
        val calcId = "cc_${input.executionId}_${resolved.version.pricingProfileVersionId.hashCode()}"

        database.costCalculationDao().insert(
            CostCalculationEntity(
                costCalculationId = calcId,
                calculationLevel = LEVEL_REQUEST_ATTEMPT,
                requestAttemptId = input.executionId,
                usageRecordId = usageRecordId,
                calculationScopeId = null,
                periodStartEpochMs = null,
                periodEndEpochMs = null,
                pricingProfileVersionId = resolved.version.pricingProfileVersionId,
                calculationEngineVersion = ENGINE_VERSION,
                ruleSetVersion = CostMath.RULE_SET_VERSION,
                inputHash = inputHash(resolved.version.pricingProfileVersionId, usage),
                currency = resolved.profile.currency,
                totalAmountMicroUnits = totalMicro,
                calculationStatus = if (input.isActualProviderReport) STATUS_CALCULATED else STATUS_ESTIMATED,
                confidenceState = CONFIDENCE_DECLARED,
                calculatedAtEpochMs = now,
                recordedAtEpochMs = now,
                supersedesCalculationId = null
            )
        )

        // calculation inputs: the measurements that fed the computation
        listOf(inputMeasurement, outputMeasurement, cachedMeasurement, totalMeasurement).forEach { m ->
            database.costCalculationInputDao().insert(
                CostCalculationInputEntity(
                    inputId = "ci_${calcId}_${m.measurementId.hashCode()}",
                    costCalculationId = calcId,
                    inputRole = "MEASUREMENT",
                    usageMeasurementId = m.measurementId,
                    usageRecordId = null,
                    requestAttemptId = null,
                    quantityPrecise = m.valuePrecise,
                    unitId = m.unitId,
                    includedAtEpochMs = now,
                    sourceAuthority = reportSource
                )
            )
        }

        computed.forEachIndexed { index, (line, priced) ->
            val lineId = "cl_${calcId}_${index + 1}"
            database.costCalculationLineDao().insert(
                CostCalculationLineEntity(
                    lineId = lineId,
                    costCalculationId = calcId,
                    pricingComponentId = priced.component.pricingComponentId,
                    lineOrder = index + 1,
                    lineBasisType = BASIS_USAGE_MEASUREMENT,
                    usageMeasurementId = priced.measurementId,
                    basisQuantityPrecise = priced.quantity.toString(),
                    basisUnitId = "UNIT_TOKEN",
                    baseAmountMicroUnits = null,
                    rateValuePrecise = priced.component.rateAmountPrecise,
                    unitPricePrecise = priced.component.priceUnitQuantityPrecise,
                    preRoundedAmountPrecise = line.preRoundedAmountPrecise,
                    finalAmountMicroUnits = line.finalAmountMicroUnits,
                    currency = resolved.profile.currency,
                    roundingMode = CostMath.ROUNDING_MODE.toString(),
                    lineStatus = STATUS_ACTIVE,
                    notes = null
                )
            )
            // line inputs: quantity measurement + rate + price unit quantity
            database.costCalculationLineInputDao().insert(
                CostCalculationLineInputEntity(
                    lineInputId = "li_${lineId}_qty",
                    lineId = lineId,
                    inputRole = "QUANTITY",
                    usageMeasurementId = priced.measurementId,
                    calculationInputId = null,
                    costCalculationLineId = null,
                    constantValuePrecise = null,
                    quantityPrecise = priced.quantity.toString(),
                    unitId = "UNIT_TOKEN",
                    notes = null
                )
            )
            database.costCalculationLineInputDao().insert(
                CostCalculationLineInputEntity(
                    lineInputId = "li_${lineId}_rate",
                    lineId = lineId,
                    inputRole = "RATE",
                    usageMeasurementId = null,
                    calculationInputId = null,
                    costCalculationLineId = null,
                    constantValuePrecise = priced.component.rateAmountPrecise,
                    quantityPrecise = null,
                    unitId = null,
                    notes = null
                )
            )
            database.costCalculationLineInputDao().insert(
                CostCalculationLineInputEntity(
                    lineInputId = "li_${lineId}_unit",
                    lineId = lineId,
                    inputRole = "PRICE_UNIT_QUANTITY",
                    usageMeasurementId = null,
                    calculationInputId = null,
                    costCalculationLineId = null,
                    constantValuePrecise = priced.component.priceUnitQuantityPrecise,
                    quantityPrecise = null,
                    unitId = null,
                    notes = null
                )
            )
        }

        BreakdownResult(
            usageRecordId = usageRecordId,
            costCalculationId = calcId,
            totalAmountMicroUnits = totalMicro,
            calculationStatus = if (input.isActualProviderReport) STATUS_CALCULATED else STATUS_ESTIMATED
        )
    }

    /** Canonical input fingerprint (Stage-1 hash discipline). */
    private fun inputHash(versionId: String, usage: com.example.domain.core.budget.TokenUsageRecord): String {
        val canonical = "v1|$versionId|in=${usage.inputTokens}|out=${usage.outputTokens}|" +
            "cached=${usage.cachedTokens}|total=${usage.providerTotalTokens ?: usage.totalTokens}"
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val SOURCE_PROVIDER = "PROVIDER"
        const val SOURCE_ORCHESTRATOR = "ORCHESTRATOR"
        const val REPORT_VERSION = "1"
        const val STATUS_FINAL = "FINAL"
        const val STATUS_ESTIMATED = "ESTIMATED"
        const val STATUS_KNOWN = "KNOWN"
        const val KNOWLEDGE_KNOWN = "KNOWN"
        const val LEVEL_REQUEST_ATTEMPT = "REQUEST_ATTEMPT"
        const val STATUS_CALCULATED = "CALCULATED"
        const val STATUS_NOT_POSSIBLE = "NOT_POSSIBLE"
        const val STATUS_ACTIVE = "ACTIVE"
        const val BASIS_USAGE_MEASUREMENT = "USAGE_MEASUREMENT"
        const val CONFIDENCE_DECLARED = "DECLARED"
        const val ENGINE_VERSION = "stage2-v1"
    }
}
