package com.example.infrastructure.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.infrastructure.persistence.entities.CostCalculationEntity
import com.example.infrastructure.persistence.entities.CostCalculationInputEntity
import com.example.infrastructure.persistence.entities.CostCalculationLineEntity
import com.example.infrastructure.persistence.entities.CostCalculationLineInputEntity
import com.example.infrastructure.persistence.entities.MetricDefinitionEntity
import com.example.infrastructure.persistence.entities.PricingComponentEntity
import com.example.infrastructure.persistence.entities.PricingProfileEntity
import com.example.infrastructure.persistence.entities.PricingProfileVersionEntity
import com.example.infrastructure.persistence.entities.PricingTierEntity
import com.example.infrastructure.persistence.entities.UnitDefinitionEntity
import com.example.infrastructure.persistence.entities.UsageMeasurementEntity
import com.example.infrastructure.persistence.entities.UsageRecordEntity
import kotlinx.coroutines.flow.Flow

/**
 * Stage-2 DAOs (pricing catalog + usage metering + itemized cost).
 * All inserts are plain @Insert (ABORT on conflict): idempotency is a
 * service-level concern (read-before-insert replay, the v23 pattern) and
 * silent replacement of financial evidence is forbidden (ADR-B4).
 */
@Dao
interface UnitDefinitionDao {
    @Insert
    suspend fun insert(unit: UnitDefinitionEntity)

    @Query("SELECT * FROM unit_definition WHERE unit_id = :unitId LIMIT 1")
    suspend fun byId(unitId: String): UnitDefinitionEntity?

    @Query("SELECT * FROM unit_definition ORDER BY unit_id")
    fun observeAll(): Flow<List<UnitDefinitionEntity>>
}

@Dao
interface MetricDefinitionDao {
    @Insert
    suspend fun insert(metric: MetricDefinitionEntity)

    @Query("SELECT * FROM metric_definition WHERE metric_id = :metricId LIMIT 1")
    suspend fun byId(metricId: String): MetricDefinitionEntity?

    @Query("SELECT * FROM metric_definition WHERE metric_code = :code LIMIT 1")
    suspend fun byCode(code: String): MetricDefinitionEntity?
}

@Dao
interface PricingProfileDao {
    @Insert
    suspend fun insert(profile: PricingProfileEntity)

    @Query("SELECT * FROM pricing_profile WHERE pricing_profile_id = :profileId LIMIT 1")
    suspend fun byId(profileId: String): PricingProfileEntity?

    @Query(
        "SELECT * FROM pricing_profile WHERE provider_id = :providerId AND profile_code = :profileCode LIMIT 1"
    )
    suspend fun byProviderAndCode(providerId: String, profileCode: String): PricingProfileEntity?

    @Query("SELECT * FROM pricing_profile WHERE provider_id = :providerId ORDER BY profile_code")
    suspend fun forProvider(providerId: String): List<PricingProfileEntity>
}

@Dao
interface PricingProfileVersionDao {
    @Insert
    suspend fun insert(version: PricingProfileVersionEntity)

    @Query(
        "SELECT * FROM pricing_profile_version WHERE pricing_profile_version_id = :versionId LIMIT 1"
    )
    suspend fun byId(versionId: String): PricingProfileVersionEntity?

    /**
     * The version covering [atEpochMs] for a profile. Multiple covering
     * windows cannot exist (enforced by the writer); the ORDER BY makes
     * the pick deterministic even if data drifts in.
     */
    @Query(
        "SELECT * FROM pricing_profile_version WHERE pricing_profile_id = :profileId " +
            "AND effective_from_epoch_ms <= :atEpochMs " +
            "AND (effective_until_epoch_ms IS NULL OR effective_until_epoch_ms > :atEpochMs) " +
            "ORDER BY effective_from_epoch_ms DESC, version_number DESC LIMIT 1"
    )
    suspend fun coveringForProfile(profileId: String, atEpochMs: Long): PricingProfileVersionEntity?

    @Query(
        "SELECT MAX(version_number) FROM pricing_profile_version WHERE pricing_profile_id = :profileId"
    )
    suspend fun maxVersionNumber(profileId: String): Int?

    @Query(
        "SELECT * FROM pricing_profile_version WHERE pricing_source = :pricingSource LIMIT 1"
    )
    suspend fun byPricingSource(pricingSource: String): PricingProfileVersionEntity?

    @Query(
        "SELECT * FROM pricing_profile_version WHERE pricing_profile_id = :profileId " +
            "ORDER BY version_number"
    )
    suspend fun forProfile(profileId: String): List<PricingProfileVersionEntity>
}

@Dao
interface PricingComponentDao {
    @Insert
    suspend fun insert(component: PricingComponentEntity)

    @Query(
        "SELECT * FROM pricing_component WHERE pricing_profile_version_id = :versionId " +
            "ORDER BY component_order"
    )
    suspend fun forVersion(versionId: String): List<PricingComponentEntity>

