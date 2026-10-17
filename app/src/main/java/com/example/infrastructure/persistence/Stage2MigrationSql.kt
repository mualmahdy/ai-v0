package com.example.infrastructure.persistence

/**
 * ============================================================================
 * SINGLE SOURCE OF TRUTH for the MIGRATION_24_TO_25 DDL (Stage 2).
 * ============================================================================
 * Stage-2 pricing, metering & itemized cost breakdown (roadmap Phase 2,
 * approved with the feasibility study; first Stage-2 item I-5 settlement
 * hooks live in HoldSettlementService, not here).
 *
 * Provenance of every statement:
 *  - Tables + columns: PDM v0.1 Group B (unit/metric/pricing) + Group E
 *    (usage/cost) subset, adapted per the APPROVED PDM v0.2 rules:
 *      * *_minor_units -> *_micro_units (ADR-B1),
 *      * TEXT timestamps -> INTEGER epoch_ms (v0.2 change 4),
 *      * partial/expression unique indices -> regular named indices with
 *        service-transaction uniqueness (v0.2 change 7; interpretation I-14),
 *      * references to not-yet-existing tables (provider, provider_request_
 *        attempt) stay as plain TEXT columns — the deferred-FK pattern
 *        (v0.2 change 8; interpretation I-12).
 *  - UNIQUE table constraints become NAMED indices (Stage-0 Section د.2
 *    rule 4) and are declared identically on the Room entities.
 *  - The 18 immutability triggers (a no-update + a no-delete pair per table) extend ADR-B2 (Class A financial evidence:
 *    append-only; corrections via supersession/versioning, never UPDATE or
 *    DELETE). pricing_profile itself stays mutable (profile_status) —
 *    administrative record, not financial evidence.
 *  - Seed rows (1 unit, 4 metrics) are reference data consumed by
 *    pricing_component / usage_measurement foreign keys.
 *  - The pricing_entries -> pricing_profile_version DATA migration is Kotlin
 *    code inside MIGRATION_24_TO_25 (needs SHA-256 + window clamping logic),
 *    in [PricingCatalogMigration].
 *
 * No existing table is touched anywhere in this list.
 */
internal object Stage2MigrationSql {

    // ------------------------------------------------------------------
    // Reference data: units + metrics
    // ------------------------------------------------------------------

    val CREATE_UNIT_DEFINITION = """
        CREATE TABLE IF NOT EXISTS unit_definition (
          unit_id TEXT PRIMARY KEY NOT NULL,
          unit_code TEXT NOT NULL,
          unit_family TEXT NOT NULL,
          dimension TEXT NOT NULL,
          description TEXT,
          base_unit_id TEXT REFERENCES unit_definition(unit_id) ON DELETE RESTRICT,
          conversion_rule TEXT,
          conversion_authority TEXT,
          created_at_epoch_ms INTEGER NOT NULL
        )
    """.trimIndent()

    val CREATE_METRIC_DEFINITION = """
        CREATE TABLE IF NOT EXISTS metric_definition (
          metric_id TEXT PRIMARY KEY NOT NULL,
          metric_code TEXT NOT NULL,
          metric_domain TEXT NOT NULL,
          metric_role TEXT NOT NULL,
          metric_kind TEXT NOT NULL CHECK (metric_kind IN ('COUNTER','GAUGE','RATE','CUMULATIVE','BOOLEAN','TEXT','STATE')),
          default_unit_id TEXT REFERENCES unit_definition(unit_id) ON DELETE RESTRICT,
          value_type TEXT NOT NULL CHECK (value_type IN ('NUMERIC','TEXT','BOOLEAN','STATE')),
          aggregation_semantics TEXT NOT NULL CHECK (aggregation_semantics IN ('ADDITIVE','SUBSET_OF','DERIVED_FROM','OVERLAPPING','NON_ADDITIVE','UNKNOWN')),
          is_monotonic INTEGER NOT NULL DEFAULT 0 CHECK (is_monotonic IN (0,1)),
          reset_policy TEXT,
          default_window TEXT,
          description TEXT,
          created_at_epoch_ms INTEGER NOT NULL
        )
    """.trimIndent()

