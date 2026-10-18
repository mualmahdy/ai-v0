package com.example.infrastructure.persistence

/**
 * ============================================================================
 * SINGLE SOURCE OF TRUTH for the MIGRATION_25_TO_26 DDL (Stage 3).
 * ============================================================================
 * Stage-3 reconciliation, provider invoicing, currency conversion and
 * entitlements (roadmap Phase 3: "سجلات فوترة المزودين والمصالحات
 * بتضميناتها وتسويات فروقها، وقواعد تحويل العملات باتجاهاتها وفتراتها،
 * ومسار استيراد الفواتير" + the accruals gap row, all Stage-3 in the
 * approved feasibility gap matrix). Closes gates PDM-G03 (comparison
 * currency & direction) and PDM-G04 (result vs finality separation).
 *
 * Provenance of every statement:
 *  - Tables + columns: PDM v0.1 Group B (currency_conversion_rule),
 *    Group C/D (entitlement_record, entitlement_consumption) and Group E
 *    (provider_billing_record, reconciliation_record,
 *    reconciliation_inclusion, reconciliation_adjustment), adapted per the
 *    APPROVED PDM v0.2 rules — identical adaptation set as Stage 2:
 *      * *_minor_units -> *_micro_units (ADR-B1),
 *      * TEXT timestamps -> INTEGER epoch_ms (v0.2 change 4),
 *      * table-level UNIQUE -> NAMED unique indices (Stage-0 د.2 rule 4),
 *      * partial unique indices -> regular named indices + service-
 *        transaction uniqueness (v0.2 change 7 / I-14 pattern), EXCEPT the
 *        one change 7 names explicitly: ux_finalized_reconciliation_key
 *        becomes a REGULAR index + a uniqueness TRIGGER (below),
 *      * references to not-yet-existing tables (provider,
 *        access_context, provider_request_attempt) stay plain TEXT — the
 *        deferred-FK pattern (v0.2 change 8 / I-12).
 *  - Triggers: the five PDM reconciliation/billing/currency triggers
 *    (trg_reconciliation_conversion_rule_insert,
 *    trg_reconciliation_finality_inclusion_guard,
 *    trg_currency_rule_immutable_when_used — ledger reference dropped:
 *    the v23 budget_ledger_entry carries no conversion_rule_id column,
 *    the FX evidence home in this schema is reconciliation_record —
 *    trg_no_update_billing_record, trg_no_delete_billing_record) plus the
 *    G04 finality machine (frozen-after-draft, no delete, unique
 *    FINALIZED per billing+scope+period — change 7) and the ADR-B2
 *    append-only pairs for the Stage-3 evidence tables (inclusion,
 *    adjustment, consumption). The two draft-guard triggers extend the
 *    PDM set: the PDM guards finalized inclusions only at finalize time
 *    (its own implementer notes admit trigger-level checks are
 *    incomplete); Stage 3 rejects inserts into a non-draft reconciliation
 *    outright (defense in depth, interpretation I-25).
 *
 * No existing table is touched anywhere in this list.
 */
internal object Stage3MigrationSql {

    // ------------------------------------------------------------------
    // Currency conversion (G03): direction is explicit
    // (TARGET_UNITS_PER_SOURCE_UNIT), rates are exact decimal TEXT,
    // windows are epoch-ms, the rounding policy is recorded per rule.
    // ------------------------------------------------------------------

    val CREATE_CURRENCY_CONVERSION_RULE = """
        CREATE TABLE IF NOT EXISTS currency_conversion_rule (
          currency_conversion_rule_id TEXT PRIMARY KEY NOT NULL,
          from_currency TEXT NOT NULL CHECK (length(from_currency)=3),
          to_currency TEXT NOT NULL CHECK (length(to_currency)=3),
          rate_precise TEXT NOT NULL,
          rate_direction TEXT NOT NULL CHECK (rate_direction='TARGET_UNITS_PER_SOURCE_UNIT'),
          effective_from_epoch_ms INTEGER NOT NULL,
          effective_until_epoch_ms INTEGER,
          source_authority TEXT NOT NULL,
          rounding_policy TEXT NOT NULL,
          created_at_epoch_ms INTEGER NOT NULL,
          CHECK (from_currency <> to_currency),
          CHECK (effective_until_epoch_ms IS NULL OR effective_from_epoch_ms < effective_until_epoch_ms)
        )
    """.trimIndent()

