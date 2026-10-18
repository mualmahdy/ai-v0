package com.example.infrastructure.persistence

/**
 * ============================================================================
 * SINGLE SOURCE OF TRUTH for the MIGRATION_26_TO_27 DDL (Stage 4).
 * ============================================================================
 * Stage-4 evidence & sources network + the first intelligence increment
 * (roadmap Phase 4: "شبكة الأدلة والمصادر التي تربط كل حقيقة اقتصادية
 * بمصدرها" + the exit criterion "تنبؤ بنفاد الميزانية قبل نفادها بسبعة
 * أيام على الأقل"). The gap-matrix row this closes is the ONLY Phase-4 row:
 * «الأدلة والمصدر» — current state "حقل مصدر نصي في إدخال السعر", target
 * "evidence + 12 جدول ربط بأدوار وثقة وفترات وشرط دليل أساسي عند النشر"
 * (value: كاملة, risk: منخفضة).
 *
 * Provenance of every statement:
 *  - Tables + columns: PDM v0.1 Groups B (resource_constraint, metric part of
 *    the rights domain), C (provider/model/identity/acquisition + the
 *    evidence network), D (observation) — adapted per the APPROVED PDM v0.2
 *    rules, identical adaptation set as Stages 2/3:
 *      * TEXT timestamps -> INTEGER *_epoch_ms (v0.2 change 4),
 *      * table-level UNIQUE -> NAMED unique indices ux_* (Stage-0 د.2 rule 4),
 *      * partial (non-unique) indices -> regular named indices — the same
 *        discipline v0.2 change 7 applied to partial UNIQUE indices, extended
 *        here to partial non-unique ones because Room @Entity indices cannot
 *        carry WHERE clauses (room compatibility; interpretation I-32),
 *      * references to tables that exist by v26 are real FKs; references to
 *        tables that STILL don't exist would have stayed deferred-FK plain
 *        TEXT (v0.2 change 8) — none remain: this migration creates every
 *        parent the evidence network needs, so ALL FKs in this list are real.
 *        The STAGE-3 deferred FKs on existing tables (provider_billing_record
 *        .provider_id / access_context_id) stay deferred FOREVER in-place:
 *        adding FKs to a live table requires a rebuild and rebuilds of live
 *        financial tables are forbidden (documented I-34).
 *  - No money columns exist in this domain, so ADR-B1 micro-units has nothing
 *    to convert; resource_constraint.limit_value_precise stays TEXT
 *    precise-decimal (the stage-3 rate_precise precedent).
 *  - Open-vocabulary status columns the PDM DDL leaves unconstrained are
 *    given CHECK vocabularies here, each recorded as an interpretation:
 *      evidence_role            PRIMARY/SUPPORTING/CONTRADICTING   (I-33)
 *      confidence_state         HIGH/MEDIUM/LOW/UNVERIFIED         (I-33)
 *      evidence_status          ACTIVE/RETIRED                     (I-33)
 *      redaction_status         NONE/PARTIAL/FULL                  (I-33)
 *      security_classification  PUBLIC/INTERNAL/CONFIDENTIAL       (I-33)
 *      relation_status          ACTIVE/REJECTED/SUPERSEDED         (I-33)
 *      constraint_status        ACTIVE/RETIRED                     (I-33)
 *      run_status               RUNNING/SUCCEEDED/FAILED/SKIPPED   (I-33)
 *      observation validity_status  VALID/INVALIDATED              (I-33)
 *  - Triggers: the twelve PDM triggers for this domain
 *    (trg_provider_scope_no_cycle_{insert,update},
 *    trg_manifest_provider_integration_insert,
 *    trg_acquisition_provider_integration_insert,
 *    trg_observation_subject_provider_insert,
 *    trg_credential_binding_provider_match_{insert,update},
 *    trg_attempt_context_offering_provider_insert,
 *    trg_attempt_credential_context_insert,
 *    trg_no_update_evidence, trg_no_delete_evidence,
 *    trg_no_update_observation, trg_no_delete_observation)
 *    plus the Stage-4 PRIMARY-evidence-at-authorization guard family
 *    (I-35, the I-25 defense-in-depth precedent): a record may not enter
 *    its AUTHORITATIVE state without at least one PRIMARY evidence link —
 *    enforced at the DB level for the four status machines that have one
 *    (reconciliation finality FINALIZED, identity match VERIFIED, metric
 *    relation ACTIVE, resource constraint ACTIVE).
 *
 * No existing table is touched anywhere in this list.
 */
internal object Stage4MigrationSql {

    // ------------------------------------------------------------------
    // The acquisition chain (Group ج, first half): the provider registry
    // through which every piece of evidence was acquired. All narrow.
    // ------------------------------------------------------------------

    val CREATE_PROVIDER = """
        CREATE TABLE IF NOT EXISTS provider (
          provider_id TEXT PRIMARY KEY NOT NULL,
          provider_code TEXT NOT NULL,
          name TEXT NOT NULL,
          display_name TEXT,
          description TEXT,
          provider_status TEXT NOT NULL,
          created_at_epoch_ms INTEGER NOT NULL,
          updated_at_epoch_ms INTEGER NOT NULL,
          created_by TEXT,
          updated_by TEXT
        )
    """.trimIndent()

    val CREATE_PROVIDER_INTEGRATION = """
        CREATE TABLE IF NOT EXISTS provider_integration (
          integration_id TEXT PRIMARY KEY NOT NULL,
          provider_id TEXT NOT NULL REFERENCES provider(provider_id) ON DELETE RESTRICT,
          integration_code TEXT NOT NULL,
          integration_type TEXT NOT NULL,
          protocol TEXT,
          protocol_version TEXT,
          default_endpoint TEXT,
          authentication_scheme TEXT,
          integration_status TEXT NOT NULL,
          created_at_epoch_ms INTEGER NOT NULL,
          updated_at_epoch_ms INTEGER NOT NULL
        )
    """.trimIndent()

    val CREATE_PROVIDER_SCOPE_NODE = """
        CREATE TABLE IF NOT EXISTS provider_scope_node (
          scope_node_id TEXT PRIMARY KEY NOT NULL,
          provider_id TEXT NOT NULL REFERENCES provider(provider_id) ON DELETE RESTRICT,
          external_scope_id TEXT,
          scope_type TEXT NOT NULL,
          display_name TEXT,
          parent_scope_node_id TEXT,
          scope_status TEXT NOT NULL,
          first_seen_at_epoch_ms INTEGER NOT NULL,
          last_verified_at_epoch_ms INTEGER,
          metadata TEXT,
          FOREIGN KEY(provider_id, parent_scope_node_id) REFERENCES provider_scope_node(provider_id, scope_node_id) ON DELETE RESTRICT
        )
    """.trimIndent()

