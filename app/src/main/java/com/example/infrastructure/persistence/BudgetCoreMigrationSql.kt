package com.example.infrastructure.persistence

/**
 * ============================================================================
 * SINGLE SOURCE OF TRUTH for the MIGRATION_22_TO_23 DDL.
 * ============================================================================
 * Stage-1 budget core (approved 2026-10-14, "أعتمد الكل" — D-5/D-6/D-7).
 * The statements below are executed verbatim by MIGRATION_22_TO_23 and are
 * the same statements verified standalone against SQLite by
 * scripts/verify_budget_core_ddl.py (trigger/CHECK/UNIQUE rejection tests).
 *
 * Provenance of every statement:
 *  - Tables + columns: approved Section و.1 DDL (PDM v0.2 Stage-1 subset).
 *  - The 8 adapted triggers: PDM v0.1 SQL lines 1278-1345, with only the
 *    mechanical renames minor_units → micro_units (ADR-B1).
 *  - The 2 new triggers (posted immutability + append-only): ADR-B4.
 *  - UNIQUE table constraints from the approved DDL are created as NAMED
 *    indices because Room compares declared indices, not table constraints
 *    (Stage-0 Section د.2 rule 4).
 *  - No existing table is touched anywhere in this list (approved
 *    migration plan: "touches_existing_tables: false").
 */
internal object BudgetCoreMigrationSql {

    val CREATE_SCOPE_DESCRIPTOR = """
        CREATE TABLE IF NOT EXISTS scope_descriptor (
          scope_id TEXT PRIMARY KEY NOT NULL,
          scope_kind TEXT NOT NULL,
          canonical_representation TEXT NOT NULL,
          canonical_hash TEXT NOT NULL,
          hash_algorithm TEXT NOT NULL,
          canonicalization_version INTEGER NOT NULL CHECK (canonicalization_version > 0),
          provider_dimension_state TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK (provider_dimension_state IN ('KNOWN','UNKNOWN','NOT_APPLICABLE')),
          provider_id TEXT,
          integration_dimension_state TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK (integration_dimension_state IN ('KNOWN','UNKNOWN','NOT_APPLICABLE')),
          integration_id TEXT,
          access_context_dimension_state TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK (access_context_dimension_state IN ('KNOWN','UNKNOWN','NOT_APPLICABLE')),
          access_context_id TEXT,
          provider_scope_node_dimension_state TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK (provider_scope_node_dimension_state IN ('KNOWN','UNKNOWN','NOT_APPLICABLE')),
          provider_scope_node_id TEXT,
          offering_dimension_state TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK (offering_dimension_state IN ('KNOWN','UNKNOWN','NOT_APPLICABLE')),
          offering_id TEXT,
          request_attempt_dimension_state TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK (request_attempt_dimension_state IN ('KNOWN','UNKNOWN','NOT_APPLICABLE')),
          request_attempt_id TEXT,
          budget_policy_dimension_state TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK (budget_policy_dimension_state IN ('KNOWN','UNKNOWN','NOT_APPLICABLE')),
          budget_policy_id TEXT REFERENCES budget_policy(budget_policy_id) ON DELETE RESTRICT,
          budget_period_dimension_state TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK (budget_period_dimension_state IN ('KNOWN','UNKNOWN','NOT_APPLICABLE')),
          budget_period_id TEXT REFERENCES budget_period(budget_period_id) ON DELETE RESTRICT,
          region_dimension_state TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK (region_dimension_state IN ('KNOWN','UNKNOWN','NOT_APPLICABLE')),
          region TEXT,
          endpoint_dimension_state TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK (endpoint_dimension_state IN ('KNOWN','UNKNOWN','NOT_APPLICABLE')),
          endpoint_ref TEXT,
          period_dimension_state TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK (period_dimension_state IN ('KNOWN','UNKNOWN','NOT_APPLICABLE')),
          period_start TEXT,
          period_end TEXT,
          platform_dimension_state TEXT NOT NULL DEFAULT 'UNKNOWN' CHECK (platform_dimension_state IN ('KNOWN','UNKNOWN','NOT_APPLICABLE')),
          platform_object_type TEXT,
          platform_object_id TEXT,
          created_at_epoch_ms INTEGER NOT NULL,
          CHECK ((provider_dimension_state='KNOWN' AND provider_id IS NOT NULL) OR (provider_dimension_state<>'KNOWN' AND provider_id IS NULL)),
          CHECK ((integration_dimension_state='KNOWN' AND integration_id IS NOT NULL) OR (integration_dimension_state<>'KNOWN' AND integration_id IS NULL)),
          CHECK ((access_context_dimension_state='KNOWN' AND access_context_id IS NOT NULL) OR (access_context_dimension_state<>'KNOWN' AND access_context_id IS NULL)),
          CHECK ((provider_scope_node_dimension_state='KNOWN' AND provider_scope_node_id IS NOT NULL) OR (provider_scope_node_dimension_state<>'KNOWN' AND provider_scope_node_id IS NULL)),
          CHECK ((offering_dimension_state='KNOWN' AND offering_id IS NOT NULL) OR (offering_dimension_state<>'KNOWN' AND offering_id IS NULL)),
          CHECK ((request_attempt_dimension_state='KNOWN' AND request_attempt_id IS NOT NULL) OR (request_attempt_dimension_state<>'KNOWN' AND request_attempt_id IS NULL)),
          CHECK ((budget_policy_dimension_state='KNOWN' AND budget_policy_id IS NOT NULL) OR (budget_policy_dimension_state<>'KNOWN' AND budget_policy_id IS NULL)),
          CHECK ((budget_period_dimension_state='KNOWN' AND budget_period_id IS NOT NULL) OR (budget_period_dimension_state<>'KNOWN' AND budget_period_id IS NULL)),
          CHECK ((region_dimension_state='KNOWN' AND region IS NOT NULL) OR (region_dimension_state<>'KNOWN' AND region IS NULL)),
          CHECK ((endpoint_dimension_state='KNOWN' AND endpoint_ref IS NOT NULL) OR (endpoint_dimension_state<>'KNOWN' AND endpoint_ref IS NULL)),
          CHECK ((period_dimension_state='KNOWN' AND period_start IS NOT NULL AND period_end IS NOT NULL AND period_start < period_end) OR (period_dimension_state<>'KNOWN' AND period_start IS NULL AND period_end IS NULL)),
          CHECK ((platform_dimension_state='KNOWN' AND platform_object_type IS NOT NULL AND platform_object_id IS NOT NULL) OR (platform_dimension_state<>'KNOWN' AND platform_object_type IS NULL AND platform_object_id IS NULL))
        )
    """.trimIndent()