    // ------------------------------------------------------------------
    // Provider billing records: the invoice side of reconciliation.
    // Immutable evidence (PDM trg_no_update/no_delete_billing_record);
    // corrections arrive as NEW rows chained via adjusts_billing_record_id.
    // provider_id / access_context_id are deferred FKs (v0.2 change 8).
    // ------------------------------------------------------------------

    val CREATE_PROVIDER_BILLING_RECORD = """
        CREATE TABLE IF NOT EXISTS provider_billing_record (
          provider_billing_record_id TEXT PRIMARY KEY NOT NULL,
          provider_id TEXT NOT NULL,
          access_context_id TEXT,
          external_billing_id TEXT,
          billing_record_type TEXT NOT NULL,
          record_granularity TEXT NOT NULL CHECK (record_granularity IN ('REQUEST','HOURLY','DAILY','MONTHLY','AGGREGATE','UNKNOWN')),
          period_start_epoch_ms INTEGER,
          period_end_epoch_ms INTEGER,
          scope_id TEXT REFERENCES scope_descriptor(scope_id) ON DELETE RESTRICT,
          primary_amount_type TEXT NOT NULL CHECK (primary_amount_type IN ('GROSS_PAYABLE','NET_BEFORE_TAX','TAX','CREDIT','ADJUSTMENT','UNKNOWN')),
          primary_amount_micro_units INTEGER,
          gross_payable_micro_units INTEGER,
          net_before_tax_micro_units INTEGER,
          tax_amount_micro_units INTEGER,
          credit_amount_micro_units INTEGER,
          adjustment_amount_micro_units INTEGER,
          currency TEXT NOT NULL CHECK (length(currency)=3),
          amount_consistency_status TEXT NOT NULL CHECK (amount_consistency_status IN ('CONSISTENT','INCONSISTENT','UNKNOWN')),
          source_authority TEXT NOT NULL,
          reported_at_epoch_ms INTEGER,
          retrieved_at_epoch_ms INTEGER,
          recorded_at_epoch_ms INTEGER NOT NULL,
          billing_status TEXT NOT NULL,
          adjusts_billing_record_id TEXT REFERENCES provider_billing_record(provider_billing_record_id) ON DELETE RESTRICT,
          CHECK (period_end_epoch_ms IS NULL OR period_start_epoch_ms IS NULL OR period_start_epoch_ms < period_end_epoch_ms)
        )
    """.trimIndent()

    // ------------------------------------------------------------------
    // Reconciliation (G04): result status (what the comparison found) is a
    // DIFFERENT column from finality status (the human sign-off machine:
    // DRAFT -> FINALIZED / REVOKED). Cross-currency comparisons MUST carry
    // their conversion rule (CHECK + insert trigger) and the comparison
    // currency must be one of the two sides — G03 resolved ambiguities.
    // ------------------------------------------------------------------