    val CREATE_PROVIDER_CAPABILITY_MANIFEST = """
        CREATE TABLE IF NOT EXISTS provider_capability_manifest (
          manifest_id TEXT PRIMARY KEY NOT NULL,
          provider_id TEXT NOT NULL REFERENCES provider(provider_id) ON DELETE RESTRICT,
          integration_id TEXT NOT NULL REFERENCES provider_integration(integration_id) ON DELETE RESTRICT,
          adapter_version TEXT NOT NULL,
          manifest_version TEXT NOT NULL,
          created_at_epoch_ms INTEGER NOT NULL,
          validated_at_epoch_ms INTEGER,
          manifest_status TEXT NOT NULL
        )
    """.trimIndent()

    val CREATE_CAPABILITY_STATEMENT = """
        CREATE TABLE IF NOT EXISTS capability_statement (
          capability_id TEXT PRIMARY KEY NOT NULL,
          manifest_id TEXT NOT NULL REFERENCES provider_capability_manifest(manifest_id) ON DELETE RESTRICT,
          operation_type TEXT NOT NULL,
          metric_id TEXT REFERENCES metric_definition(metric_id) ON DELETE RESTRICT,
          support_status TEXT NOT NULL CHECK (support_status IN ('SUPPORTED','PARTIAL','REQUIRES_PERMISSION','MANUAL_ONLY','UNSUPPORTED','UNKNOWN')),
          required_permission_class TEXT,
          notes TEXT
        )
    """.trimIndent()

    val CREATE_CREDENTIAL_REFERENCE = """
        CREATE TABLE IF NOT EXISTS credential_reference (
          credential_ref_id TEXT PRIMARY KEY NOT NULL,
          provider_id TEXT NOT NULL REFERENCES provider(provider_id) ON DELETE RESTRICT,
          secret_store_ref TEXT NOT NULL,
          credential_type TEXT NOT NULL,
          credential_label TEXT,
          credential_status TEXT NOT NULL,
          lifecycle_status TEXT NOT NULL,
          rotation_policy TEXT,
          created_at_epoch_ms INTEGER NOT NULL,
          expires_at_epoch_ms INTEGER,
          last_validated_at_epoch_ms INTEGER,
          last_validation_result TEXT
        )
    """.trimIndent()

    val CREATE_CREDENTIAL_BINDING = """
        CREATE TABLE IF NOT EXISTS credential_binding (
          credential_binding_id TEXT PRIMARY KEY NOT NULL,
          credential_ref_id TEXT NOT NULL REFERENCES credential_reference(credential_ref_id) ON DELETE RESTRICT,
          access_context_id TEXT NOT NULL REFERENCES access_context(access_context_id) ON DELETE RESTRICT,
          credential_purpose TEXT NOT NULL,
          permission_class TEXT,
          binding_status TEXT NOT NULL,
          valid_from_epoch_ms INTEGER NOT NULL,
          valid_until_epoch_ms INTEGER,
          created_at_epoch_ms INTEGER NOT NULL,
          CHECK (valid_until_epoch_ms IS NULL OR valid_from_epoch_ms < valid_until_epoch_ms)
        )
    """.trimIndent()

    val CREATE_ACCESS_CONTEXT = """
        CREATE TABLE IF NOT EXISTS access_context (
          access_context_id TEXT PRIMARY KEY NOT NULL,
          provider_id TEXT NOT NULL REFERENCES provider(provider_id) ON DELETE RESTRICT,
          integration_id TEXT NOT NULL,
          account_scope_node_id TEXT,
          billing_scope_node_id TEXT,
          context_label TEXT NOT NULL,
          default_region TEXT,
          context_status TEXT NOT NULL,
          created_at_epoch_ms INTEGER NOT NULL,
          updated_at_epoch_ms INTEGER NOT NULL,
          last_verified_at_epoch_ms INTEGER,
          FOREIGN KEY(provider_id, integration_id) REFERENCES provider_integration(provider_id, integration_id) ON DELETE RESTRICT,
          FOREIGN KEY(provider_id, account_scope_node_id) REFERENCES provider_scope_node(provider_id, scope_node_id) ON DELETE RESTRICT,
          FOREIGN KEY(provider_id, billing_scope_node_id) REFERENCES provider_scope_node(provider_id, scope_node_id) ON DELETE RESTRICT
        )
    """.trimIndent()

    val CREATE_ACQUISITION_RUN = """
        CREATE TABLE IF NOT EXISTS acquisition_run (
          acquisition_run_id TEXT PRIMARY KEY NOT NULL,
          provider_id TEXT NOT NULL REFERENCES provider(provider_id) ON DELETE RESTRICT,
          integration_id TEXT NOT NULL REFERENCES provider_integration(integration_id) ON DELETE RESTRICT,
          access_context_id TEXT REFERENCES access_context(access_context_id) ON DELETE RESTRICT,
          operation_type TEXT NOT NULL,
          target_scope_id TEXT REFERENCES scope_descriptor(scope_id) ON DELETE RESTRICT,
          capability_statement_id TEXT REFERENCES capability_statement(capability_id) ON DELETE RESTRICT,
          credential_binding_id TEXT REFERENCES credential_binding(credential_binding_id) ON DELETE RESTRICT,
          started_at_epoch_ms INTEGER NOT NULL,
          completed_at_epoch_ms INTEGER,
          run_status TEXT NOT NULL CHECK (run_status IN ('RUNNING','SUCCEEDED','FAILED','SKIPPED')),
          error_category TEXT,
          error_reference TEXT,
          parent_run_id TEXT REFERENCES acquisition_run(acquisition_run_id) ON DELETE RESTRICT,
          retry_of_run_id TEXT REFERENCES acquisition_run(acquisition_run_id) ON DELETE RESTRICT,
          idempotency_key TEXT,
          CHECK (completed_at_epoch_ms IS NULL OR started_at_epoch_ms <= completed_at_epoch_ms)
        )
    """.trimIndent()

    // ------------------------------------------------------------------
    // The canonical model registry + provider model records + identity
    // matches (Group ج) and the request/attempt operation trail.
    // ------------------------------------------------------------------

    val CREATE_MODEL = """
        CREATE TABLE IF NOT EXISTS model (
          model_id TEXT PRIMARY KEY NOT NULL,
          canonical_name TEXT NOT NULL,
          model_family TEXT,
          model_version TEXT,
          revision TEXT,
          modalities TEXT,
          capabilities TEXT,
          context_limit INTEGER CHECK (context_limit IS NULL OR context_limit >= 0),
          output_limit INTEGER CHECK (output_limit IS NULL OR output_limit >= 0),
          lifecycle_status TEXT NOT NULL,
          metadata TEXT,
          created_at_epoch_ms INTEGER NOT NULL,
          updated_at_epoch_ms INTEGER NOT NULL
        )
    """.trimIndent()