    val CREATE_BUDGET_POLICY = """
        CREATE TABLE IF NOT EXISTS budget_policy (
          budget_policy_id TEXT PRIMARY KEY NOT NULL,
          policy_family_id TEXT NOT NULL,
          budget_name TEXT NOT NULL,
          budget_scope_type TEXT NOT NULL CHECK (budget_scope_type IN
            ('APPLICATION','WORKSPACE','PROJECT','SESSION','ACCESS_CONTEXT','PROVIDER','MODEL_OFFERING',
             'AGENT','TASK','EXECUTION','STEP','CUSTOM')),
          scope_id TEXT NOT NULL REFERENCES scope_descriptor(scope_id) ON DELETE RESTRICT,
          currency TEXT NOT NULL CHECK (length(currency)=3),
          default_limit_micro_units INTEGER NOT NULL CHECK (default_limit_micro_units >= 0),
          period_type TEXT NOT NULL,
          rollover_policy TEXT NOT NULL,
          overage_policy TEXT NOT NULL,
          allocation_policy TEXT NOT NULL CHECK (allocation_policy IN ('SUPERVISORY_OVERLAP','ACCOUNTING_ALLOCATION')),
          policy_version INTEGER NOT NULL CHECK (policy_version > 0),
          effective_from_epoch_ms INTEGER NOT NULL,
          policy_status TEXT NOT NULL,
          created_at_epoch_ms INTEGER NOT NULL,
          updated_at_epoch_ms INTEGER NOT NULL
        )
    """.trimIndent()

    val CREATE_BUDGET_PERIOD = """
        CREATE TABLE IF NOT EXISTS budget_period (
          budget_period_id TEXT PRIMARY KEY NOT NULL,
          budget_policy_id TEXT NOT NULL REFERENCES budget_policy(budget_policy_id) ON DELETE RESTRICT,
          period_start_epoch_ms INTEGER NOT NULL,
          period_end_epoch_ms INTEGER NOT NULL,
          limit_micro_units INTEGER NOT NULL CHECK (limit_micro_units >= 0),
          period_status TEXT NOT NULL,
          created_at_epoch_ms INTEGER NOT NULL,
          updated_at_epoch_ms INTEGER NOT NULL,
          CHECK (period_start_epoch_ms < period_end_epoch_ms)
        )
    """.trimIndent()