    // ------------------------------------------------------------------
    // Pricing catalog: profile -> version -> component -> tier
    // ------------------------------------------------------------------

    val CREATE_PRICING_PROFILE = """
        CREATE TABLE IF NOT EXISTS pricing_profile (
          pricing_profile_id TEXT PRIMARY KEY NOT NULL,
          provider_id TEXT NOT NULL,
          profile_code TEXT NOT NULL,
          currency TEXT NOT NULL CHECK (length(currency)=3),
          applicability_scope_id TEXT NOT NULL REFERENCES scope_descriptor(scope_id) ON DELETE RESTRICT,
          profile_status TEXT NOT NULL,
          created_at_epoch_ms INTEGER NOT NULL,
          updated_at_epoch_ms INTEGER NOT NULL
        )
    """.trimIndent()

    val CREATE_PRICING_PROFILE_VERSION = """
        CREATE TABLE IF NOT EXISTS pricing_profile_version (
          pricing_profile_version_id TEXT PRIMARY KEY NOT NULL,
          pricing_profile_id TEXT NOT NULL REFERENCES pricing_profile(pricing_profile_id) ON DELETE RESTRICT,
          version_number INTEGER NOT NULL CHECK (version_number > 0),
          pricing_source TEXT NOT NULL,
          authority TEXT NOT NULL,
          effective_from_epoch_ms INTEGER NOT NULL,
          effective_until_epoch_ms INTEGER,
          retrieved_at_epoch_ms INTEGER,
          recorded_at_epoch_ms INTEGER NOT NULL,
          effective_status TEXT NOT NULL,
          verification_status TEXT NOT NULL,
          precedence INTEGER NOT NULL DEFAULT 0,
          pricing_status TEXT NOT NULL CHECK (pricing_status IN ('CALCULABLE','PARTIALLY_CALCULABLE','NOT_CALCULABLE')),
          rounding_strategy TEXT NOT NULL CHECK (rounding_strategy IN ('ROUND_LINE_THEN_SUM','ROUND_SUM_THEN_ALLOCATE','PROVIDER_DEFINED','UNKNOWN')),
          created_at_epoch_ms INTEGER NOT NULL,
          CHECK (effective_until_epoch_ms IS NULL OR effective_from_epoch_ms < effective_until_epoch_ms)
        )
    """.trimIndent()

    val CREATE_PRICING_COMPONENT = """
        CREATE TABLE IF NOT EXISTS pricing_component (
          pricing_component_id TEXT PRIMARY KEY NOT NULL,
          pricing_profile_version_id TEXT NOT NULL REFERENCES pricing_profile_version(pricing_profile_version_id) ON DELETE RESTRICT,
          component_code TEXT NOT NULL,
          component_type TEXT NOT NULL,
          metric_id TEXT REFERENCES metric_definition(metric_id) ON DELETE RESTRICT,
          quantity_unit_id TEXT REFERENCES unit_definition(unit_id) ON DELETE RESTRICT,
          currency TEXT NOT NULL CHECK (length(currency)=3),
          calculation_mode TEXT NOT NULL CHECK (calculation_mode IN ('SIMPLE_UNIT_PRICE','TIERED','FIXED_FEE','PERCENTAGE','FORMULA','UNSUPPORTED')),
          component_application_basis TEXT,
          tier_application_mode TEXT NOT NULL CHECK (tier_application_mode IN ('GRADUATED','VOLUME','NOT_APPLICABLE')),
          quantity_scope TEXT NOT NULL CHECK (quantity_scope IN ('PER_REQUEST','PER_PERIOD','UNKNOWN')),
          rate_amount_precise TEXT,
          price_unit_quantity_precise TEXT,
          rounding_policy TEXT,
          component_order INTEGER NOT NULL,
          component_status TEXT NOT NULL
        )
    """.trimIndent()