    val CREATE_RECONCILIATION_RECORD = """
        CREATE TABLE IF NOT EXISTS reconciliation_record (
          reconciliation_id TEXT PRIMARY KEY NOT NULL,
          provider_billing_record_id TEXT NOT NULL REFERENCES provider_billing_record(provider_billing_record_id) ON DELETE RESTRICT,
          scope_id TEXT NOT NULL REFERENCES scope_descriptor(scope_id) ON DELETE RESTRICT,
          period_start_epoch_ms INTEGER NOT NULL,
          period_end_epoch_ms INTEGER NOT NULL,
          provider_currency TEXT NOT NULL CHECK (length(provider_currency)=3),
          local_currency TEXT NOT NULL CHECK (length(local_currency)=3),
          comparison_currency TEXT NOT NULL CHECK (length(comparison_currency)=3),
          conversion_rule_id TEXT REFERENCES currency_conversion_rule(currency_conversion_rule_id) ON DELETE RESTRICT,
          local_amount_micro_units INTEGER,
          provider_amount_micro_units INTEGER,
          difference_micro_units INTEGER,
          tolerance_micro_units INTEGER,
          coverage_status TEXT NOT NULL CHECK (coverage_status IN ('COMPLETE','PARTIAL','NOT_POSSIBLE')),
          reconciliation_status TEXT NOT NULL CHECK (reconciliation_status IN ('PENDING','MATCHED','WITHIN_TOLERANCE','CONFLICT','NOT_POSSIBLE','SUPERSEDED')),
          finality_status TEXT NOT NULL DEFAULT 'DRAFT' CHECK (finality_status IN ('DRAFT','FINALIZED','REVOKED')),
          reason_code TEXT,
          rule_version TEXT NOT NULL,
          reconciled_at_epoch_ms INTEGER,
          recorded_at_epoch_ms INTEGER NOT NULL,
          superseded_by TEXT REFERENCES reconciliation_record(reconciliation_id) ON DELETE RESTRICT,
          CHECK (period_start_epoch_ms < period_end_epoch_ms),
          CHECK (comparison_currency IN (provider_currency, local_currency)),
          CHECK ((provider_currency=local_currency AND conversion_rule_id IS NULL) OR (provider_currency<>local_currency AND conversion_rule_id IS NOT NULL))
        )
    """.trimIndent()

    val CREATE_RECONCILIATION_INCLUSION = """
        CREATE TABLE IF NOT EXISTS reconciliation_inclusion (
          inclusion_id TEXT PRIMARY KEY NOT NULL,
          reconciliation_id TEXT NOT NULL REFERENCES reconciliation_record(reconciliation_id) ON DELETE RESTRICT,
          cost_calculation_line_id TEXT NOT NULL REFERENCES cost_calculation_line(line_id) ON DELETE RESTRICT,
          included_amount_micro_units INTEGER NOT NULL,
          currency TEXT NOT NULL CHECK (length(currency)=3),
          inclusion_status TEXT NOT NULL
        )
    """.trimIndent()

    val CREATE_RECONCILIATION_ADJUSTMENT = """
        CREATE TABLE IF NOT EXISTS reconciliation_adjustment (
          adjustment_id TEXT PRIMARY KEY NOT NULL,
          reconciliation_id TEXT NOT NULL REFERENCES reconciliation_record(reconciliation_id) ON DELETE RESTRICT,
          adjustment_type TEXT NOT NULL CHECK (adjustment_type IN ('PROVIDER_ONLY_TAX','PROVIDER_ONLY_CREDIT','PROVIDER_ONLY_FEE','CONVERSION_DIFFERENCE','TOLERANCE','LOCAL_UNMODELED_ADJUSTMENT')),
          amount_micro_units INTEGER NOT NULL,
          currency TEXT NOT NULL CHECK (length(currency)=3),
          reason_code TEXT NOT NULL,
          notes TEXT,
          created_at_epoch_ms INTEGER NOT NULL
        )
    """.trimIndent()

    // ------------------------------------------------------------------
    // Entitlements (the accruals gap row): exactly-one-of value shape is a
    // table CHECK (mirrored by the service), consumption is append-only
    // evidence with an (entitlement, idempotency_key) unique pair.
    // entitlement_record itself stays MUTABLE (administrative record —
    // the pricing_profile precedent, interpretation I-28).
    // ------------------------------------------------------------------