    @Query(
        "SELECT * FROM pricing_component WHERE pricing_component_id = :componentId LIMIT 1"
    )
    suspend fun byId(componentId: String): PricingComponentEntity?
}

@Dao
interface PricingTierDao {
    @Insert
    suspend fun insert(tier: PricingTierEntity)

    @Query("SELECT * FROM pricing_tier WHERE component_id = :componentId ORDER BY tier_order")
    suspend fun forComponent(componentId: String): List<PricingTierEntity>
}

@Dao
interface UsageRecordDao {
    @Insert
    suspend fun insert(record: UsageRecordEntity)

    @Query(
        "SELECT * FROM usage_record WHERE request_attempt_id = :attemptId " +
            "AND report_source = :source AND report_version = :version LIMIT 1"
    )
    suspend fun byAttemptSourceVersion(
        attemptId: String,
        source: String,
        version: String
    ): UsageRecordEntity?

    @Query("SELECT * FROM usage_record WHERE usage_record_id = :recordId LIMIT 1")
    suspend fun byId(recordId: String): UsageRecordEntity?
}

@Dao
interface UsageMeasurementDao {
    @Insert
    suspend fun insert(measurement: UsageMeasurementEntity)

    @Query(
        "SELECT * FROM usage_measurement WHERE usage_record_id = :recordId ORDER BY metric_id"
    )
    suspend fun forRecord(recordId: String): List<UsageMeasurementEntity>

    @Query("SELECT * FROM usage_measurement WHERE measurement_id = :measurementId LIMIT 1")
    suspend fun byId(measurementId: String): UsageMeasurementEntity?
}

@Dao
interface CostCalculationDao {
    @Insert
    suspend fun insert(calculation: CostCalculationEntity)

    @Query("SELECT * FROM cost_calculation WHERE cost_calculation_id = :calculationId LIMIT 1")
    suspend fun byId(calculationId: String): CostCalculationEntity?

    @Query(
        "SELECT * FROM cost_calculation WHERE request_attempt_id = :attemptId " +
            "ORDER BY calculated_at_epoch_ms"
    )
    suspend fun forAttempt(attemptId: String): List<CostCalculationEntity>

    /**
     * Stage-3 reconciliation feed: the provider-attributed calculations in
     * a billing window. Provider linkage rides the pricing chain
     * (calculation -> version -> profile -> provider_id); the attribution
     * instant for REQUEST_ATTEMPT-level rows is calculated_at_epoch_ms
     * (interpretation I-23 — period-level rows will carry their own
     * windows when a period-level writer exists).
     */
    @Query(
        "SELECT cc.* FROM cost_calculation cc " +
            "JOIN pricing_profile_version v ON v.pricing_profile_version_id = cc.pricing_profile_version_id " +
            "JOIN pricing_profile p ON p.pricing_profile_id = v.pricing_profile_id " +
            "WHERE p.provider_id = :providerId " +
            "AND cc.calculation_status = :calculationStatus " +
            "AND cc.calculated_at_epoch_ms >= :fromEpochMs " +
            "AND cc.calculated_at_epoch_ms < :untilEpochMs " +
            "ORDER BY cc.calculated_at_epoch_ms"
    )
    suspend fun forProviderWindow(
        providerId: String,
        calculationStatus: String,
        fromEpochMs: Long,
        untilEpochMs: Long
    ): List<CostCalculationEntity>
}

@Dao
interface CostCalculationInputDao {
    @Insert
    suspend fun insert(input: CostCalculationInputEntity)

    @Query(
        "SELECT * FROM cost_calculation_input WHERE cost_calculation_id = :calculationId"
    )
    suspend fun forCalculation(calculationId: String): List<CostCalculationInputEntity>
}

@Dao
interface CostCalculationLineDao {
    @Insert
    suspend fun insert(line: CostCalculationLineEntity)

    @Query(
        "SELECT * FROM cost_calculation_line WHERE cost_calculation_id = :calculationId " +
            "ORDER BY line_order"
    )
    suspend fun forCalculation(calculationId: String): List<CostCalculationLineEntity>

    /** Stage-3 reconciliation feed: active lines of many calculations. */
    @Query(
        "SELECT * FROM cost_calculation_line WHERE cost_calculation_id IN (:calculationIds) " +
            "AND line_status = 'ACTIVE' AND final_amount_micro_units IS NOT NULL " +
            "ORDER BY cost_calculation_id, line_order"
    )
    suspend fun activeLinesForCalculations(calculationIds: List<String>): List<CostCalculationLineEntity>
}

@Dao
interface CostCalculationLineInputDao {
    @Insert
    suspend fun insert(lineInput: CostCalculationLineInputEntity)

    @Query("SELECT * FROM cost_calculation_line_input WHERE line_id = :lineId")
    suspend fun forLine(lineId: String): List<CostCalculationLineInputEntity>
}
