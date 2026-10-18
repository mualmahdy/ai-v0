package com.example.infrastructure.persistence

/**
 * ============================================================================
 * SINGLE SOURCE OF TRUTH for the MIGRATION_27_TO_28 DDL (Stage 5).
 * ============================================================================
 * The second increment of the roadmap's CONTINUOUS Phase 4 — the documented
 * next-increment list (stage4_implementation_record) plus the remaining
 * open Phase-4 roadmap item and the study's Section-9 circle-3 remainder:
 *
 *   * current_resource_state + current_resource_state_source — the DERIVED
 *     half of Group D: «إسقاط مشتق قابل لإعادة البناء» with partial UNIQUE
 *     indices that prevent more than one ACTIVE state per
 *     subject/metric/scope. Room cannot carry WHERE clauses, so those seven
 *     partial indices become the trg_current_state_unique_active_insert /
 *     _update trigger pair (the v0.2 change-7 precedent, Room compatibility
 *     — identical discipline as Stage-4's I-32);
 *   * scope_internal_dimension + scope_custom_dimension — the full G01
 *     dimension tables («أبعاد النطاق المفتوحة»): open dimension codes with
 *     a KNOWN/UNKNOWN/NOT_APPLICABLE state and a KNOWN-shape CHECK;
 *   * trg_budget_policy_version_freeze_when_governing — the G05 closure:
 *     the whole-version freeze. Row-per-version, the family+version unique
 *     index and the period-limit immutability trigger shipped with Stage 1;
 *     what was missing is the DB-level freeze of a version's SUBSTANTIVE
 *     columns once it governs periods (the pricing_entries precedent:
 *     trg_pricing_version_immutable_when_used). policy_status and
 *     updated_at_epoch_ms stay mutable — the retirement path
 *     (createPolicyVersion) updates them and must keep working.
 *
 * Provenance of every statement:
 *  - Tables + columns: PDM v0.1 Group D (derived half) + the scope-dimension
 *    tables, adapted per the APPROVED PDM v0.2 rules, identical adaptation
 *    set as Stages 2/3/4:
 *      * TEXT timestamps -> INTEGER *_epoch_ms (v0.2 change 4);
 *      * table-level UNIQUE -> NAMED unique indices ux_* (Stage-0 د.2 rule 4);
 *      * no money columns exist in this domain — current_numeric_value_precise
 *        stays TEXT precise-decimal (the stage-3 rate_precise precedent);
 *      * every referenced parent exists by v27, so every FK below is real.
 *        No existing table's SHAPE is touched; budget_policy gains a TRIGGER
 *        only (triggers are invisible to Room's TableInfo identity check —
 *        the Stage-1/2/3/4 precedent).
 *  - Open-vocabulary status columns get CHECK vocabularies here (the I-33
 *    discipline):
 *      freshness_state   FRESH/AGING/STALE/UNKNOWN
 *      state_status      OK/CONFLICT/UNKNOWN/STALE/NOT_DERIVABLE
 *      source_role       PRIMARY/SUPPORTING/CONFLICTING/EXCLUDED
 *      dimension_state   KNOWN/UNKNOWN/NOT_APPLICABLE
 *      value_type        TEXT/INTEGER/DECIMAL/BOOLEAN/JSON
 *  - The value-shape CHECK (the PDM's status-arm constraint): a state row
 *    in UNKNOWN/CONFLICT/NOT_DERIVABLE carries NO value; a row in OK/STALE
 *    carries exactly the typed value its current_value_type names.
 */
internal object Stage5MigrationSql {

    // ------------------------------------------------------------------
    // Group D, derived half: the current-state projection.
    // ------------------------------------------------------------------