    val CREATE_BUDGET_HOLD = """
        CREATE TABLE IF NOT EXISTS budget_hold (
          hold_id TEXT PRIMARY KEY NOT NULL,
          budget_period_id TEXT NOT NULL REFERENCES budget_period(budget_period_id) ON DELETE RESTRICT,
          hold_amount_micro_units INTEGER NOT NULL CHECK (hold_amount_micro_units > 0),
          currency TEXT NOT NULL CHECK (length(currency)=3),
          reason TEXT,
          request_operation_id TEXT,
          status TEXT NOT NULL CHECK (status IN ('ACTIVE','PARTIALLY_SETTLED','SETTLED','RELEASED','EXPIRED')),
          created_at_epoch_ms INTEGER NOT NULL,
          expires_at_epoch_ms INTEGER,
          idempotency_key TEXT NOT NULL,
          CHECK (expires_at_epoch_ms IS NULL OR created_at_epoch_ms < expires_at_epoch_ms)
        )
    """.trimIndent()

    val CREATE_BUDGET_LEDGER_ENTRY = """
        CREATE TABLE IF NOT EXISTS budget_ledger_entry (
          entry_id TEXT PRIMARY KEY NOT NULL,
          budget_period_id TEXT NOT NULL REFERENCES budget_period(budget_period_id) ON DELETE RESTRICT,
          entry_type TEXT NOT NULL CHECK (entry_type IN
            ('CONSUMPTION','ADJUSTMENT','REFUND','REVERSAL','ALLOCATION','CARRYOVER')),
          posting_state TEXT NOT NULL CHECK (posting_state IN ('DRAFT','POSTED','REJECTED')),
          available_effect_micro_units INTEGER NOT NULL,
          entry_basis TEXT NOT NULL CHECK (entry_basis IN ('ESTIMATE','ACTUAL','UNKNOWN')),
          currency TEXT NOT NULL CHECK (length(currency)=3),
          original_amount_precise TEXT,
          original_currency TEXT CHECK (original_currency IS NULL OR length(original_currency)=3),
          conversion_rule_id TEXT,
          entry_timestamp_epoch_ms INTEGER NOT NULL,
          posted_at_epoch_ms INTEGER,
          cost_calculation_line_id TEXT,
          usage_record_id TEXT,
          reconciliation_record_id TEXT,
          hold_id TEXT REFERENCES budget_hold(hold_id) ON DELETE RESTRICT,
          reversal_of_entry_id TEXT REFERENCES budget_ledger_entry(entry_id) ON DELETE RESTRICT,
          allocation_group_id TEXT,
          allocation_role TEXT CHECK (allocation_role IS NULL OR allocation_role IN ('SUPERVISORY','ALLOCATED')),
          idempotency_key TEXT NOT NULL,
          CHECK ((posting_state='POSTED' AND posted_at_epoch_ms IS NOT NULL) OR posting_state<>'POSTED'),
          CHECK (entry_type<>'REVERSAL' OR reversal_of_entry_id IS NOT NULL)
        )
    """.trimIndent()

    val CREATE_BUDGET_HOLD_SETTLEMENT = """
        CREATE TABLE IF NOT EXISTS budget_hold_settlement (
          settlement_id TEXT PRIMARY KEY NOT NULL,
          hold_id TEXT NOT NULL REFERENCES budget_hold(hold_id) ON DELETE RESTRICT,
          settlement_type TEXT NOT NULL CHECK (settlement_type IN ('SETTLE','RELEASE')),
          ledger_entry_id TEXT REFERENCES budget_ledger_entry(entry_id) ON DELETE RESTRICT,
          amount_micro_units INTEGER NOT NULL CHECK (amount_micro_units > 0),
          currency TEXT NOT NULL CHECK (length(currency)=3),
          created_at_epoch_ms INTEGER NOT NULL,
          idempotency_key TEXT NOT NULL,
          CHECK ((settlement_type='SETTLE' AND ledger_entry_id IS NOT NULL)
               OR (settlement_type='RELEASE' AND ledger_entry_id IS NULL))
        )
    """.trimIndent()

    // ---- Indices: all DECLARED in the entities (Room rule: no partial,
    //      no expression, no table-constraint UNIQUE — named indices only). ----