    val CREATE_PRICING_TIER = """
        CREATE TABLE IF NOT EXISTS pricing_tier (
          tier_id TEXT PRIMARY KEY NOT NULL,
          component_id TEXT NOT NULL REFERENCES pricing_component(pricing_component_id) ON DELETE RESTRICT,
          tier_order INTEGER NOT NULL,
          lower_bound_precise TEXT NOT NULL,
          upper_bound_precise TEXT,
          bound_closure TEXT NOT NULL,
          quantity_unit_id TEXT NOT NULL REFERENCES unit_definition(unit_id) ON DELETE RESTRICT,
          unit_price_precise TEXT,
          flat_fee_precise TEXT,
          currency TEXT NOT NULL CHECK (length(currency)=3)
        )
    """.trimIndent()

    // ------------------------------------------------------------------
    // Usage metering: report -> measurements
    // ------------------------------------------------------------------

    val CREATE_USAGE_RECORD = """
        CREATE TABLE IF NOT EXISTS usage_record (
          usage_record_id TEXT PRIMARY KEY NOT NULL,
          request_attempt_id TEXT NOT NULL,
          report_source TEXT NOT NULL,
          report_version TEXT NOT NULL,
          reported_at_epoch_ms INTEGER,
          retrieved_at_epoch_ms INTEGER,
          report_status TEXT NOT NULL,
          is_final_report INTEGER NOT NULL DEFAULT 0 CHECK (is_final_report IN (0,1)),
          supersedes_usage_record_id TEXT REFERENCES usage_record(usage_record_id) ON DELETE RESTRICT,
          provider_request_id TEXT,
          recorded_at_epoch_ms INTEGER NOT NULL
        )
    """.trimIndent()

    val CREATE_USAGE_MEASUREMENT = """
        CREATE TABLE IF NOT EXISTS usage_measurement (
          measurement_id TEXT PRIMARY KEY NOT NULL,
          usage_record_id TEXT NOT NULL REFERENCES usage_record(usage_record_id) ON DELETE RESTRICT,
          metric_id TEXT NOT NULL REFERENCES metric_definition(metric_id) ON DELETE RESTRICT,
          value_precise TEXT,
          unit_id TEXT REFERENCES unit_definition(unit_id) ON DELETE RESTRICT,
          source_authority TEXT NOT NULL,
          source_field TEXT,
          measurement_status TEXT NOT NULL,
          knowledge_status TEXT NOT NULL CHECK (knowledge_status IN ('KNOWN','UNKNOWN','UNAVAILABLE','UNSUPPORTED','NOT_APPLICABLE')),
          unknown_reason_code TEXT,
          recorded_at_epoch_ms INTEGER NOT NULL,
          CHECK ((knowledge_status='KNOWN' AND value_precise IS NOT NULL) OR (knowledge_status<>'KNOWN' AND value_precise IS NULL))
        )
    """.trimIndent()

    // ------------------------------------------------------------------
    // Itemized cost: calculation -> inputs -> lines -> line inputs
    // ------------------------------------------------------------------