    val CREATE_PROVIDER_MODEL_RECORD = """
        CREATE TABLE IF NOT EXISTS provider_model_record (
          provider_model_record_id TEXT PRIMARY KEY NOT NULL,
          provider_id TEXT NOT NULL REFERENCES provider(provider_id) ON DELETE RESTRICT,
          integration_id TEXT NOT NULL,
          provider_native_model_id TEXT NOT NULL,
          display_name TEXT,
          provider_version TEXT,
          discovery_source TEXT NOT NULL,
          discovery_status TEXT NOT NULL,
          raw_metadata TEXT,
          first_seen_at_epoch_ms INTEGER NOT NULL,
          last_seen_at_epoch_ms INTEGER,
          FOREIGN KEY(provider_id, integration_id) REFERENCES provider_integration(provider_id, integration_id) ON DELETE RESTRICT
        )
    """.trimIndent()

    val CREATE_MODEL_IDENTITY_MATCH = """
        CREATE TABLE IF NOT EXISTS model_identity_match (
          match_id TEXT PRIMARY KEY NOT NULL,
          provider_model_record_id TEXT NOT NULL REFERENCES provider_model_record(provider_model_record_id) ON DELETE RESTRICT,
          canonical_model_id TEXT NOT NULL REFERENCES model(model_id) ON DELETE RESTRICT,
          match_status TEXT NOT NULL CHECK (match_status IN ('PROPOSED','VERIFIED','REJECTED','SUPERSEDED')),
          confidence_score TEXT,
          match_reason TEXT,
          valid_from_epoch_ms INTEGER NOT NULL,
          valid_until_epoch_ms INTEGER,
          matched_at_epoch_ms INTEGER NOT NULL,
          superseded_by_match_id TEXT REFERENCES model_identity_match(match_id) ON DELETE RESTRICT,
          CHECK (valid_until_epoch_ms IS NULL OR valid_from_epoch_ms < valid_until_epoch_ms)
        )
    """.trimIndent()

    val CREATE_MODEL_OFFERING = """
        CREATE TABLE IF NOT EXISTS model_offering (
          offering_id TEXT PRIMARY KEY NOT NULL,
          provider_model_record_id TEXT NOT NULL REFERENCES provider_model_record(provider_model_record_id) ON DELETE RESTRICT,
          offering_code TEXT NOT NULL,
          endpoint TEXT,
          region TEXT,
          service_mode TEXT,
          variant TEXT,
          availability_status TEXT NOT NULL,
          availability_source TEXT NOT NULL CHECK (availability_source IN ('PROVIDER_DECLARED','MANUAL_CONFIGURED','OBSERVATION_DERIVED','UNKNOWN')),
          created_at_epoch_ms INTEGER NOT NULL,
          updated_at_epoch_ms INTEGER NOT NULL
        )
    """.trimIndent()

    val CREATE_REQUEST_OPERATION = """
        CREATE TABLE IF NOT EXISTS request_operation (
          request_operation_id TEXT PRIMARY KEY NOT NULL,
          application_ref TEXT,
          workspace_ref TEXT,
          project_ref TEXT,
          session_ref TEXT,
          operation_type TEXT NOT NULL,
          operation_status TEXT NOT NULL,
          created_at_epoch_ms INTEGER NOT NULL,
          client_operation_id TEXT
        )
    """.trimIndent()

    val CREATE_PROVIDER_REQUEST_ATTEMPT = """
        CREATE TABLE IF NOT EXISTS provider_request_attempt (
          request_attempt_id TEXT PRIMARY KEY NOT NULL,
          request_operation_id TEXT NOT NULL REFERENCES request_operation(request_operation_id) ON DELETE RESTRICT,
          provider_id TEXT NOT NULL REFERENCES provider(provider_id) ON DELETE RESTRICT,
          integration_id TEXT NOT NULL,
          offering_id TEXT NOT NULL REFERENCES model_offering(offering_id) ON DELETE RESTRICT,
          access_context_id TEXT NOT NULL REFERENCES access_context(access_context_id) ON DELETE RESTRICT,
          credential_binding_id TEXT REFERENCES credential_binding(credential_binding_id) ON DELETE RESTRICT,
          provider_request_id TEXT,
          client_attempt_id TEXT,
          attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
          started_at_epoch_ms INTEGER NOT NULL,
          completed_at_epoch_ms INTEGER,
          request_status TEXT NOT NULL CHECK (request_status IN ('CREATED','SENT','SUCCEEDED','FAILED','TIMED_OUT','CANCELLED')),
          http_status INTEGER,
          provider_error_code TEXT,
          retry_of_attempt_id TEXT REFERENCES provider_request_attempt(request_attempt_id) ON DELETE RESTRICT,
          idempotency_key TEXT,
          FOREIGN KEY(provider_id, integration_id) REFERENCES provider_integration(provider_id, integration_id) ON DELETE RESTRICT,
          CHECK (completed_at_epoch_ms IS NULL OR started_at_epoch_ms <= completed_at_epoch_ms)
        )
    """.trimIndent()

    val CREATE_FRESHNESS_POLICY = """
        CREATE TABLE IF NOT EXISTS freshness_policy (
          policy_id TEXT PRIMARY KEY NOT NULL,
          metric_id TEXT NOT NULL REFERENCES metric_definition(metric_id) ON DELETE RESTRICT,
          scope_id TEXT REFERENCES scope_descriptor(scope_id) ON DELETE RESTRICT,
          expected_update_interval_seconds INTEGER NOT NULL CHECK (expected_update_interval_seconds >= 0),
          staleness_threshold_seconds INTEGER NOT NULL CHECK (staleness_threshold_seconds >= 0),
          source_delay_seconds INTEGER NOT NULL DEFAULT 0 CHECK (source_delay_seconds >= 0),
          unknown_when_expired INTEGER NOT NULL DEFAULT 0 CHECK (unknown_when_expired IN (0,1)),
          effective_from_epoch_ms INTEGER NOT NULL,
          effective_until_epoch_ms INTEGER,
          created_at_epoch_ms INTEGER NOT NULL,
          CHECK (effective_until_epoch_ms IS NULL OR effective_from_epoch_ms < effective_until_epoch_ms)
        )
    """.trimIndent()

    // ------------------------------------------------------------------
    // The evidence network core. evidence is IMMUTABLE after insert
    // (PDM trg_no_update_evidence / trg_no_delete_evidence); its payload
    // storage mode is one of three, each with its own consistency CHECK.
    // ------------------------------------------------------------------