    val CREATE_INDICES = listOf(
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_scope_descriptor_hash ON scope_descriptor(scope_kind, canonicalization_version, canonical_hash)",
        "CREATE INDEX IF NOT EXISTS ix_budget_policy_scope ON budget_policy(scope_id, policy_status, effective_from_epoch_ms)",
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_budget_policy_family_version ON budget_policy(policy_family_id, policy_version)",
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_budget_period_policy_window ON budget_period(budget_policy_id, period_start_epoch_ms, period_end_epoch_ms)",
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_budget_hold_idempotency ON budget_hold(budget_period_id, idempotency_key)",
        "CREATE INDEX IF NOT EXISTS ix_budget_ledger_period_posted ON budget_ledger_entry(budget_period_id, posting_state, entry_timestamp_epoch_ms)",
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_budget_ledger_entry_idempotency ON budget_ledger_entry(budget_period_id, idempotency_key)",
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_budget_hold_settlement_idempotency ON budget_hold_settlement(hold_id, idempotency_key)",
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_budget_hold_settlement_entry ON budget_hold_settlement(ledger_entry_id)"
    )

    // ---- The ten protection triggers. ----
    // 8 adapted from PDM v0.1 (lines 1278-1345) with minor_units →
    // micro_units renames; 2 new per ADR-B4. Room never validates triggers
    // (Section د.2 rule 2) — each has an explicit acceptance test in
    // Migration22to23Test and the standalone SQLite verification script.