    val CREATE_COST_CALCULATION = """
        CREATE TABLE IF NOT EXISTS cost_calculation (
          cost_calculation_id TEXT PRIMARY KEY NOT NULL,
          calculation_level TEXT NOT NULL CHECK (calculation_level IN ('REQUEST_ATTEMPT','PERIOD','CUSTOM')),
          request_attempt_id TEXT,
          usage_record_id TEXT REFERENCES usage_record(usage_record_id) ON DELETE RESTRICT,
          calculation_scope_id TEXT REFERENCES scope_descriptor(scope_id) ON DELETE RESTRICT,
          period_start_epoch_ms INTEGER,
          period_end_epoch_ms INTEGER,
          pricing_profile_version_id TEXT NOT NULL REFERENCES pricing_profile_version(pricing_profile_version_id) ON DELETE RESTRICT,
          calculation_engine_version TEXT NOT NULL,
          rule_set_version TEXT NOT NULL,
          input_hash TEXT NOT NULL,
          currency TEXT NOT NULL CHECK (length(currency)=3),
          total_amount_micro_units INTEGER,
          calculation_status TEXT NOT NULL CHECK (calculation_status IN ('CALCULATED','ESTIMATED','PARTIAL','NOT_POSSIBLE','INVALID')),
          confidence_state TEXT NOT NULL,
          calculated_at_epoch_ms INTEGER NOT NULL,
          recorded_at_epoch_ms INTEGER NOT NULL,
          supersedes_calculation_id TEXT REFERENCES cost_calculation(cost_calculation_id) ON DELETE RESTRICT,
          CHECK ((calculation_level='REQUEST_ATTEMPT' AND request_attempt_id IS NOT NULL) OR calculation_level<>'REQUEST_ATTEMPT'),
          CHECK ((calculation_level='PERIOD' AND calculation_scope_id IS NOT NULL AND period_start_epoch_ms IS NOT NULL AND period_end_epoch_ms IS NOT NULL AND period_start_epoch_ms < period_end_epoch_ms) OR calculation_level<>'PERIOD'),
          CHECK (calculation_status<>'NOT_POSSIBLE' OR total_amount_micro_units IS NULL)
        )
    """.trimIndent()

    val CREATE_COST_CALCULATION_INPUT = """
        CREATE TABLE IF NOT EXISTS cost_calculation_input (
          input_id TEXT PRIMARY KEY NOT NULL,
          cost_calculation_id TEXT NOT NULL REFERENCES cost_calculation(cost_calculation_id) ON DELETE RESTRICT,
          input_role TEXT NOT NULL,
          usage_measurement_id TEXT REFERENCES usage_measurement(measurement_id) ON DELETE RESTRICT,
          usage_record_id TEXT REFERENCES usage_record(usage_record_id) ON DELETE RESTRICT,
          request_attempt_id TEXT,
          quantity_precise TEXT,
          unit_id TEXT REFERENCES unit_definition(unit_id) ON DELETE RESTRICT,
          included_at_epoch_ms INTEGER NOT NULL,
          source_authority TEXT NOT NULL,
          CHECK ((usage_measurement_id IS NOT NULL) + (usage_record_id IS NOT NULL) + (request_attempt_id IS NOT NULL) = 1)
        )
    """.trimIndent()

    val CREATE_COST_CALCULATION_LINE = """
        CREATE TABLE IF NOT EXISTS cost_calculation_line (
          line_id TEXT PRIMARY KEY NOT NULL,
          cost_calculation_id TEXT NOT NULL REFERENCES cost_calculation(cost_calculation_id) ON DELETE RESTRICT,
          pricing_component_id TEXT REFERENCES pricing_component(pricing_component_id) ON DELETE RESTRICT,
          line_order INTEGER NOT NULL,
          line_basis_type TEXT NOT NULL CHECK (line_basis_type IN ('USAGE_MEASUREMENT','FIXED_FEE','PERCENTAGE_SUBTOTAL','TIER_PERIOD_AGGREGATE','FORMULA','ADJUSTMENT','TAX','CREDIT','SUBSCRIPTION_FEE')),
          usage_measurement_id TEXT REFERENCES usage_measurement(measurement_id) ON DELETE RESTRICT,
          basis_quantity_precise TEXT,
          basis_unit_id TEXT REFERENCES unit_definition(unit_id) ON DELETE RESTRICT,
          base_amount_micro_units INTEGER,
          rate_value_precise TEXT,
          unit_price_precise TEXT,
          pre_rounded_amount_precise TEXT,
          final_amount_micro_units INTEGER,
          currency TEXT NOT NULL CHECK (length(currency)=3),
          rounding_mode TEXT NOT NULL,
          line_status TEXT NOT NULL,
          notes TEXT,
          CHECK (line_basis_type<>'USAGE_MEASUREMENT' OR usage_measurement_id IS NOT NULL)
        )
    """.trimIndent()