    val CREATE_EVIDENCE = """
        CREATE TABLE IF NOT EXISTS evidence (
          evidence_id TEXT PRIMARY KEY NOT NULL,
          acquisition_run_id TEXT REFERENCES acquisition_run(acquisition_run_id) ON DELETE RESTRICT,
          evidence_type TEXT NOT NULL,
          source_location TEXT,
          source_field TEXT,
          source_reference TEXT,
          retrieved_at_epoch_ms INTEGER,
          provider_request_id TEXT,
          payload_digest TEXT,
          hash_algorithm TEXT,
          payload_storage_mode TEXT NOT NULL CHECK (payload_storage_mode IN ('REDACTED_INLINE','EXTERNAL_POINTER','DIGEST_ONLY')),
          verification_available INTEGER NOT NULL DEFAULT 0 CHECK (verification_available IN (0,1)),
          redaction_status TEXT NOT NULL CHECK (redaction_status IN ('NONE','PARTIAL','FULL')),
          retention_until_epoch_ms INTEGER,
          evidence_status TEXT NOT NULL CHECK (evidence_status IN ('ACTIVE','RETIRED')),
          security_classification TEXT NOT NULL CHECK (security_classification IN ('PUBLIC','INTERNAL','CONFIDENTIAL')),
          sanitized_payload TEXT,
          recorded_at_epoch_ms INTEGER NOT NULL,
          CHECK (payload_storage_mode <> 'DIGEST_ONLY' OR payload_digest IS NOT NULL),
          CHECK (payload_storage_mode <> 'EXTERNAL_POINTER' OR source_reference IS NOT NULL OR source_location IS NOT NULL),
          CHECK (payload_storage_mode <> 'REDACTED_INLINE' OR sanitized_payload IS NOT NULL)
        )
    """.trimIndent()

    // ------------------------------------------------------------------
    // The twelve typed evidence-link tables (PDM §3.4). One shared
    // physical contract: composite PK (target, evidence, role); the role
    // vocabulary and confidence states are CHECKed (I-33); the validity
    // window is epoch-ms and must be non-empty when both ends are present.
    // ------------------------------------------------------------------

    private val LINK_CONTRACT_COLUMNS = """
          evidence_role TEXT NOT NULL CHECK (evidence_role IN ('PRIMARY','SUPPORTING','CONTRADICTING')),
          confidence_state TEXT NOT NULL CHECK (confidence_state IN ('HIGH','MEDIUM','LOW','UNVERIFIED')),
          is_primary INTEGER NOT NULL DEFAULT 0 CHECK (is_primary IN (0,1)),
          valid_from_epoch_ms INTEGER,
          valid_until_epoch_ms INTEGER,
          created_at_epoch_ms INTEGER NOT NULL,
          CHECK (valid_until_epoch_ms IS NULL OR valid_from_epoch_ms IS NULL OR valid_from_epoch_ms < valid_until_epoch_ms)
    """.trimIndent()

    val CREATE_CURRENCY_CONVERSION_RULE_EVIDENCE = """
        CREATE TABLE IF NOT EXISTS currency_conversion_rule_evidence (
          currency_conversion_rule_id TEXT NOT NULL REFERENCES currency_conversion_rule(currency_conversion_rule_id) ON DELETE RESTRICT,
          evidence_id TEXT NOT NULL REFERENCES evidence(evidence_id) ON DELETE RESTRICT,
$LINK_CONTRACT_COLUMNS,
          PRIMARY KEY(currency_conversion_rule_id, evidence_id, evidence_role)
        )
    """.trimIndent()

    val CREATE_ENTITLEMENT_EVIDENCE = """
        CREATE TABLE IF NOT EXISTS entitlement_evidence (
          entitlement_id TEXT NOT NULL REFERENCES entitlement_record(entitlement_id) ON DELETE RESTRICT,
          evidence_id TEXT NOT NULL REFERENCES evidence(evidence_id) ON DELETE RESTRICT,
$LINK_CONTRACT_COLUMNS,
          PRIMARY KEY(entitlement_id, evidence_id, evidence_role)
        )
    """.trimIndent()

    val CREATE_METRIC_RELATIONSHIP_EVIDENCE = """
        CREATE TABLE IF NOT EXISTS metric_relationship_evidence (
          relation_id TEXT NOT NULL REFERENCES metric_relationship(relation_id) ON DELETE RESTRICT,
          evidence_id TEXT NOT NULL REFERENCES evidence(evidence_id) ON DELETE RESTRICT,
$LINK_CONTRACT_COLUMNS,
          PRIMARY KEY(relation_id, evidence_id, evidence_role)
        )
    """.trimIndent()

    val CREATE_MODEL_IDENTITY_MATCH_EVIDENCE = """
        CREATE TABLE IF NOT EXISTS model_identity_match_evidence (
          match_id TEXT NOT NULL REFERENCES model_identity_match(match_id) ON DELETE RESTRICT,
          evidence_id TEXT NOT NULL REFERENCES evidence(evidence_id) ON DELETE RESTRICT,
$LINK_CONTRACT_COLUMNS,
          PRIMARY KEY(match_id, evidence_id, evidence_role)
        )
    """.trimIndent()

    val CREATE_OBSERVATION_EVIDENCE = """
        CREATE TABLE IF NOT EXISTS observation_evidence (
          observation_id TEXT NOT NULL REFERENCES observation(observation_id) ON DELETE RESTRICT,
          evidence_id TEXT NOT NULL REFERENCES evidence(evidence_id) ON DELETE RESTRICT,
$LINK_CONTRACT_COLUMNS,
          PRIMARY KEY(observation_id, evidence_id, evidence_role)
        )
    """.trimIndent()

    val CREATE_PRICING_COMPONENT_EVIDENCE = """
        CREATE TABLE IF NOT EXISTS pricing_component_evidence (
          pricing_component_id TEXT NOT NULL REFERENCES pricing_component(pricing_component_id) ON DELETE RESTRICT,
          evidence_id TEXT NOT NULL REFERENCES evidence(evidence_id) ON DELETE RESTRICT,
$LINK_CONTRACT_COLUMNS,
          PRIMARY KEY(pricing_component_id, evidence_id, evidence_role)
        )
    """.trimIndent()

    val CREATE_PRICING_PROFILE_EVIDENCE = """
        CREATE TABLE IF NOT EXISTS pricing_profile_evidence (
          pricing_profile_id TEXT NOT NULL REFERENCES pricing_profile(pricing_profile_id) ON DELETE RESTRICT,
          evidence_id TEXT NOT NULL REFERENCES evidence(evidence_id) ON DELETE RESTRICT,
$LINK_CONTRACT_COLUMNS,
          PRIMARY KEY(pricing_profile_id, evidence_id, evidence_role)
        )
    """.trimIndent()