    val CREATE_CURRENT_RESOURCE_STATE = """
        CREATE TABLE IF NOT EXISTS current_resource_state (
          state_id TEXT PRIMARY KEY NOT NULL,
          subject_provider_id TEXT REFERENCES provider(provider_id) ON DELETE RESTRICT,
          subject_access_context_id TEXT REFERENCES access_context(access_context_id) ON DELETE RESTRICT,
          subject_provider_scope_node_id TEXT REFERENCES provider_scope_node(scope_node_id) ON DELETE RESTRICT,
          subject_provider_model_record_id TEXT REFERENCES provider_model_record(provider_model_record_id) ON DELETE RESTRICT,
          subject_model_id TEXT REFERENCES model(model_id) ON DELETE RESTRICT,
          subject_offering_id TEXT REFERENCES model_offering(offering_id) ON DELETE RESTRICT,
          subject_request_attempt_id TEXT REFERENCES provider_request_attempt(request_attempt_id) ON DELETE RESTRICT,
          metric_id TEXT NOT NULL REFERENCES metric_definition(metric_id) ON DELETE RESTRICT,
          current_value_type TEXT NOT NULL CHECK (current_value_type IN ('NUMERIC','TEXT','BOOLEAN','STATE')),
          current_numeric_value_precise TEXT,
          current_text_value TEXT,
          current_boolean_value INTEGER CHECK (current_boolean_value IS NULL OR current_boolean_value IN (0,1)),
          current_state_value TEXT,
          unit_id TEXT REFERENCES unit_definition(unit_id) ON DELETE RESTRICT,
          scope_id TEXT REFERENCES scope_descriptor(scope_id) ON DELETE RESTRICT,
          state_as_of_epoch_ms INTEGER NOT NULL,
          freshness_state TEXT NOT NULL CHECK (freshness_state IN ('FRESH','AGING','STALE','UNKNOWN')),
          derivation_policy_id TEXT NOT NULL REFERENCES freshness_policy(policy_id) ON DELETE RESTRICT,
          state_status TEXT NOT NULL CHECK (state_status IN ('OK','CONFLICT','UNKNOWN','STALE','NOT_DERIVABLE')),
          is_active INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0,1)),
          updated_at_epoch_ms INTEGER NOT NULL,
          CHECK ((subject_provider_id IS NOT NULL) + (subject_access_context_id IS NOT NULL) + (subject_provider_scope_node_id IS NOT NULL) + (subject_provider_model_record_id IS NOT NULL) + (subject_model_id IS NOT NULL) + (subject_offering_id IS NOT NULL) + (subject_request_attempt_id IS NOT NULL) = 1),
          CHECK (
            (state_status IN ('UNKNOWN','CONFLICT','NOT_DERIVABLE') AND current_numeric_value_precise IS NULL AND current_text_value IS NULL AND current_boolean_value IS NULL AND current_state_value IS NULL) OR
            (state_status IN ('OK','STALE') AND current_value_type='NUMERIC' AND current_numeric_value_precise IS NOT NULL AND current_text_value IS NULL AND current_boolean_value IS NULL AND current_state_value IS NULL) OR
            (state_status IN ('OK','STALE') AND current_value_type='TEXT' AND current_text_value IS NOT NULL AND current_numeric_value_precise IS NULL AND current_boolean_value IS NULL AND current_state_value IS NULL) OR
            (state_status IN ('OK','STALE') AND current_value_type='BOOLEAN' AND current_boolean_value IS NOT NULL AND current_numeric_value_precise IS NULL AND current_text_value IS NULL AND current_state_value IS NULL) OR
            (state_status IN ('OK','STALE') AND current_value_type='STATE' AND current_state_value IS NOT NULL AND current_numeric_value_precise IS NULL AND current_text_value IS NULL AND current_boolean_value IS NULL)
          )
        )
    """.trimIndent()

    val CREATE_CURRENT_RESOURCE_STATE_SOURCE = """
        CREATE TABLE IF NOT EXISTS current_resource_state_source (
          state_source_id TEXT PRIMARY KEY NOT NULL,
          state_id TEXT NOT NULL REFERENCES current_resource_state(state_id) ON DELETE RESTRICT,
          observation_id TEXT NOT NULL REFERENCES observation(observation_id) ON DELETE RESTRICT,
          source_role TEXT NOT NULL CHECK (source_role IN ('PRIMARY','SUPPORTING','CONFLICTING','EXCLUDED')),
          included_at_epoch_ms INTEGER NOT NULL
        )
    """.trimIndent()