    val TRIGGERS = listOf(
        // 1 (v0.1:1278) — a period can only reference an existing policy.
        """
        CREATE TRIGGER IF NOT EXISTS trg_budget_period_currency_via_policy_insert
        BEFORE INSERT ON budget_period
        BEGIN
          SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM budget_policy WHERE budget_policy_id=NEW.budget_policy_id)
            THEN RAISE(ABORT, 'budget policy required') END;
        END
        """.trimIndent(),
        // 2 (v0.1:1284) — hold currency = policy currency via its period.
        """
        CREATE TRIGGER IF NOT EXISTS trg_budget_hold_currency_insert
        BEFORE INSERT ON budget_hold
        BEGIN
          SELECT CASE WHEN NEW.currency <> (SELECT p.currency FROM budget_period bp JOIN budget_policy p ON p.budget_policy_id=bp.budget_policy_id WHERE bp.budget_period_id=NEW.budget_period_id)
            THEN RAISE(ABORT, 'budget hold currency must match budget period policy currency') END;
        END
        """.trimIndent(),
        // 3 (v0.1:1290) — entry currency = policy currency via its period.
        """
        CREATE TRIGGER IF NOT EXISTS trg_budget_ledger_currency_insert
        BEFORE INSERT ON budget_ledger_entry
        BEGIN
          SELECT CASE WHEN NEW.currency <> (SELECT p.currency FROM budget_period bp JOIN budget_policy p ON p.budget_policy_id=bp.budget_policy_id WHERE bp.budget_period_id=NEW.budget_period_id)
            THEN RAISE(ABORT, 'budget ledger currency must match budget period policy currency') END;
        END
        """.trimIndent(),
        // 4 (v0.1:1296) — settlement currency = hold currency; a SETTLE
        // settlement must reference a POSTED debit entry of the SAME hold,
        // the SAME currency and the SAME amount.
        """
        CREATE TRIGGER IF NOT EXISTS trg_hold_settlement_currency_insert
        BEFORE INSERT ON budget_hold_settlement
        BEGIN
          SELECT CASE WHEN NEW.currency <> (SELECT currency FROM budget_hold WHERE hold_id=NEW.hold_id)
            THEN RAISE(ABORT, 'hold settlement currency must match hold currency') END;
          SELECT CASE WHEN NEW.settlement_type='SETTLE' AND NEW.ledger_entry_id IS NOT NULL
            AND (SELECT hold_id FROM budget_ledger_entry WHERE entry_id=NEW.ledger_entry_id) IS NOT NEW.hold_id
            THEN RAISE(ABORT, 'settlement ledger entry must reference same hold') END;
          SELECT CASE WHEN NEW.settlement_type='SETTLE' AND NEW.ledger_entry_id IS NOT NULL
            AND ((SELECT posting_state FROM budget_ledger_entry WHERE entry_id=NEW.ledger_entry_id) <> 'POSTED'
              OR (SELECT currency FROM budget_ledger_entry WHERE entry_id=NEW.ledger_entry_id) <> NEW.currency
              OR (SELECT available_effect_micro_units FROM budget_ledger_entry WHERE entry_id=NEW.ledger_entry_id) <> -NEW.amount_micro_units)
            THEN RAISE(ABORT, 'settlement must match a posted ledger debit of the same amount and currency') END;
        END
        """.trimIndent(),
        // 5 (v0.1:1312) — no self-containment: a policy's scope descriptor
        // must not carry that same policy as a KNOWN budget dimension.
        """
        CREATE TRIGGER IF NOT EXISTS trg_budget_policy_no_self_scope_insert
        BEFORE INSERT ON budget_policy
        BEGIN
          SELECT CASE WHEN EXISTS (
            SELECT 1 FROM scope_descriptor s
            WHERE s.scope_id=NEW.scope_id
              AND s.budget_policy_dimension_state='KNOWN'
              AND s.budget_policy_id=NEW.budget_policy_id
          ) THEN RAISE(ABORT, 'budget policy scope cannot contain the same budget policy') END;
        END
        """.trimIndent(),
        // 6 (v0.1:1322) — same guard on update.
        """
        CREATE TRIGGER IF NOT EXISTS trg_budget_policy_no_self_scope_update
        BEFORE UPDATE OF budget_policy_id, scope_id ON budget_policy
        BEGIN
          SELECT CASE WHEN EXISTS (
            SELECT 1 FROM scope_descriptor s
            WHERE s.scope_id=NEW.scope_id
              AND s.budget_policy_dimension_state='KNOWN'
              AND s.budget_policy_id=NEW.budget_policy_id
          ) THEN RAISE(ABORT, 'budget policy scope cannot contain the same budget policy') END;
        END
        """.trimIndent(),
        // 7 (v0.1:1333) — settlements + releases can never exceed the
        // original hold amount.
        """
        CREATE TRIGGER IF NOT EXISTS trg_budget_hold_settlement_amount_insert
        BEFORE INSERT ON budget_hold_settlement
        BEGIN
          SELECT CASE WHEN
            COALESCE((SELECT SUM(amount_micro_units) FROM budget_hold_settlement WHERE hold_id=NEW.hold_id),0) + NEW.amount_micro_units
            > (SELECT hold_amount_micro_units FROM budget_hold WHERE hold_id=NEW.hold_id)
          THEN RAISE(ABORT, 'hold settlements/releases cannot exceed original hold amount') END;
        END
        """.trimIndent(),
        // 8 (v0.1:1341) — period limit freezes after the first POSTED entry.
        """
        CREATE TRIGGER IF NOT EXISTS trg_budget_period_limit_immutable_after_posting
        BEFORE UPDATE OF limit_micro_units ON budget_period
        WHEN EXISTS (SELECT 1 FROM budget_ledger_entry WHERE budget_period_id=OLD.budget_period_id AND posting_state='POSTED')
        BEGIN SELECT RAISE(ABORT, 'budget period limit is immutable after first posted ledger entry'); END
        """.trimIndent(),
        // 9 (NEW, ADR-B4) — POSTED ledger entries are immutable.
        """
        CREATE TRIGGER IF NOT EXISTS trg_budget_ledger_no_update_posted
        BEFORE UPDATE ON budget_ledger_entry
        WHEN OLD.posting_state='POSTED'
        BEGIN SELECT RAISE(ABORT, 'posted ledger entries are immutable'); END
        """.trimIndent(),
        // 10 (NEW, ADR-B4) — the budget ledger is append-only, forever.
        """
        CREATE TRIGGER IF NOT EXISTS trg_budget_ledger_no_delete
        BEFORE DELETE ON budget_ledger_entry
        BEGIN SELECT RAISE(ABORT, 'budget ledger is append-only'); END
        """.trimIndent()
    )

    /** Execution order: tables (dependency-safe), indices, triggers. */
    val STATEMENTS: List<String> = listOf(
        CREATE_SCOPE_DESCRIPTOR,
        CREATE_BUDGET_POLICY,
        CREATE_BUDGET_PERIOD,
        CREATE_BUDGET_HOLD,
        CREATE_BUDGET_LEDGER_ENTRY,
        CREATE_BUDGET_HOLD_SETTLEMENT
    ) + CREATE_INDICES + TRIGGERS
}