    val CREATE_PRICING_PROFILE_VERSION_EVIDENCE = """
        CREATE TABLE IF NOT EXISTS pricing_profile_version_evidence (
          pricing_profile_version_id TEXT NOT NULL REFERENCES pricing_profile_version(pricing_profile_version_id) ON DELETE RESTRICT,
          evidence_id TEXT NOT NULL REFERENCES evidence(evidence_id) ON DELETE RESTRICT,
$LINK_CONTRACT_COLUMNS,
          PRIMARY KEY(pricing_profile_version_id, evidence_id, evidence_role)
        )
    """.trimIndent()

    val CREATE_PROVIDER_BILLING_RECORD_EVIDENCE = """
        CREATE TABLE IF NOT EXISTS provider_billing_record_evidence (
          provider_billing_record_id TEXT NOT NULL REFERENCES provider_billing_record(provider_billing_record_id) ON DELETE RESTRICT,
          evidence_id TEXT NOT NULL REFERENCES evidence(evidence_id) ON DELETE RESTRICT,
$LINK_CONTRACT_COLUMNS,
          PRIMARY KEY(provider_billing_record_id, evidence_id, evidence_role)
        )
    """.trimIndent()

    val CREATE_RECONCILIATION_EVIDENCE = """
        CREATE TABLE IF NOT EXISTS reconciliation_evidence (
          reconciliation_id TEXT NOT NULL REFERENCES reconciliation_record(reconciliation_id) ON DELETE RESTRICT,
          evidence_id TEXT NOT NULL REFERENCES evidence(evidence_id) ON DELETE RESTRICT,
$LINK_CONTRACT_COLUMNS,
          PRIMARY KEY(reconciliation_id, evidence_id, evidence_role)
        )
    """.trimIndent()

    val CREATE_RESOURCE_CONSTRAINT_EVIDENCE = """
        CREATE TABLE IF NOT EXISTS resource_constraint_evidence (
          constraint_id TEXT NOT NULL REFERENCES resource_constraint(constraint_id) ON DELETE RESTRICT,
          evidence_id TEXT NOT NULL REFERENCES evidence(evidence_id) ON DELETE RESTRICT,
$LINK_CONTRACT_COLUMNS,
          PRIMARY KEY(constraint_id, evidence_id, evidence_role)
        )
    """.trimIndent()

    val CREATE_USAGE_RECORD_EVIDENCE = """
        CREATE TABLE IF NOT EXISTS usage_record_evidence (
          usage_record_id TEXT NOT NULL REFERENCES usage_record(usage_record_id) ON DELETE RESTRICT,
          evidence_id TEXT NOT NULL REFERENCES evidence(evidence_id) ON DELETE RESTRICT,
$LINK_CONTRACT_COLUMNS,
          PRIMARY KEY(usage_record_id, evidence_id, evidence_role)
        )
    """.trimIndent()

    // ------------------------------------------------------------------
    // The link PARENTS that did not exist by v26. metric_relationship is
    // the metrics/units semantics layer; resource_constraint is the
    // declared/configured/applied rate-limit registry (Group ب remainder).
    // ------------------------------------------------------------------

    val CREATE_METRIC_RELATIONSHIP = """
        CREATE TABLE IF NOT EXISTS metric_relationship (
          relation_id TEXT PRIMARY KEY NOT NULL,
          source_metric_id TEXT NOT NULL REFERENCES metric_definition(metric_id) ON DELETE RESTRICT,
          target_metric_id TEXT NOT NULL REFERENCES metric_definition(metric_id) ON DELETE RESTRICT,
          relation_type TEXT NOT NULL CHECK (relation_type IN ('SUBSET_OF','ADDITIVE_WITH','NON_ADDITIVE_WITH','DERIVED_FROM','OVERLAPPING')),
          scope_id TEXT REFERENCES scope_descriptor(scope_id) ON DELETE RESTRICT,
          effective_from_epoch_ms INTEGER NOT NULL,
          effective_until_epoch_ms INTEGER,
          relation_status TEXT NOT NULL CHECK (relation_status IN ('ACTIVE','REJECTED','SUPERSEDED')),
          CHECK (source_metric_id <> target_metric_id),
          CHECK (effective_until_epoch_ms IS NULL OR effective_from_epoch_ms < effective_until_epoch_ms)
        )
    """.trimIndent()

    val CREATE_RESOURCE_CONSTRAINT = """
        CREATE TABLE IF NOT EXISTS resource_constraint (
          constraint_id TEXT PRIMARY KEY NOT NULL,
          provider_id TEXT NOT NULL REFERENCES provider(provider_id) ON DELETE RESTRICT,
          applicability_scope_id TEXT NOT NULL REFERENCES scope_descriptor(scope_id) ON DELETE RESTRICT,
          constraint_type TEXT NOT NULL,
          constraint_source_type TEXT NOT NULL CHECK (constraint_source_type IN ('DECLARED','CONFIGURED','APPLIED')),
          metric_id TEXT REFERENCES metric_definition(metric_id) ON DELETE RESTRICT,
          limit_value_precise TEXT,
          unit_id TEXT REFERENCES unit_definition(unit_id) ON DELETE RESTRICT,
          window_type TEXT NOT NULL,
          window_duration_seconds INTEGER,
          limiting_algorithm TEXT,
          refill_policy TEXT,
          effective_from_epoch_ms INTEGER NOT NULL,
          effective_until_epoch_ms INTEGER,
          constraint_status TEXT NOT NULL CHECK (constraint_status IN ('ACTIVE','RETIRED')),
          CHECK (effective_until_epoch_ms IS NULL OR effective_from_epoch_ms < effective_until_epoch_ms)
        )
    """.trimIndent()

    // ------------------------------------------------------------------
    // observation (Group د): the immutable historical fact table. The 7
    // subject FK types are adopted as-is (G02); exactly ONE subject per
    // row is CHECKed; KNOWN rows carry exactly one typed value; the value/
    // knowledge consistency CHECK is verbatim PDM.
    // ------------------------------------------------------------------