    val CREATE_COST_CALCULATION_LINE_INPUT = """
        CREATE TABLE IF NOT EXISTS cost_calculation_line_input (
          line_input_id TEXT PRIMARY KEY NOT NULL,
          line_id TEXT NOT NULL REFERENCES cost_calculation_line(line_id) ON DELETE RESTRICT,
          input_role TEXT NOT NULL,
          usage_measurement_id TEXT REFERENCES usage_measurement(measurement_id) ON DELETE RESTRICT,
          calculation_input_id TEXT REFERENCES cost_calculation_input(input_id) ON DELETE RESTRICT,
          cost_calculation_line_id TEXT REFERENCES cost_calculation_line(line_id) ON DELETE RESTRICT,
          constant_value_precise TEXT,
          quantity_precise TEXT,
          unit_id TEXT REFERENCES unit_definition(unit_id) ON DELETE RESTRICT,
          notes TEXT,
          CHECK ((usage_measurement_id IS NOT NULL) + (calculation_input_id IS NOT NULL) + (cost_calculation_line_id IS NOT NULL) + (constant_value_precise IS NOT NULL) = 1),
          CHECK (cost_calculation_line_id IS NULL OR cost_calculation_line_id <> line_id)
        )
    """.trimIndent()

    // ------------------------------------------------------------------
    // Named indices — every UNIQUE from the PDM DDL becomes a NAMED index
    // (Stage-0 Section د.2 rule 4) declared identically on the entities.
    // The PDM's PARTIAL unique indices (ux_usage_measurement_source with
    // COALESCE, ux_cost_calculation_input_* with WHERE clauses) cannot be
    // declared to Room: they become the regular indices below and their
    // uniqueness is enforced inside the writing service's transaction
    // (v0.2 change 7 pattern; interpretation I-14).
    // ------------------------------------------------------------------

    private val CREATE_INDICES = listOf(
        // unit/metric reference codes
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_unit_definition_code ON unit_definition(unit_code)",
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_metric_definition_code ON metric_definition(metric_code)",
        // pricing catalog
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_pricing_profile_provider_code ON pricing_profile(provider_id, profile_code)",
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_ppv_profile_version ON pricing_profile_version(pricing_profile_id, version_number)",
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_ppv_id_profile ON pricing_profile_version(pricing_profile_version_id, pricing_profile_id)",
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_pricing_component_version_code_order ON pricing_component(pricing_profile_version_id, component_code, component_order)",
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_pricing_tier_component_order ON pricing_tier(component_id, tier_order)",
        // usage metering
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_usage_record_attempt_source_version ON usage_record(request_attempt_id, report_source, report_version)",
        "CREATE INDEX IF NOT EXISTS ix_usage_measurement_record ON usage_measurement(usage_record_id, metric_id)",
        // cost calculations
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_cost_calculation_line_order ON cost_calculation_line(cost_calculation_id, line_order)",
        "CREATE INDEX IF NOT EXISTS ix_cost_calculation_attempt ON cost_calculation(request_attempt_id, calculated_at_epoch_ms)",
        "CREATE INDEX IF NOT EXISTS ix_cost_calculation_scope_period ON cost_calculation(calculation_scope_id, period_start_epoch_ms, period_end_epoch_ms)",
        "CREATE INDEX IF NOT EXISTS ix_cost_calculation_input_parent ON cost_calculation_input(cost_calculation_id)",
        "CREATE INDEX IF NOT EXISTS ix_cost_line_input_parent ON cost_calculation_line_input(line_id)"
    )

    // ------------------------------------------------------------------
    // Seeds: one unit + four metrics consumed by pricing_component and
    // usage_measurement FKs. Reference data, deterministic ids — safe to
    // re-run (INSERT OR IGNORE).
    // ------------------------------------------------------------------