    val CREATE_ENTITLEMENT_RECORD = """
        CREATE TABLE IF NOT EXISTS entitlement_record (
          entitlement_id TEXT PRIMARY KEY NOT NULL,
          provider_id TEXT NOT NULL,
          applicability_scope_id TEXT NOT NULL REFERENCES scope_descriptor(scope_id) ON DELETE RESTRICT,
          entitlement_type TEXT NOT NULL CHECK (entitlement_type IN ('FREE_PRICING','FREE_QUOTA','INCLUDED_USAGE','PROVIDER_CREDIT','TRIAL','PROMOTIONAL','SUBSCRIPTION_INCLUDED','PAID','UNKNOWN')),
          value_kind TEXT NOT NULL CHECK (value_kind IN ('UNLIMITED','QUANTITY_LIMIT','MONETARY_CREDIT','PROVIDER_CREDIT','PRICE_OVERRIDE','UNKNOWN')),
          quantity_limit_precise TEXT,
          unit_id TEXT REFERENCES unit_definition(unit_id) ON DELETE RESTRICT,
          monetary_limit_micro_units INTEGER,
          currency TEXT CHECK (currency IS NULL OR length(currency)=3),
          credit_limit_precise TEXT,
          credit_unit_id TEXT REFERENCES unit_definition(unit_id) ON DELETE RESTRICT,
          price_override_mode TEXT CHECK (price_override_mode IS NULL OR price_override_mode IN ('NONE','ZERO_PRICE','LINKED_PRICING_PROFILE')),
          linked_pricing_profile_id TEXT REFERENCES pricing_profile(pricing_profile_id) ON DELETE RESTRICT,
          starts_at_epoch_ms INTEGER,
          expires_at_epoch_ms INTEGER,
          reset_policy TEXT,
          stacking_policy TEXT,
          consumption_order INTEGER,
          status TEXT NOT NULL,
          verification_status TEXT NOT NULL,
          created_at_epoch_ms INTEGER NOT NULL,
          CHECK (expires_at_epoch_ms IS NULL OR starts_at_epoch_ms IS NULL OR starts_at_epoch_ms < expires_at_epoch_ms),
          CHECK (
            (value_kind='UNLIMITED' AND quantity_limit_precise IS NULL AND unit_id IS NULL AND monetary_limit_micro_units IS NULL AND currency IS NULL AND credit_limit_precise IS NULL AND credit_unit_id IS NULL) OR
            (value_kind='QUANTITY_LIMIT' AND quantity_limit_precise IS NOT NULL AND unit_id IS NOT NULL AND monetary_limit_micro_units IS NULL AND currency IS NULL AND credit_limit_precise IS NULL AND credit_unit_id IS NULL) OR
            (value_kind='MONETARY_CREDIT' AND monetary_limit_micro_units IS NOT NULL AND currency IS NOT NULL AND quantity_limit_precise IS NULL AND unit_id IS NULL AND credit_limit_precise IS NULL AND credit_unit_id IS NULL) OR
            (value_kind='PROVIDER_CREDIT' AND credit_limit_precise IS NOT NULL AND credit_unit_id IS NOT NULL AND monetary_limit_micro_units IS NULL AND currency IS NULL AND quantity_limit_precise IS NULL AND unit_id IS NULL) OR
            (value_kind='PRICE_OVERRIDE' AND price_override_mode IS NOT NULL) OR
            (value_kind='UNKNOWN')
          ),
          CHECK (price_override_mode IS NULL OR price_override_mode <> 'LINKED_PRICING_PROFILE' OR linked_pricing_profile_id IS NOT NULL)
        )
    """.trimIndent()

    val CREATE_ENTITLEMENT_CONSUMPTION = """
        CREATE TABLE IF NOT EXISTS entitlement_consumption (
          consumption_id TEXT PRIMARY KEY NOT NULL,
          entitlement_id TEXT NOT NULL REFERENCES entitlement_record(entitlement_id) ON DELETE RESTRICT,
          consumption_granularity TEXT NOT NULL CHECK (consumption_granularity IN ('REQUEST_ATTEMPT','USAGE_RECORD','USAGE_MEASUREMENT','PERIOD_AGGREGATE','PROVIDER_DECLARED')),
          request_attempt_id TEXT,
          usage_record_id TEXT REFERENCES usage_record(usage_record_id) ON DELETE RESTRICT,
          usage_measurement_id TEXT REFERENCES usage_measurement(measurement_id) ON DELETE RESTRICT,
          period_start_epoch_ms INTEGER,
          period_end_epoch_ms INTEGER,
          source_type TEXT NOT NULL,
          quantity_precise TEXT,
          unit_id TEXT REFERENCES unit_definition(unit_id) ON DELETE RESTRICT,
          amount_micro_units INTEGER,
          credit_amount_precise TEXT,
          consumption_status TEXT NOT NULL CHECK (consumption_status IN ('CONFIRMED','ESTIMATED','PENDING','UNKNOWN','OVER_LIMIT')),
          idempotency_key TEXT NOT NULL,
          recorded_at_epoch_ms INTEGER NOT NULL,
          CHECK (period_end_epoch_ms IS NULL OR period_start_epoch_ms IS NULL OR period_start_epoch_ms < period_end_epoch_ms)
        )
    """.trimIndent()