    // ------------------------------------------------------------------
    // The full G01 scope dimension tables.
    // ------------------------------------------------------------------

    val CREATE_SCOPE_INTERNAL_DIMENSION = """
        CREATE TABLE IF NOT EXISTS scope_internal_dimension (
          scope_dimension_id TEXT PRIMARY KEY NOT NULL,
          scope_id TEXT NOT NULL REFERENCES scope_descriptor(scope_id) ON DELETE RESTRICT,
          dimension_code TEXT NOT NULL,
          dimension_state TEXT NOT NULL CHECK (dimension_state IN ('KNOWN','UNKNOWN','NOT_APPLICABLE')),
          dimension_value TEXT,
          value_type TEXT CHECK (value_type IS NULL OR value_type IN ('TEXT','INTEGER','DECIMAL','BOOLEAN','JSON')),
          created_at_epoch_ms INTEGER NOT NULL,
          CHECK ((dimension_state='KNOWN' AND dimension_value IS NOT NULL AND value_type IS NOT NULL) OR (dimension_state<>'KNOWN' AND dimension_value IS NULL AND value_type IS NULL))
        )
    """.trimIndent()

    val CREATE_SCOPE_CUSTOM_DIMENSION = """
        CREATE TABLE IF NOT EXISTS scope_custom_dimension (
          scope_dimension_id TEXT PRIMARY KEY NOT NULL,
          scope_id TEXT NOT NULL REFERENCES scope_descriptor(scope_id) ON DELETE RESTRICT,
          namespace TEXT NOT NULL,
          dimension_code TEXT NOT NULL,
          dimension_state TEXT NOT NULL CHECK (dimension_state IN ('KNOWN','UNKNOWN','NOT_APPLICABLE')),
          dimension_value TEXT,
          value_type TEXT CHECK (value_type IS NULL OR value_type IN ('TEXT','INTEGER','DECIMAL','BOOLEAN','JSON')),
          created_at_epoch_ms INTEGER NOT NULL,
          CHECK ((dimension_state='KNOWN' AND dimension_value IS NOT NULL AND value_type IS NOT NULL) OR (dimension_state<>'KNOWN' AND dimension_value IS NULL AND value_type IS NULL))
        )
    """.trimIndent()

    // ------------------------------------------------------------------
    // Indices. Whatever is declared here is declared identically in
    // Stage5Entities — MigrationTestHelper compares them. The PDM's seven
    // partial UNIQUE ux_current_state_* indices are NOT here: Room cannot
    // carry WHERE clauses, so the at-most-one-active invariant lives in the
    // trg_current_state_unique_active_{insert,update} trigger pair below
    // (the v0.2 change-7 precedent). Every table-level UNIQUE became a NAMED
    // unique index ux_* (Stage-0 د.2 rule 4 — no autoindex drift between
    // the fresh-install and migration paths). These are the access paths.
    // ------------------------------------------------------------------

    private val CREATE_INDICES = listOf(
        "CREATE INDEX IF NOT EXISTS ix_current_state_metric_active ON current_resource_state(metric_id, is_active)",
        "CREATE INDEX IF NOT EXISTS ix_current_state_scope_active ON current_resource_state(scope_id, is_active)",
        "CREATE INDEX IF NOT EXISTS ix_current_state_as_of ON current_resource_state(state_as_of_epoch_ms)",
        "CREATE INDEX IF NOT EXISTS ix_current_state_source_state ON current_resource_state_source(state_id)",
        "CREATE INDEX IF NOT EXISTS ix_current_state_source_observation ON current_resource_state_source(observation_id)",
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_current_state_source_triple ON current_resource_state_source(state_id, observation_id, source_role)",
        "CREATE INDEX IF NOT EXISTS ix_scope_internal_dimension_scope ON scope_internal_dimension(scope_id)",
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_scope_internal_dimension ON scope_internal_dimension(scope_id, dimension_code)",
        "CREATE INDEX IF NOT EXISTS ix_scope_custom_dimension_scope ON scope_custom_dimension(scope_id)",
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_scope_custom_dimension ON scope_custom_dimension(scope_id, namespace, dimension_code)"
    )