    private val SEEDS = listOf(
        "INSERT OR IGNORE INTO unit_definition (unit_id, unit_code, unit_family, dimension, description, base_unit_id, conversion_rule, conversion_authority, created_at_epoch_ms) VALUES ('UNIT_TOKEN', 'TOKEN', 'TOKEN_COUNT', 'QUANTITY', 'LLM tokens (the platform''s canonical billing quantity)', NULL, NULL, NULL, 0)",
        "INSERT OR IGNORE INTO metric_definition (metric_id, metric_code, metric_domain, metric_role, metric_kind, default_unit_id, value_type, aggregation_semantics, is_monotonic, reset_policy, default_window, description, created_at_epoch_ms) VALUES ('METRIC_INPUT_TOKENS', 'INPUT_TOKENS', 'LLM_USAGE', 'CONSUMED', 'COUNTER', 'UNIT_TOKEN', 'NUMERIC', 'ADDITIVE', 0, 'REQUEST', NULL, 'Prompt tokens reported for a request', 0)",
        "INSERT OR IGNORE INTO metric_definition (metric_id, metric_code, metric_domain, metric_role, metric_kind, default_unit_id, value_type, aggregation_semantics, is_monotonic, reset_policy, default_window, description, created_at_epoch_ms) VALUES ('METRIC_OUTPUT_TOKENS', 'OUTPUT_TOKENS', 'LLM_USAGE', 'CONSUMED', 'COUNTER', 'UNIT_TOKEN', 'NUMERIC', 'ADDITIVE', 0, 'REQUEST', NULL, 'Completion tokens reported for a request', 0)",
        "INSERT OR IGNORE INTO metric_definition (metric_id, metric_code, metric_domain, metric_role, metric_kind, default_unit_id, value_type, aggregation_semantics, is_monotonic, reset_policy, default_window, description, created_at_epoch_ms) VALUES ('METRIC_CACHED_TOKENS', 'CACHED_TOKENS', 'LLM_USAGE', 'CONSUMED', 'COUNTER', 'UNIT_TOKEN', 'NUMERIC', 'ADDITIVE', 0, 'REQUEST', NULL, 'Prompt tokens served from cache where the provider prices them separately', 0)",
        "INSERT OR IGNORE INTO metric_definition (metric_id, metric_code, metric_domain, metric_role, metric_kind, default_unit_id, value_type, aggregation_semantics, is_monotonic, reset_policy, default_window, description, created_at_epoch_ms) VALUES ('METRIC_TOTAL_TOKENS', 'TOTAL_TOKENS', 'LLM_USAGE', 'CONSUMED', 'COUNTER', 'UNIT_TOKEN', 'NUMERIC', 'DERIVED_FROM', 0, 'REQUEST', NULL, 'Total tokens (input + output, or the provider-reported total when present)', 0)"
    )

    // ------------------------------------------------------------------
    // Triggers — ADR-B2 extension: every Stage-2 fact table is Class A
    // financial evidence. Append-only: corrections arrive as new versions
    // (pricing), superseding reports (usage) or superseding calculations
    // (cost) — never as UPDATE or DELETE. pricing_profile itself stays
    // mutable (administrative status record).
    // SQLite has NO combined "UPDATE OR DELETE" event (verified against the
    // real engine by scripts/verify_stage2_ddl.py — the combined syntax is
    // a MySQLism): each table gets a separate no-update and no-delete
    // trigger, eighteen in all, mirroring the Stage-1 naming style.
    // ------------------------------------------------------------------