    val CREATE_OBSERVATION = """
        CREATE TABLE IF NOT EXISTS observation (
          observation_id TEXT PRIMARY KEY NOT NULL,
          provider_id TEXT NOT NULL REFERENCES provider(provider_id) ON DELETE RESTRICT,
          subject_provider_id TEXT REFERENCES provider(provider_id) ON DELETE RESTRICT,
          subject_access_context_id TEXT REFERENCES access_context(access_context_id) ON DELETE RESTRICT,
          subject_provider_scope_node_id TEXT REFERENCES provider_scope_node(scope_node_id) ON DELETE RESTRICT,
          subject_provider_model_record_id TEXT REFERENCES provider_model_record(provider_model_record_id) ON DELETE RESTRICT,
          subject_model_id TEXT REFERENCES model(model_id) ON DELETE RESTRICT,
          subject_offering_id TEXT REFERENCES model_offering(offering_id) ON DELETE RESTRICT,
          subject_request_attempt_id TEXT REFERENCES provider_request_attempt(request_attempt_id) ON DELETE RESTRICT,
          metric_id TEXT NOT NULL REFERENCES metric_definition(metric_id) ON DELETE RESTRICT,
          value_type TEXT NOT NULL CHECK (value_type IN ('NUMERIC','TEXT','BOOLEAN','STATE')),
          numeric_value_precise TEXT,
          text_value TEXT,
          boolean_value INTEGER CHECK (boolean_value IS NULL OR boolean_value IN (0,1)),
          state_value TEXT,
          unit_id TEXT REFERENCES unit_definition(unit_id) ON DELETE RESTRICT,
          scope_id TEXT REFERENCES scope_descriptor(scope_id) ON DELETE RESTRICT,
          observed_at_epoch_ms INTEGER NOT NULL,
          valid_from_epoch_ms INTEGER,
          valid_until_epoch_ms INTEGER,
          validity_status TEXT NOT NULL CHECK (validity_status IN ('VALID','INVALIDATED')),
          reset_at_epoch_ms INTEGER,
          reset_after_epoch_ms INTEGER,
          source_authority TEXT NOT NULL,
          knowledge_status TEXT NOT NULL CHECK (knowledge_status IN ('KNOWN','UNKNOWN','UNSUPPORTED','UNAVAILABLE','NOT_APPLICABLE')),
          unknown_reason_code TEXT,
          freshness_policy_id TEXT REFERENCES freshness_policy(policy_id) ON DELETE RESTRICT,
          acquisition_run_id TEXT REFERENCES acquisition_run(acquisition_run_id) ON DELETE RESTRICT,
          derivation_rule_id TEXT,
          confidence_score TEXT,
          recorded_at_epoch_ms INTEGER NOT NULL,
          correction_of_observation_id TEXT REFERENCES observation(observation_id) ON DELETE RESTRICT,
          CHECK (valid_until_epoch_ms IS NULL OR valid_from_epoch_ms IS NULL OR valid_from_epoch_ms < valid_until_epoch_ms),
          CHECK ((subject_provider_id IS NOT NULL) + (subject_access_context_id IS NOT NULL) + (subject_provider_scope_node_id IS NOT NULL) + (subject_provider_model_record_id IS NOT NULL) + (subject_model_id IS NOT NULL) + (subject_offering_id IS NOT NULL) + (subject_request_attempt_id IS NOT NULL) = 1),
          CHECK (
            (knowledge_status='KNOWN' AND ((value_type='NUMERIC' AND numeric_value_precise IS NOT NULL AND text_value IS NULL AND boolean_value IS NULL AND state_value IS NULL) OR
             (value_type='TEXT' AND text_value IS NOT NULL AND numeric_value_precise IS NULL AND boolean_value IS NULL AND state_value IS NULL) OR
             (value_type='BOOLEAN' AND boolean_value IS NOT NULL AND numeric_value_precise IS NULL AND text_value IS NULL AND state_value IS NULL) OR
             (value_type='STATE' AND state_value IS NOT NULL AND numeric_value_precise IS NULL AND text_value IS NULL AND boolean_value IS NULL))) OR
            (knowledge_status<>'KNOWN' AND numeric_value_precise IS NULL AND text_value IS NULL AND boolean_value IS NULL AND state_value IS NULL)
          )
        )
    """.trimIndent()

    // ------------------------------------------------------------------
    // Indices. Every PDM inline UNIQUE became a NAMED unique index
    // (Stage-0 د.2 rule 4 — ux_*); the three PDM PARTIAL non-unique
    // indices became regular named indices (I-32, Room cannot declare
    // WHERE clauses); FK-child indices were added where a real access
    // path needs them (ix_*). Whatever is declared here is declared
    // identically in Stage4Entities — MigrationTestHelper compares them.
    // ------------------------------------------------------------------

    private val CREATE_INDICES = listOf(
        // provider registry
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_provider_code ON provider(provider_code)",
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_provider_integration_code ON provider_integration(provider_id, integration_code)",
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_provider_integration_identity ON provider_integration(provider_id, integration_id)",
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_provider_scope_node_identity ON provider_scope_node(provider_id, scope_node_id)",
        "CREATE INDEX IF NOT EXISTS ix_provider_scope_node_parent ON provider_scope_node(provider_id, parent_scope_node_id)",
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_capability_statement_key ON capability_statement(manifest_id, operation_type, metric_id)",
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_credential_reference_store ON credential_reference(provider_id, secret_store_ref)",
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_credential_reference_identity ON credential_reference(provider_id, credential_ref_id)",
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_credential_binding_context ON credential_binding(credential_binding_id, access_context_id)",
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_access_context_label ON access_context(provider_id, integration_id, context_label)",
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_access_context_identity ON access_context(provider_id, access_context_id)",
        "CREATE INDEX IF NOT EXISTS ix_access_context_integration ON access_context(provider_id, integration_id)",
        "CREATE INDEX IF NOT EXISTS ix_access_context_account_scope ON access_context(provider_id, account_scope_node_id)",
        "CREATE INDEX IF NOT EXISTS ix_access_context_billing_scope ON access_context(provider_id, billing_scope_node_id)",
        "CREATE INDEX IF NOT EXISTS ix_acquisition_run_target_scope ON acquisition_run(target_scope_id)",
        "CREATE INDEX IF NOT EXISTS ix_acquisition_run_capability ON acquisition_run(capability_statement_id)",
        // canonical model registry
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_provider_model_record_native ON provider_model_record(provider_id, integration_id, provider_native_model_id)",
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_provider_model_record_identity ON provider_model_record(provider_model_record_id, provider_id, integration_id)",
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_model_identity_match_identity ON model_identity_match(match_id, provider_model_record_id)",
        "CREATE INDEX IF NOT EXISTS ix_model_identity_match_canonical ON model_identity_match(canonical_model_id)",
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_provider_request_attempt_operation ON provider_request_attempt(request_operation_id, attempt_number)",
        "CREATE UNIQUE INDEX IF NOT EXISTS ux_provider_request_attempt_identity ON provider_request_attempt(request_attempt_id, provider_id, integration_id)",
        "CREATE INDEX IF NOT EXISTS ix_provider_request_attempt_offering ON provider_request_attempt(offering_id)",
        // semantics + constraints
        "CREATE INDEX IF NOT EXISTS ix_metric_relationship_pair ON metric_relationship(source_metric_id, target_metric_id, relation_type, effective_from_epoch_ms)",
        "CREATE INDEX IF NOT EXISTS ix_metric_relationship_scope ON metric_relationship(scope_id)",
        "CREATE INDEX IF NOT EXISTS ix_resource_constraint_scope ON resource_constraint(applicability_scope_id)",
        // evidence network
        "CREATE INDEX IF NOT EXISTS ix_evidence_digest ON evidence(payload_digest)",
        "CREATE INDEX IF NOT EXISTS ix_evidence_acquisition ON evidence(acquisition_run_id)",
        "CREATE INDEX IF NOT EXISTS ix_observation_metric_time ON observation(metric_id, observed_at_epoch_ms)",
        "CREATE INDEX IF NOT EXISTS ix_observation_context_metric_time ON observation(subject_access_context_id, metric_id, observed_at_epoch_ms)",
        "CREATE INDEX IF NOT EXISTS ix_observation_offering_metric_time ON observation(subject_offering_id, metric_id, observed_at_epoch_ms)"
    )