    // ------------------------------------------------------------------
    // Triggers.
    //
    // (1)+(2) The Room adaptation of the PDM's seven partial UNIQUE
    // indices: at most one ACTIVE row per (subject, metric, scope). Both
    // triggers check all seven subject arms with a NULL-safe scope
    // comparison (the PDM's COALESCE(scope_id,'__NO_SCOPE__') form, so a
    // NULL scope keys with a NULL scope, never with a real one). The
    // INSERT arm guards the writer's insert; the UPDATE arm guards
    // activating an inactive row OR re-keying an already-active row onto
    // another subject's occupied slot — every arm excludes the row's own
    // state_id, so the writer's deactivate-then-insert supersede flow and
    // in-place no-op updates stay legal.
    //
    // (3) The PDM's defense-in-depth existence guard for the provider and
    // provider-scope-node subjects (the trg_observation_subject_provider
    // _insert analog — I-25: real FKs enforce this too; the trigger speaks
    // with a clearer message).
    //
    // (4) The G05 closure: whole-version freeze. A policy version that
    // governs periods (any budget_period row points at it) has its
    // SUBSTANTIVE columns frozen at the DB level; only policy_status and
    // updated_at_epoch_ms remain mutable, which is exactly the surface the
    // retirement path (createPolicyVersion) needs. Deleting a governing
    // version is already impossible: budget_period's FK is ON DELETE
    // RESTRICT.
    // ------------------------------------------------------------------