    val TRIGGERS = listOf(
        // pricing catalog evidence
        """
        CREATE TRIGGER IF NOT EXISTS trg_pp_version_no_update
        BEFORE UPDATE ON pricing_profile_version
        BEGIN SELECT RAISE(ABORT, 'pricing profile versions are immutable — corrections ship as a new version row'); END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS trg_pp_version_no_delete
        BEFORE DELETE ON pricing_profile_version
        BEGIN SELECT RAISE(ABORT, 'pricing profile versions are immutable — corrections ship as a new version row'); END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS trg_pricing_component_no_update
        BEFORE UPDATE ON pricing_component
        BEGIN SELECT RAISE(ABORT, 'pricing components are immutable — corrections ship as a new profile version'); END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS trg_pricing_component_no_delete
        BEFORE DELETE ON pricing_component
        BEGIN SELECT RAISE(ABORT, 'pricing components are immutable — corrections ship as a new profile version'); END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS trg_pricing_tier_no_update
        BEFORE UPDATE ON pricing_tier
        BEGIN SELECT RAISE(ABORT, 'pricing tiers are immutable — corrections ship as a new profile version'); END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS trg_pricing_tier_no_delete
        BEFORE DELETE ON pricing_tier
        BEGIN SELECT RAISE(ABORT, 'pricing tiers are immutable — corrections ship as a new profile version'); END
        """.trimIndent(),
        // usage facts
        """
        CREATE TRIGGER IF NOT EXISTS trg_usage_record_no_update
        BEFORE UPDATE ON usage_record
        BEGIN SELECT RAISE(ABORT, 'usage records are immutable — revisions ship as superseding reports'); END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS trg_usage_record_no_delete
        BEFORE DELETE ON usage_record
        BEGIN SELECT RAISE(ABORT, 'usage records are immutable — revisions ship as superseding reports'); END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS trg_usage_measurement_no_update
        BEFORE UPDATE ON usage_measurement
        BEGIN SELECT RAISE(ABORT, 'usage measurements are immutable — revisions ship as superseding reports'); END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS trg_usage_measurement_no_delete
        BEFORE DELETE ON usage_measurement
        BEGIN SELECT RAISE(ABORT, 'usage measurements are immutable — revisions ship as superseding reports'); END
        """.trimIndent(),
        // cost calculations
        """
        CREATE TRIGGER IF NOT EXISTS trg_cost_calculation_no_update
        BEFORE UPDATE ON cost_calculation
        BEGIN SELECT RAISE(ABORT, 'cost calculations are immutable — corrections ship as superseding calculations'); END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS trg_cost_calculation_no_delete
        BEFORE DELETE ON cost_calculation
        BEGIN SELECT RAISE(ABORT, 'cost calculations are immutable — corrections ship as superseding calculations'); END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS trg_cost_calculation_input_no_update
        BEFORE UPDATE ON cost_calculation_input
        BEGIN SELECT RAISE(ABORT, 'cost calculation inputs are immutable'); END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS trg_cost_calculation_input_no_delete
        BEFORE DELETE ON cost_calculation_input
        BEGIN SELECT RAISE(ABORT, 'cost calculation inputs are immutable'); END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS trg_cost_line_no_update
        BEFORE UPDATE ON cost_calculation_line
        BEGIN SELECT RAISE(ABORT, 'cost calculation lines are immutable'); END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS trg_cost_line_no_delete
        BEFORE DELETE ON cost_calculation_line
        BEGIN SELECT RAISE(ABORT, 'cost calculation lines are immutable'); END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS trg_cost_line_input_no_update
        BEFORE UPDATE ON cost_calculation_line_input
        BEGIN SELECT RAISE(ABORT, 'cost calculation line inputs are immutable'); END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS trg_cost_line_input_no_delete
        BEFORE DELETE ON cost_calculation_line_input
        BEGIN SELECT RAISE(ABORT, 'cost calculation line inputs are immutable'); END
        """.trimIndent()
    )

    /** Execution order: tables (dependency-safe), indices, seeds, triggers. */
    val STATEMENTS: List<String> = listOf(
        CREATE_UNIT_DEFINITION,
        CREATE_METRIC_DEFINITION,
        CREATE_PRICING_PROFILE,
        CREATE_PRICING_PROFILE_VERSION,
        CREATE_PRICING_COMPONENT,
        CREATE_PRICING_TIER,
        CREATE_USAGE_RECORD,
        CREATE_USAGE_MEASUREMENT,
        CREATE_COST_CALCULATION,
        CREATE_COST_CALCULATION_INPUT,
        CREATE_COST_CALCULATION_LINE,
        CREATE_COST_CALCULATION_LINE_INPUT
    ) + CREATE_INDICES + SEEDS + TRIGGERS
}