    // ------------------------------------------------------------------
    // Triggers. The twelve PDM triggers for this domain (verbatim PDM
    // semantics; recursive-CTE cycle guard, provider/integration match
    // guards, credential/context coherence, evidence + observation
    // immutability) plus the Stage-4 PRIMARY-evidence-at-authorization
    // guard family (I-35). SQLite has no combined UPDATE OR DELETE event
    // (Stage-2 verified) — separate triggers per event.
    // ------------------------------------------------------------------

    val TRIGGERS = listOf(
        // ---- PDM: provider scope hierarchy cannot contain cycles (insert) ----
        """
        CREATE TRIGGER IF NOT EXISTS trg_provider_scope_no_cycle_insert
        BEFORE INSERT ON provider_scope_node
        WHEN NEW.parent_scope_node_id IS NOT NULL
        BEGIN
          SELECT CASE WHEN NEW.parent_scope_node_id=NEW.scope_node_id OR EXISTS (
            WITH RECURSIVE ancestors(id) AS (
              SELECT parent_scope_node_id FROM provider_scope_node WHERE scope_node_id=NEW.parent_scope_node_id
              UNION ALL
              SELECT n.parent_scope_node_id FROM provider_scope_node n JOIN ancestors a ON n.scope_node_id=a.id WHERE n.parent_scope_node_id IS NOT NULL
            ) SELECT 1 FROM ancestors WHERE id=NEW.scope_node_id
          ) THEN RAISE(ABORT, 'provider scope hierarchy cannot contain cycles') END;
        END
        """.trimIndent() + ";",

        // ---- PDM: provider scope hierarchy cannot contain cycles (update) ----
        """
        CREATE TRIGGER IF NOT EXISTS trg_provider_scope_no_cycle_update
        BEFORE UPDATE OF parent_scope_node_id ON provider_scope_node
        WHEN NEW.parent_scope_node_id IS NOT NULL
        BEGIN
          SELECT CASE WHEN NEW.parent_scope_node_id=NEW.scope_node_id OR EXISTS (
            WITH RECURSIVE ancestors(id) AS (
              SELECT parent_scope_node_id FROM provider_scope_node WHERE scope_node_id=NEW.parent_scope_node_id
              UNION ALL
              SELECT n.parent_scope_node_id FROM provider_scope_node n JOIN ancestors a ON n.scope_node_id=a.id WHERE n.parent_scope_node_id IS NOT NULL
            ) SELECT 1 FROM ancestors WHERE id=NEW.scope_node_id
          ) THEN RAISE(ABORT, 'provider scope hierarchy cannot contain cycles') END;
        END
        """.trimIndent() + ";",

        // ---- PDM: manifest's provider must match its integration's provider ----
        """
        CREATE TRIGGER IF NOT EXISTS trg_manifest_provider_integration_insert
        BEFORE INSERT ON provider_capability_manifest
        BEGIN
          SELECT CASE WHEN NEW.provider_id <> (SELECT provider_id FROM provider_integration WHERE integration_id=NEW.integration_id)
            THEN RAISE(ABORT, 'capability manifest provider must match integration provider') END;
        END
        """.trimIndent() + ";",

        // ---- PDM: acquisition run's provider must match its integration's ----
        """
        CREATE TRIGGER IF NOT EXISTS trg_acquisition_provider_integration_insert
        BEFORE INSERT ON acquisition_run
        BEGIN
          SELECT CASE WHEN NEW.provider_id <> (SELECT provider_id FROM provider_integration WHERE integration_id=NEW.integration_id)
            THEN RAISE(ABORT, 'acquisition provider must match integration provider') END;
        END
        """.trimIndent() + ";",

        // ---- PDM: observation's subject provider must be the source provider ----
        """
        CREATE TRIGGER IF NOT EXISTS trg_observation_subject_provider_insert
        BEFORE INSERT ON observation
        BEGIN
          SELECT CASE WHEN NEW.subject_provider_id IS NOT NULL AND NEW.provider_id <> NEW.subject_provider_id
            THEN RAISE(ABORT, 'observation source provider and subject provider must match') END;
        END
        """.trimIndent() + ";",

        // ---- PDM: credential binding's providers must match (insert) ----
        """
        CREATE TRIGGER IF NOT EXISTS trg_credential_binding_provider_match_insert
        BEFORE INSERT ON credential_binding
        BEGIN
          SELECT CASE WHEN
            (SELECT provider_id FROM credential_reference WHERE credential_ref_id=NEW.credential_ref_id)
            <> (SELECT provider_id FROM access_context WHERE access_context_id=NEW.access_context_id)
          THEN RAISE(ABORT, 'credential provider must match access-context provider') END;
        END
        """.trimIndent() + ";",

        // ---- PDM: credential binding's providers must match (update) ----
        """
        CREATE TRIGGER IF NOT EXISTS trg_credential_binding_provider_match_update
        BEFORE UPDATE OF credential_ref_id, access_context_id ON credential_binding
        BEGIN
          SELECT CASE WHEN
            (SELECT provider_id FROM credential_reference WHERE credential_ref_id=NEW.credential_ref_id)
            <> (SELECT provider_id FROM access_context WHERE access_context_id=NEW.access_context_id)
          THEN RAISE(ABORT, 'credential provider must match access-context provider') END;
        END
        """.trimIndent() + ";",

        // ---- PDM: request attempt's provider must match its access context ----
        """
        CREATE TRIGGER IF NOT EXISTS trg_attempt_context_offering_provider_insert
        BEFORE INSERT ON provider_request_attempt
        BEGIN
          SELECT CASE WHEN NEW.provider_id <> (SELECT provider_id FROM access_context WHERE access_context_id=NEW.access_context_id)
            THEN RAISE(ABORT, 'request provider must match access context') END;
        END
        """.trimIndent() + ";",

        // ---- PDM: attempt's credential binding must belong to its access context ----
        """
        CREATE TRIGGER IF NOT EXISTS trg_attempt_credential_context_insert
        BEFORE INSERT ON provider_request_attempt
        WHEN NEW.credential_binding_id IS NOT NULL
        BEGIN
          SELECT CASE WHEN NEW.access_context_id <> (SELECT access_context_id FROM credential_binding WHERE credential_binding_id=NEW.credential_binding_id)
            THEN RAISE(ABORT, 'credential binding must belong to request access context') END;
        END
        """.trimIndent() + ";",

        // ---- PDM: evidence is immutable (append-only, ADR-B2 family) ----
        """
        CREATE TRIGGER IF NOT EXISTS trg_no_update_evidence
        BEFORE UPDATE ON evidence
        BEGIN SELECT RAISE(ABORT, 'evidence is immutable'); END;
        """.trimIndent() + ";",

        """
        CREATE TRIGGER IF NOT EXISTS trg_no_delete_evidence
        BEFORE DELETE ON evidence
        BEGIN SELECT RAISE(ABORT, 'evidence is immutable'); END;
        """.trimIndent() + ";",

        // ---- PDM: observations are immutable (append-only) ----
        """
        CREATE TRIGGER IF NOT EXISTS trg_no_update_observation
        BEFORE UPDATE ON observation
        BEGIN SELECT RAISE(ABORT, 'observations are immutable'); END;
        """.trimIndent() + ";",

        """
        CREATE TRIGGER IF NOT EXISTS trg_no_delete_observation
        BEFORE DELETE ON observation
        BEGIN SELECT RAISE(ABORT, 'observations are immutable'); END;
        """.trimIndent() + ";",

        // ---- Stage-4 guard family (I-35): PRIMARY evidence at
        // authorization. A record may not ENTER its authoritative state
        // without at least one PRIMARY evidence link. The gap-matrix
        // requirement «شرط دليل أساسي عند النشر»; the PDM doc itself says
        // this cannot be an FK and must be checked in the adopting/publishing
        // transaction — this is the DB half of that defense in depth
        // (the service half is EvidenceService.assertAuthoritative). ----

        """
        CREATE TRIGGER IF NOT EXISTS trg_finalize_requires_primary_evidence
        BEFORE UPDATE OF finality_status ON reconciliation_record
        WHEN NEW.finality_status='FINALIZED' AND OLD.finality_status<>'FINALIZED'
        BEGIN
          SELECT CASE WHEN NOT EXISTS (
            SELECT 1 FROM reconciliation_evidence
             WHERE reconciliation_id=NEW.reconciliation_id AND evidence_role='PRIMARY'
          ) THEN RAISE(ABORT, 'finalizing a reconciliation requires PRIMARY evidence') END;
        END
        """.trimIndent() + ";",

        """
        CREATE TRIGGER IF NOT EXISTS trg_match_verified_requires_primary_evidence
        BEFORE UPDATE OF match_status ON model_identity_match
        WHEN NEW.match_status='VERIFIED' AND OLD.match_status<>'VERIFIED'
        BEGIN
          SELECT CASE WHEN NOT EXISTS (
            SELECT 1 FROM model_identity_match_evidence
             WHERE match_id=NEW.match_id AND evidence_role='PRIMARY'
          ) THEN RAISE(ABORT, 'verifying an identity match requires PRIMARY evidence') END;
        END
        """.trimIndent() + ";",

        """
        CREATE TRIGGER IF NOT EXISTS trg_metric_relation_active_requires_primary_evidence
        BEFORE UPDATE OF relation_status ON metric_relationship
        WHEN NEW.relation_status='ACTIVE' AND OLD.relation_status<>'ACTIVE'
        BEGIN
          SELECT CASE WHEN NOT EXISTS (
            SELECT 1 FROM metric_relationship_evidence
             WHERE relation_id=NEW.relation_id AND evidence_role='PRIMARY'
          ) THEN RAISE(ABORT, 'activating a metric relation requires PRIMARY evidence') END;
        END
        """.trimIndent() + ";",

        """
        CREATE TRIGGER IF NOT EXISTS trg_resource_constraint_active_requires_primary_evidence
        BEFORE UPDATE OF constraint_status ON resource_constraint
        WHEN NEW.constraint_status='ACTIVE' AND OLD.constraint_status<>'ACTIVE'
        BEGIN
          SELECT CASE WHEN NOT EXISTS (
            SELECT 1 FROM resource_constraint_evidence
             WHERE constraint_id=NEW.constraint_id AND evidence_role='PRIMARY'
          ) THEN RAISE(ABORT, 'activating a resource constraint requires PRIMARY evidence') END;
        END
        """.trimIndent() + ";"
    )