    // ------------------------------------------------------------------
    // Named indices. ux_finalized_reconciliation_key (PDM partial unique)
    // is the ONE change-7 exception: its uniqueness lives in the
    // trg_reconciliation_finalized_unique_* trigger pair below; the index
    // itself is regular. ux_provider_billing_external_id (the other PDM
    // partial unique, WHERE external_billing_id IS NOT NULL) follows the
    // Stage-2 I-14 pattern: regular index + service-transaction
    // uniqueness in ProviderBillingService.
    // ------------------------------------------------------------------

    private val CREATE_INDICES = listOf(
        "CREATE INDEX IF NOT EXISTS ix_currency_conversion_pair ON currency_conversion_rule(from_currency, to_currency, effective_from_epoch_ms)",
        "CREATE INDEX IF NOT EXISTS ix_provider_billing_period_scope ON provider_billing_record(provider_id, scope_id, period_start_epoch_ms, period_end_epoch_ms)",
        "CREATE INDEX IF NOT EXISTS ix_provider_billing_external_id ON provider_billing_record(provider_id, access_context_id, external_billing_id)",
        "CREATE INDEX IF NOT EXISTS ix_reconciliation_billing_scope_period ON reconciliation_record(provider_billing_record_id, scope_id, period_start_epoch_ms, period_end_epoch_ms, finality_status)",
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_reconciliation_inclusion_line ON reconciliation_inclusion(reconciliation_id, cost_calculation_line_id)",
        "CREATE INDEX IF NOT EXISTS ix_reconciliation_adjustment_reconciliation ON reconciliation_adjustment(reconciliation_id)",
        "CREATE INDEX IF NOT EXISTS ix_entitlement_record_scope ON entitlement_record(applicability_scope_id)",
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_entitlement_consumption_idem ON entitlement_consumption(entitlement_id, idempotency_key)",
        "CREATE INDEX IF NOT EXISTS ix_entitlement_consumption_usage_record ON entitlement_consumption(usage_record_id)"
    )

    // ------------------------------------------------------------------
    // Triggers. SQLite has no combined UPDATE OR DELETE event (verified
    // against the real engine in Stage 2) — separate triggers per event.
    // ------------------------------------------------------------------