    val TRIGGERS = listOf(
        """
        CREATE TRIGGER IF NOT EXISTS trg_current_state_unique_active_insert
        BEFORE INSERT ON current_resource_state
        WHEN NEW.is_active = 1
        BEGIN
          SELECT CASE WHEN NEW.subject_provider_id IS NOT NULL AND EXISTS (
            SELECT 1 FROM current_resource_state WHERE is_active=1 AND state_id<>NEW.state_id
              AND subject_provider_id=NEW.subject_provider_id AND metric_id=NEW.metric_id
              AND COALESCE(scope_id,'__NO_SCOPE__')=COALESCE(NEW.scope_id,'__NO_SCOPE__'))
            THEN RAISE(ABORT, 'current state already has an active row for this provider subject/metric/scope') END;
          SELECT CASE WHEN NEW.subject_access_context_id IS NOT NULL AND EXISTS (
            SELECT 1 FROM current_resource_state WHERE is_active=1 AND state_id<>NEW.state_id
              AND subject_access_context_id=NEW.subject_access_context_id AND metric_id=NEW.metric_id
              AND COALESCE(scope_id,'__NO_SCOPE__')=COALESCE(NEW.scope_id,'__NO_SCOPE__'))
            THEN RAISE(ABORT, 'current state already has an active row for this context subject/metric/scope') END;
          SELECT CASE WHEN NEW.subject_provider_scope_node_id IS NOT NULL AND EXISTS (
            SELECT 1 FROM current_resource_state WHERE is_active=1 AND state_id<>NEW.state_id
              AND subject_provider_scope_node_id=NEW.subject_provider_scope_node_id AND metric_id=NEW.metric_id
              AND COALESCE(scope_id,'__NO_SCOPE__')=COALESCE(NEW.scope_id,'__NO_SCOPE__'))
            THEN RAISE(ABORT, 'current state already has an active row for this scope-node subject/metric/scope') END;
          SELECT CASE WHEN NEW.subject_provider_model_record_id IS NOT NULL AND EXISTS (
            SELECT 1 FROM current_resource_state WHERE is_active=1 AND state_id<>NEW.state_id
              AND subject_provider_model_record_id=NEW.subject_provider_model_record_id AND metric_id=NEW.metric_id
              AND COALESCE(scope_id,'__NO_SCOPE__')=COALESCE(NEW.scope_id,'__NO_SCOPE__'))
            THEN RAISE(ABORT, 'current state already has an active row for this provider-model subject/metric/scope') END;
          SELECT CASE WHEN NEW.subject_model_id IS NOT NULL AND EXISTS (
            SELECT 1 FROM current_resource_state WHERE is_active=1 AND state_id<>NEW.state_id
              AND subject_model_id=NEW.subject_model_id AND metric_id=NEW.metric_id
              AND COALESCE(scope_id,'__NO_SCOPE__')=COALESCE(NEW.scope_id,'__NO_SCOPE__'))
            THEN RAISE(ABORT, 'current state already has an active row for this model subject/metric/scope') END;
          SELECT CASE WHEN NEW.subject_offering_id IS NOT NULL AND EXISTS (
            SELECT 1 FROM current_resource_state WHERE is_active=1 AND state_id<>NEW.state_id
              AND subject_offering_id=NEW.subject_offering_id AND metric_id=NEW.metric_id
              AND COALESCE(scope_id,'__NO_SCOPE__')=COALESCE(NEW.scope_id,'__NO_SCOPE__'))
            THEN RAISE(ABORT, 'current state already has an active row for this offering subject/metric/scope') END;
          SELECT CASE WHEN NEW.subject_request_attempt_id IS NOT NULL AND EXISTS (
            SELECT 1 FROM current_resource_state WHERE is_active=1 AND state_id<>NEW.state_id
              AND subject_request_attempt_id=NEW.subject_request_attempt_id AND metric_id=NEW.metric_id
              AND COALESCE(scope_id,'__NO_SCOPE__')=COALESCE(NEW.scope_id,'__NO_SCOPE__'))
            THEN RAISE(ABORT, 'current state already has an active row for this attempt subject/metric/scope') END;
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS trg_current_state_unique_active_update
        BEFORE UPDATE ON current_resource_state
        WHEN NEW.is_active = 1
        BEGIN
          SELECT CASE WHEN NEW.subject_provider_id IS NOT NULL AND EXISTS (
            SELECT 1 FROM current_resource_state WHERE is_active=1 AND state_id<>NEW.state_id
              AND subject_provider_id=NEW.subject_provider_id AND metric_id=NEW.metric_id
              AND COALESCE(scope_id,'__NO_SCOPE__')=COALESCE(NEW.scope_id,'__NO_SCOPE__'))
            THEN RAISE(ABORT, 'current state already has an active row for this provider subject/metric/scope') END;
          SELECT CASE WHEN NEW.subject_access_context_id IS NOT NULL AND EXISTS (
            SELECT 1 FROM current_resource_state WHERE is_active=1 AND state_id<>NEW.state_id
              AND subject_access_context_id=NEW.subject_access_context_id AND metric_id=NEW.metric_id
              AND COALESCE(scope_id,'__NO_SCOPE__')=COALESCE(NEW.scope_id,'__NO_SCOPE__'))
            THEN RAISE(ABORT, 'current state already has an active row for this context subject/metric/scope') END;
          SELECT CASE WHEN NEW.subject_provider_scope_node_id IS NOT NULL AND EXISTS (
            SELECT 1 FROM current_resource_state WHERE is_active=1 AND state_id<>NEW.state_id
              AND subject_provider_scope_node_id=NEW.subject_provider_scope_node_id AND metric_id=NEW.metric_id
              AND COALESCE(scope_id,'__NO_SCOPE__')=COALESCE(NEW.scope_id,'__NO_SCOPE__'))
            THEN RAISE(ABORT, 'current state already has an active row for this scope-node subject/metric/scope') END;
          SELECT CASE WHEN NEW.subject_provider_model_record_id IS NOT NULL AND EXISTS (
            SELECT 1 FROM current_resource_state WHERE is_active=1 AND state_id<>NEW.state_id
              AND subject_provider_model_record_id=NEW.subject_provider_model_record_id AND metric_id=NEW.metric_id
              AND COALESCE(scope_id,'__NO_SCOPE__')=COALESCE(NEW.scope_id,'__NO_SCOPE__'))
            THEN RAISE(ABORT, 'current state already has an active row for this provider-model subject/metric/scope') END;
          SELECT CASE WHEN NEW.subject_model_id IS NOT NULL AND EXISTS (
            SELECT 1 FROM current_resource_state WHERE is_active=1 AND state_id<>NEW.state_id
              AND subject_model_id=NEW.subject_model_id AND metric_id=NEW.metric_id
              AND COALESCE(scope_id,'__NO_SCOPE__')=COALESCE(NEW.scope_id,'__NO_SCOPE__'))
            THEN RAISE(ABORT, 'current state already has an active row for this model subject/metric/scope') END;
          SELECT CASE WHEN NEW.subject_offering_id IS NOT NULL AND EXISTS (
            SELECT 1 FROM current_resource_state WHERE is_active=1 AND state_id<>NEW.state_id
              AND subject_offering_id=NEW.subject_offering_id AND metric_id=NEW.metric_id
              AND COALESCE(scope_id,'__NO_SCOPE__')=COALESCE(NEW.scope_id,'__NO_SCOPE__'))
            THEN RAISE(ABORT, 'current state already has an active row for this offering subject/metric/scope') END;
          SELECT CASE WHEN NEW.subject_request_attempt_id IS NOT NULL AND EXISTS (
            SELECT 1 FROM current_resource_state WHERE is_active=1 AND state_id<>NEW.state_id
              AND subject_request_attempt_id=NEW.subject_request_attempt_id AND metric_id=NEW.metric_id
              AND COALESCE(scope_id,'__NO_SCOPE__')=COALESCE(NEW.scope_id,'__NO_SCOPE__'))
            THEN RAISE(ABORT, 'current state already has an active row for this attempt subject/metric/scope') END;
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS trg_current_state_subject_provider_insert
        BEFORE INSERT ON current_resource_state
        BEGIN
          SELECT CASE WHEN NEW.subject_provider_id IS NOT NULL AND (SELECT provider_id FROM provider WHERE provider_id=NEW.subject_provider_id) IS NULL
            THEN RAISE(ABORT, 'current state provider subject must exist') END;
          SELECT CASE WHEN NEW.subject_provider_scope_node_id IS NOT NULL AND (SELECT provider_id FROM provider_scope_node WHERE scope_node_id=NEW.subject_provider_scope_node_id) IS NULL
            THEN RAISE(ABORT, 'current state provider-scope subject must exist') END;
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS trg_budget_policy_version_freeze_when_governing
        BEFORE UPDATE ON budget_policy
        WHEN EXISTS (SELECT 1 FROM budget_period WHERE budget_policy_id = OLD.budget_policy_id)
        BEGIN
          SELECT CASE WHEN
            OLD.budget_policy_id <> NEW.budget_policy_id OR
            OLD.policy_family_id <> NEW.policy_family_id OR
            OLD.budget_name <> NEW.budget_name OR
            OLD.budget_scope_type <> NEW.budget_scope_type OR
            OLD.scope_id <> NEW.scope_id OR
            OLD.currency <> NEW.currency OR
            OLD.default_limit_micro_units <> NEW.default_limit_micro_units OR
            OLD.period_type <> NEW.period_type OR
            OLD.rollover_policy <> NEW.rollover_policy OR
            OLD.overage_policy <> NEW.overage_policy OR
            OLD.allocation_policy <> NEW.allocation_policy OR
            OLD.policy_version <> NEW.policy_version OR
            OLD.effective_from_epoch_ms <> NEW.effective_from_epoch_ms OR
            OLD.created_at_epoch_ms <> NEW.created_at_epoch_ms
          THEN RAISE(ABORT, 'budget policy version governing periods is frozen; create a new version (PDM-G05)') END;
        END
        """.trimIndent()
    )

    val STATEMENTS: List<String> = listOf(
        CREATE_CURRENT_RESOURCE_STATE,
        CREATE_CURRENT_RESOURCE_STATE_SOURCE,
        CREATE_SCOPE_INTERNAL_DIMENSION,
        CREATE_SCOPE_CUSTOM_DIMENSION
    ) + CREATE_INDICES + TRIGGERS
}