    /** Execution order: tables (parents first), indices, triggers. No seeds — Stage-4 rows are operator/acquisition data, not reference data. */
    val STATEMENTS: List<String> = listOf(
        CREATE_PROVIDER,
        CREATE_PROVIDER_INTEGRATION,
        CREATE_PROVIDER_SCOPE_NODE,
        CREATE_PROVIDER_CAPABILITY_MANIFEST,
        CREATE_CAPABILITY_STATEMENT,
        CREATE_CREDENTIAL_REFERENCE,
        CREATE_ACCESS_CONTEXT,
        CREATE_CREDENTIAL_BINDING,
        CREATE_ACQUISITION_RUN,
        CREATE_MODEL,
        CREATE_PROVIDER_MODEL_RECORD,
        CREATE_MODEL_IDENTITY_MATCH,
        CREATE_MODEL_OFFERING,
        CREATE_REQUEST_OPERATION,
        CREATE_PROVIDER_REQUEST_ATTEMPT,
        CREATE_FRESHNESS_POLICY,
        CREATE_METRIC_RELATIONSHIP,
        CREATE_RESOURCE_CONSTRAINT,
        CREATE_OBSERVATION,
        CREATE_EVIDENCE,
        CREATE_CURRENCY_CONVERSION_RULE_EVIDENCE,
        CREATE_ENTITLEMENT_EVIDENCE,
        CREATE_METRIC_RELATIONSHIP_EVIDENCE,
        CREATE_MODEL_IDENTITY_MATCH_EVIDENCE,
        CREATE_OBSERVATION_EVIDENCE,
        CREATE_PRICING_COMPONENT_EVIDENCE,
        CREATE_PRICING_PROFILE_EVIDENCE,
        CREATE_PRICING_PROFILE_VERSION_EVIDENCE,
        CREATE_PROVIDER_BILLING_RECORD_EVIDENCE,
        CREATE_RECONCILIATION_EVIDENCE,
        CREATE_RESOURCE_CONSTRAINT_EVIDENCE,
        CREATE_USAGE_RECORD_EVIDENCE
    ) + CREATE_INDICES + TRIGGERS
}