    val TRIGGERS = listOf(
        // G03: a rule that a reconciliation depends on is frozen —
        // corrections ship as a NEW rule row with a new window.
        // (PDM trigger adapted: the budget_ledger_entry.conversion_rule_id
        // reference does not exist in the v23 schema — the ledger keeps no
        // FX column by design; reconciliation_record is the FX evidence
        // home in this schema, interpretation I-22.)
        """
        CREATE TRIGGER IF NOT EXISTS trg_currency_rule_immutable_when_used
        BEFORE UPDATE ON currency_conversion_rule
        WHEN EXISTS (SELECT 1 FROM reconciliation_record WHERE conversion_rule_id=OLD.currency_conversion_rule_id)
        BEGIN SELECT RAISE(ABORT, 'currency conversion rule used by a reconciliation is immutable — register a new rule'); END
        """.trimIndent(),
        // Provider billing records are immutable (PDM verbatim semantics).
        """
        CREATE TRIGGER IF NOT EXISTS trg_no_update_billing_record
        BEFORE UPDATE ON provider_billing_record
        BEGIN SELECT RAISE(ABORT, 'provider billing records are immutable — append a correction or adjustment record'); END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS trg_no_delete_billing_record
        BEFORE DELETE ON provider_billing_record
        BEGIN SELECT RAISE(ABORT, 'provider billing records are immutable'); END
        """.trimIndent(),
        // G03 (PDM): on insert, a cross-currency reconciliation's rule must
        // exist AND convert the NON-comparison currency INTO the comparison
        // currency — direction can never be silently flipped.
        """
        CREATE TRIGGER IF NOT EXISTS trg_reconciliation_conversion_rule_insert
        BEFORE INSERT ON reconciliation_record
        WHEN NEW.provider_currency <> NEW.local_currency
        BEGIN
          SELECT CASE WHEN NOT EXISTS (
            SELECT 1 FROM currency_conversion_rule r
            WHERE r.currency_conversion_rule_id=NEW.conversion_rule_id
              AND ((NEW.comparison_currency=NEW.local_currency AND r.from_currency=NEW.provider_currency AND r.to_currency=NEW.local_currency)
                OR (NEW.comparison_currency=NEW.provider_currency AND r.from_currency=NEW.local_currency AND r.to_currency=NEW.provider_currency))
          ) THEN RAISE(ABORT, 'conversion rule direction must convert non-comparison currency to comparison currency') END;
        END
        """.trimIndent(),
        // G04 (PDM, adapted): finalizing rejects a cost line that another
        // FINALIZED reconciliation of the SAME billing record already
        // includes. PDM defect caught by scripts/verify_stage3_ddl.py: the
        // original condition nr.finality_status='FINALIZED' (nr = the row
        // being finalized) is UNSATISFIABLE in a BEFORE trigger — SQLite
        // reads the pre-update table, where that row is still DRAFT. The
        // condition is redundant (the WHEN clause already pins
        // NEW.finality_status='FINALIZED') and is dropped; the JOIN stays
        // for the billing-record match (interpretation I-30).
        """
        CREATE TRIGGER IF NOT EXISTS trg_reconciliation_finality_inclusion_guard
        BEFORE UPDATE OF finality_status ON reconciliation_record
        WHEN NEW.finality_status='FINALIZED'
        BEGIN
          SELECT CASE WHEN EXISTS (
            SELECT 1 FROM reconciliation_inclusion ni
            JOIN reconciliation_record nr ON nr.reconciliation_id=ni.reconciliation_id
            JOIN reconciliation_inclusion oi ON oi.cost_calculation_line_id=ni.cost_calculation_line_id
            JOIN reconciliation_record oldr ON oldr.reconciliation_id=oi.reconciliation_id
            WHERE ni.reconciliation_id=NEW.reconciliation_id
              AND oi.reconciliation_id<>NEW.reconciliation_id
              AND nr.provider_billing_record_id=oldr.provider_billing_record_id
              AND oldr.finality_status='FINALIZED'
          ) THEN RAISE(ABORT, 'cost line already included in another finalized reconciliation for same billing record') END;
        END
        """.trimIndent(),
        // v0.2 change 7: ux_finalized_reconciliation_key as a trigger pair —
        // at most ONE finalized reconciliation per (billing, scope, period).
        """
        CREATE TRIGGER IF NOT EXISTS trg_reconciliation_finalized_unique_insert
        BEFORE INSERT ON reconciliation_record
        WHEN NEW.finality_status='FINALIZED'
        BEGIN
          SELECT CASE WHEN EXISTS (
            SELECT 1 FROM reconciliation_record r
            WHERE r.provider_billing_record_id=NEW.provider_billing_record_id
              AND r.scope_id=NEW.scope_id
              AND r.period_start_epoch_ms=NEW.period_start_epoch_ms
              AND r.period_end_epoch_ms=NEW.period_end_epoch_ms
              AND r.reconciliation_id<>NEW.reconciliation_id
              AND r.finality_status='FINALIZED'
          ) THEN RAISE(ABORT, 'a finalized reconciliation already exists for this billing record, scope and period') END;
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS trg_reconciliation_finalized_unique_update
        BEFORE UPDATE OF finality_status ON reconciliation_record
        WHEN NEW.finality_status='FINALIZED'
        BEGIN
          SELECT CASE WHEN EXISTS (
            SELECT 1 FROM reconciliation_record r
            WHERE r.provider_billing_record_id=NEW.provider_billing_record_id
              AND r.scope_id=NEW.scope_id
              AND r.period_start_epoch_ms=NEW.period_start_epoch_ms
              AND r.period_end_epoch_ms=NEW.period_end_epoch_ms
              AND r.reconciliation_id<>NEW.reconciliation_id
              AND r.finality_status='FINALIZED'
          ) THEN RAISE(ABORT, 'a finalized reconciliation already exists for this billing record, scope and period') END;
        END
        """.trimIndent(),
        // G04: only DRAFT rows are mutable (DRAFT->FINALIZED sign-off,
        // DRAFT->REVOKED supersession); FINALIZED and REVOKED are frozen.
        """
        CREATE TRIGGER IF NOT EXISTS trg_reconciliation_no_update_frozen
        BEFORE UPDATE ON reconciliation_record
        WHEN OLD.finality_status<>'DRAFT'
        BEGIN SELECT RAISE(ABORT, 'reconciliation is no longer a draft — finalize state is frozen; corrections ship as a new reconciliation'); END
        """.trimIndent(),
        // G04 / ADR-B2: the reconciliation trail is append-only.
        """
        CREATE TRIGGER IF NOT EXISTS trg_reconciliation_no_delete
        BEFORE DELETE ON reconciliation_record
        BEGIN SELECT RAISE(ABORT, 'reconciliation records are append-only — supersede, never delete'); END
        """.trimIndent(),
        // Inclusions are immutable (PDM) — and only a DRAFT reconciliation
        // accepts new inclusions (Stage-3 strengthening, I-25).
        """
        CREATE TRIGGER IF NOT EXISTS trg_no_update_reconciliation_inclusion
        BEFORE UPDATE ON reconciliation_inclusion
        BEGIN SELECT RAISE(ABORT, 'reconciliation inclusions are immutable — supersede the reconciliation'); END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS trg_no_delete_reconciliation_inclusion
        BEFORE DELETE ON reconciliation_inclusion
        BEGIN SELECT RAISE(ABORT, 'reconciliation inclusions are immutable'); END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS trg_reconciliation_inclusion_draft_guard
        BEFORE INSERT ON reconciliation_inclusion
        WHEN (SELECT finality_status FROM reconciliation_record WHERE reconciliation_id=NEW.reconciliation_id)<>'DRAFT'
        BEGIN SELECT RAISE(ABORT, 'inclusions can only be added to a draft reconciliation'); END
        """.trimIndent(),
        // Adjustments: append-only evidence (ADR-B2 Class A), drafts only.
        """
        CREATE TRIGGER IF NOT EXISTS trg_no_update_reconciliation_adjustment
        BEFORE UPDATE ON reconciliation_adjustment
        BEGIN SELECT RAISE(ABORT, 'reconciliation adjustments are immutable — supersede the reconciliation'); END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS trg_no_delete_reconciliation_adjustment
        BEFORE DELETE ON reconciliation_adjustment
        BEGIN SELECT RAISE(ABORT, 'reconciliation adjustments are immutable'); END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS trg_reconciliation_adjustment_draft_guard
        BEFORE INSERT ON reconciliation_adjustment
        WHEN (SELECT finality_status FROM reconciliation_record WHERE reconciliation_id=NEW.reconciliation_id)<>'DRAFT'
        BEGIN SELECT RAISE(ABORT, 'adjustments can only be added to a draft reconciliation'); END
        """.trimIndent(),
        // Entitlement consumption: append-only idempotent evidence.
        """
        CREATE TRIGGER IF NOT EXISTS trg_no_update_entitlement_consumption
        BEFORE UPDATE ON entitlement_consumption
        BEGIN SELECT RAISE(ABORT, 'entitlement consumption records are immutable'); END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS trg_no_delete_entitlement_consumption
        BEFORE DELETE ON entitlement_consumption
        BEGIN SELECT RAISE(ABORT, 'entitlement consumption records are immutable'); END
        """.trimIndent()
    )

    /** Execution order: tables (dependency-safe), indices, triggers. No seeds — Stage-3 rows are user/operator data, not reference data. */
    val STATEMENTS: List<String> = listOf(
        CREATE_CURRENCY_CONVERSION_RULE,
        CREATE_PROVIDER_BILLING_RECORD,
        CREATE_RECONCILIATION_RECORD,
        CREATE_RECONCILIATION_INCLUSION,
        CREATE_RECONCILIATION_ADJUSTMENT,
        CREATE_ENTITLEMENT_RECORD,
        CREATE_ENTITLEMENT_CONSUMPTION
    ) + CREATE_INDICES + TRIGGERS
}
