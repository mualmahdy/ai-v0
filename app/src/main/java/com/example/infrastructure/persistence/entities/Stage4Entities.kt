package com.example.infrastructure.persistence.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ============================================================================
 * Stage-4 entities (v27): the evidence & sources network (gap-matrix Phase-4
 * row: "evidence + 12 جدول ربط بأدوار وثقة وفترات وشرط دليل أساسي عند
 * النشر") with the acquisition chain, canonical model registry, observation
 * domain, metric relations and resource constraints it requires by FK.
 *
 * Every index declared here is created with the SAME name by
 * MIGRATION_26_TO_27 (Stage4MigrationSql.CREATE_INDICES) —
 * MigrationTestHelper compares them statement by statement. Open-vocabulary
 * columns are CHECKed in the migration DDL (I-33) and exposed here as plain
 * strings with service-level constants (EvidenceService).
 *
 * The 12 link tables share one physical contract (PDM §3.4): composite PK
 * (target, evidence, role) + confidence_state + is_primary + validity
 * window. The PRIMARY-evidence-at-authorization requirement is enforced by
 * the trg_*_requires_primary_evidence guard family at the DB level and
 * EvidenceService.assertAuthoritative at the service level (I-35).
 */

// ---------------------------------------------------------------------------
// The acquisition chain (Group ج, first half)
// ---------------------------------------------------------------------------

@Entity(
    tableName = "provider",
    indices = [
        Index(value = ["provider_code"], name = "ux_provider_code", unique = true)
    ]
)
data class PdmProviderEntity(
    @PrimaryKey
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "provider_code") val providerCode: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "display_name") val displayName: String?,
    @ColumnInfo(name = "description") val description: String?,
    @ColumnInfo(name = "provider_status") val providerStatus: String,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
    @ColumnInfo(name = "created_by") val createdBy: String?,
    @ColumnInfo(name = "updated_by") val updatedBy: String?
)

@Entity(
    tableName = "provider_integration",
    indices = [
        Index(value = ["provider_id", "integration_code"], name = "ux_provider_integration_code", unique = true),
        Index(value = ["provider_id", "integration_id"], name = "ux_provider_integration_identity", unique = true)
    ],
    foreignKeys = [
        ForeignKey(
            entity = PdmProviderEntity::class,
            parentColumns = ["provider_id"],
            childColumns = ["provider_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class ProviderIntegrationEntity(
    @PrimaryKey
    @ColumnInfo(name = "integration_id") val integrationId: String,
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "integration_code") val integrationCode: String,
    @ColumnInfo(name = "integration_type") val integrationType: String,
    @ColumnInfo(name = "protocol") val protocol: String?,
    @ColumnInfo(name = "protocol_version") val protocolVersion: String?,
    @ColumnInfo(name = "default_endpoint") val defaultEndpoint: String?,
    @ColumnInfo(name = "authentication_scheme") val authenticationScheme: String?,
    @ColumnInfo(name = "integration_status") val integrationStatus: String,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long
)

@Entity(
    tableName = "provider_scope_node",
    indices = [
        Index(value = ["provider_id", "scope_node_id"], name = "ux_provider_scope_node_identity", unique = true),
        Index(value = ["provider_id", "parent_scope_node_id"], name = "ix_provider_scope_node_parent")
    ],
    foreignKeys = [
        ForeignKey(
            entity = PdmProviderEntity::class,
            parentColumns = ["provider_id"],
            childColumns = ["provider_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ProviderScopeNodeEntity::class,
            parentColumns = ["provider_id", "scope_node_id"],
            childColumns = ["provider_id", "parent_scope_node_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class ProviderScopeNodeEntity(
    @PrimaryKey
    @ColumnInfo(name = "scope_node_id") val scopeNodeId: String,
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "external_scope_id") val externalScopeId: String?,
    @ColumnInfo(name = "scope_type") val scopeType: String,
    @ColumnInfo(name = "display_name") val displayName: String?,
    @ColumnInfo(name = "parent_scope_node_id") val parentScopeNodeId: String?,
    @ColumnInfo(name = "scope_status") val scopeStatus: String,
    @ColumnInfo(name = "first_seen_at_epoch_ms") val firstSeenAtEpochMs: Long,
    @ColumnInfo(name = "last_verified_at_epoch_ms") val lastVerifiedAtEpochMs: Long?,
    @ColumnInfo(name = "metadata") val metadata: String?
)

@Entity(
    tableName = "provider_capability_manifest",
    foreignKeys = [
        ForeignKey(
            entity = PdmProviderEntity::class,
            parentColumns = ["provider_id"],
            childColumns = ["provider_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ProviderIntegrationEntity::class,
            parentColumns = ["integration_id"],
            childColumns = ["integration_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class ProviderCapabilityManifestEntity(
    @PrimaryKey
    @ColumnInfo(name = "manifest_id") val manifestId: String,
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "integration_id") val integrationId: String,
    @ColumnInfo(name = "adapter_version") val adapterVersion: String,
    @ColumnInfo(name = "manifest_version") val manifestVersion: String,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
    @ColumnInfo(name = "validated_at_epoch_ms") val validatedAtEpochMs: Long?,
    @ColumnInfo(name = "manifest_status") val manifestStatus: String
)

@Entity(
    tableName = "capability_statement",
    indices = [
        Index(value = ["manifest_id", "operation_type", "metric_id"], name = "ux_capability_statement_key", unique = true)
    ],
    foreignKeys = [
        ForeignKey(
            entity = ProviderCapabilityManifestEntity::class,
            parentColumns = ["manifest_id"],
            childColumns = ["manifest_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = MetricDefinitionEntity::class,
            parentColumns = ["metric_id"],
            childColumns = ["metric_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class CapabilityStatementEntity(
    @PrimaryKey
    @ColumnInfo(name = "capability_id") val capabilityId: String,
    @ColumnInfo(name = "manifest_id") val manifestId: String,
    @ColumnInfo(name = "operation_type") val operationType: String,
    @ColumnInfo(name = "metric_id") val metricId: String?,
    @ColumnInfo(name = "support_status") val supportStatus: String,
    @ColumnInfo(name = "required_permission_class") val requiredPermissionClass: String?,
    @ColumnInfo(name = "notes") val notes: String?
)

@Entity(
    tableName = "credential_reference",
    indices = [
        Index(value = ["provider_id", "secret_store_ref"], name = "ux_credential_reference_store", unique = true),
        Index(value = ["provider_id", "credential_ref_id"], name = "ux_credential_reference_identity", unique = true)
    ],
    foreignKeys = [
        ForeignKey(
            entity = PdmProviderEntity::class,
            parentColumns = ["provider_id"],
            childColumns = ["provider_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class CredentialReferenceEntity(
    @PrimaryKey
    @ColumnInfo(name = "credential_ref_id") val credentialRefId: String,
    @ColumnInfo(name = "provider_id") val providerId: String,
    /** A REFERENCE to the secret store — never a secret itself (I-36). */
    @ColumnInfo(name = "secret_store_ref") val secretStoreRef: String,
    @ColumnInfo(name = "credential_type") val credentialType: String,
    @ColumnInfo(name = "credential_label") val credentialLabel: String?,
    @ColumnInfo(name = "credential_status") val credentialStatus: String,
    @ColumnInfo(name = "lifecycle_status") val lifecycleStatus: String,
    @ColumnInfo(name = "rotation_policy") val rotationPolicy: String?,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
    @ColumnInfo(name = "expires_at_epoch_ms") val expiresAtEpochMs: Long?,
    @ColumnInfo(name = "last_validated_at_epoch_ms") val lastValidatedAtEpochMs: Long?,
    @ColumnInfo(name = "last_validation_result") val lastValidationResult: String?
)

@Entity(
    tableName = "access_context",
    indices = [
        Index(value = ["provider_id", "integration_id", "context_label"], name = "ux_access_context_label", unique = true),
        Index(value = ["provider_id", "access_context_id"], name = "ux_access_context_identity", unique = true),
        Index(value = ["provider_id", "integration_id"], name = "ix_access_context_integration"),
        Index(value = ["provider_id", "account_scope_node_id"], name = "ix_access_context_account_scope"),
        Index(value = ["provider_id", "billing_scope_node_id"], name = "ix_access_context_billing_scope")
    ],
    foreignKeys = [
        ForeignKey(
            entity = PdmProviderEntity::class,
            parentColumns = ["provider_id"],
            childColumns = ["provider_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ProviderIntegrationEntity::class,
            parentColumns = ["provider_id", "integration_id"],
            childColumns = ["provider_id", "integration_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ProviderScopeNodeEntity::class,
            parentColumns = ["provider_id", "scope_node_id"],
            childColumns = ["provider_id", "account_scope_node_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ProviderScopeNodeEntity::class,
            parentColumns = ["provider_id", "scope_node_id"],
            childColumns = ["provider_id", "billing_scope_node_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class AccessContextEntity(
    @PrimaryKey
    @ColumnInfo(name = "access_context_id") val accessContextId: String,
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "integration_id") val integrationId: String,
    @ColumnInfo(name = "account_scope_node_id") val accountScopeNodeId: String?,
    @ColumnInfo(name = "billing_scope_node_id") val billingScopeNodeId: String?,
    @ColumnInfo(name = "context_label") val contextLabel: String,
    @ColumnInfo(name = "default_region") val defaultRegion: String?,
    @ColumnInfo(name = "context_status") val contextStatus: String,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
    @ColumnInfo(name = "last_verified_at_epoch_ms") val lastVerifiedAtEpochMs: Long?
)

@Entity(
    tableName = "credential_binding",
    indices = [
        Index(value = ["credential_binding_id", "access_context_id"], name = "ux_credential_binding_context", unique = true)
    ],
    foreignKeys = [
        ForeignKey(
            entity = CredentialReferenceEntity::class,
            parentColumns = ["credential_ref_id"],
            childColumns = ["credential_ref_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = AccessContextEntity::class,
            parentColumns = ["access_context_id"],
            childColumns = ["access_context_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class CredentialBindingEntity(
    @PrimaryKey
    @ColumnInfo(name = "credential_binding_id") val credentialBindingId: String,
    @ColumnInfo(name = "credential_ref_id") val credentialRefId: String,
    @ColumnInfo(name = "access_context_id") val accessContextId: String,
    @ColumnInfo(name = "credential_purpose") val credentialPurpose: String,
    @ColumnInfo(name = "permission_class") val permissionClass: String?,
    @ColumnInfo(name = "binding_status") val bindingStatus: String,
    @ColumnInfo(name = "valid_from_epoch_ms") val validFromEpochMs: Long,
    @ColumnInfo(name = "valid_until_epoch_ms") val validUntilEpochMs: Long?,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long
)

@Entity(
    tableName = "acquisition_run",
    indices = [
        Index(value = ["target_scope_id"], name = "ix_acquisition_run_target_scope"),
        Index(value = ["capability_statement_id"], name = "ix_acquisition_run_capability")
    ],
    foreignKeys = [
        ForeignKey(
            entity = PdmProviderEntity::class,
            parentColumns = ["provider_id"],
            childColumns = ["provider_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ProviderIntegrationEntity::class,
            parentColumns = ["integration_id"],
            childColumns = ["integration_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = AccessContextEntity::class,
            parentColumns = ["access_context_id"],
            childColumns = ["access_context_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ScopeDescriptorEntity::class,
            parentColumns = ["scope_id"],
            childColumns = ["target_scope_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = CapabilityStatementEntity::class,
            parentColumns = ["capability_id"],
            childColumns = ["capability_statement_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = CredentialBindingEntity::class,
            parentColumns = ["credential_binding_id"],
            childColumns = ["credential_binding_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = AcquisitionRunEntity::class,
            parentColumns = ["acquisition_run_id"],
            childColumns = ["parent_run_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = AcquisitionRunEntity::class,
            parentColumns = ["acquisition_run_id"],
            childColumns = ["retry_of_run_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class AcquisitionRunEntity(
    @PrimaryKey
    @ColumnInfo(name = "acquisition_run_id") val acquisitionRunId: String,
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "integration_id") val integrationId: String,
    @ColumnInfo(name = "access_context_id") val accessContextId: String?,
    @ColumnInfo(name = "operation_type") val operationType: String,
    @ColumnInfo(name = "target_scope_id") val targetScopeId: String?,
    @ColumnInfo(name = "capability_statement_id") val capabilityStatementId: String?,
    @ColumnInfo(name = "credential_binding_id") val credentialBindingId: String?,
    @ColumnInfo(name = "started_at_epoch_ms") val startedAtEpochMs: Long,
    @ColumnInfo(name = "completed_at_epoch_ms") val completedAtEpochMs: Long?,
    @ColumnInfo(name = "run_status") val runStatus: String,
    @ColumnInfo(name = "error_category") val errorCategory: String?,
    @ColumnInfo(name = "error_reference") val errorReference: String?,
    @ColumnInfo(name = "parent_run_id") val parentRunId: String?,
    @ColumnInfo(name = "retry_of_run_id") val retryOfRunId: String?,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String?
)

// ---------------------------------------------------------------------------
// The canonical model registry + identity matches + offerings + the
// request/attempt operation trail (Group ج, second half).
// ---------------------------------------------------------------------------

@Entity(tableName = "model")
data class ModelEntity(
    @PrimaryKey
    @ColumnInfo(name = "model_id") val modelId: String,
    @ColumnInfo(name = "canonical_name") val canonicalName: String,
    @ColumnInfo(name = "model_family") val modelFamily: String?,
    @ColumnInfo(name = "model_version") val modelVersion: String?,
    @ColumnInfo(name = "revision") val revision: String?,
    @ColumnInfo(name = "modalities") val modalities: String?,
    @ColumnInfo(name = "capabilities") val capabilities: String?,
    @ColumnInfo(name = "context_limit") val contextLimit: Int?,
    @ColumnInfo(name = "output_limit") val outputLimit: Int?,
    @ColumnInfo(name = "lifecycle_status") val lifecycleStatus: String,
    @ColumnInfo(name = "metadata") val metadata: String?,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long
)

@Entity(
    tableName = "provider_model_record",
    indices = [
        Index(value = ["provider_id", "integration_id", "provider_native_model_id"], name = "ux_provider_model_record_native", unique = true),
        Index(value = ["provider_model_record_id", "provider_id", "integration_id"], name = "ux_provider_model_record_identity", unique = true)
    ],
    foreignKeys = [
        ForeignKey(
            entity = PdmProviderEntity::class,
            parentColumns = ["provider_id"],
            childColumns = ["provider_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ProviderIntegrationEntity::class,
            parentColumns = ["provider_id", "integration_id"],
            childColumns = ["provider_id", "integration_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class ProviderModelRecordEntity(
    @PrimaryKey
    @ColumnInfo(name = "provider_model_record_id") val providerModelRecordId: String,
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "integration_id") val integrationId: String,
    @ColumnInfo(name = "provider_native_model_id") val providerNativeModelId: String,
    @ColumnInfo(name = "display_name") val displayName: String?,
    @ColumnInfo(name = "provider_version") val providerVersion: String?,
    @ColumnInfo(name = "discovery_source") val discoverySource: String,
    @ColumnInfo(name = "discovery_status") val discoveryStatus: String,
    @ColumnInfo(name = "raw_metadata") val rawMetadata: String?,
    @ColumnInfo(name = "first_seen_at_epoch_ms") val firstSeenAtEpochMs: Long,
    @ColumnInfo(name = "last_seen_at_epoch_ms") val lastSeenAtEpochMs: Long?
)

@Entity(
    tableName = "model_identity_match",
    indices = [
        Index(value = ["match_id", "provider_model_record_id"], name = "ux_model_identity_match_identity", unique = true),
        Index(value = ["canonical_model_id"], name = "ix_model_identity_match_canonical")
    ],
    foreignKeys = [
        ForeignKey(
            entity = ProviderModelRecordEntity::class,
            parentColumns = ["provider_model_record_id"],
            childColumns = ["provider_model_record_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ModelEntity::class,
            parentColumns = ["model_id"],
            childColumns = ["canonical_model_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ModelIdentityMatchEntity::class,
            parentColumns = ["match_id"],
            childColumns = ["superseded_by_match_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class ModelIdentityMatchEntity(
    @PrimaryKey
    @ColumnInfo(name = "match_id") val matchId: String,
    @ColumnInfo(name = "provider_model_record_id") val providerModelRecordId: String,
    @ColumnInfo(name = "canonical_model_id") val canonicalModelId: String,
    @ColumnInfo(name = "match_status") val matchStatus: String,
    @ColumnInfo(name = "confidence_score") val confidenceScore: String?,
    @ColumnInfo(name = "match_reason") val matchReason: String?,
    @ColumnInfo(name = "valid_from_epoch_ms") val validFromEpochMs: Long,
    @ColumnInfo(name = "valid_until_epoch_ms") val validUntilEpochMs: Long?,
    @ColumnInfo(name = "matched_at_epoch_ms") val matchedAtEpochMs: Long,
    @ColumnInfo(name = "superseded_by_match_id") val supersededByMatchId: String?
)

@Entity(
    tableName = "model_offering",
    foreignKeys = [
        ForeignKey(
            entity = ProviderModelRecordEntity::class,
            parentColumns = ["provider_model_record_id"],
            childColumns = ["provider_model_record_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class ModelOfferingEntity(
    @PrimaryKey
    @ColumnInfo(name = "offering_id") val offeringId: String,
    @ColumnInfo(name = "provider_model_record_id") val providerModelRecordId: String,
    @ColumnInfo(name = "offering_code") val offeringCode: String,
    @ColumnInfo(name = "endpoint") val endpoint: String?,
    @ColumnInfo(name = "region") val region: String?,
    @ColumnInfo(name = "service_mode") val serviceMode: String?,
    @ColumnInfo(name = "variant") val variant: String?,
    @ColumnInfo(name = "availability_status") val availabilityStatus: String,
    @ColumnInfo(name = "availability_source") val availabilitySource: String,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long
)

@Entity(tableName = "request_operation")
data class RequestOperationEntity(
    @PrimaryKey
    @ColumnInfo(name = "request_operation_id") val requestOperationId: String,
    @ColumnInfo(name = "application_ref") val applicationRef: String?,
    @ColumnInfo(name = "workspace_ref") val workspaceRef: String?,
    @ColumnInfo(name = "project_ref") val projectRef: String?,
    @ColumnInfo(name = "session_ref") val sessionRef: String?,
    @ColumnInfo(name = "operation_type") val operationType: String,
    @ColumnInfo(name = "operation_status") val operationStatus: String,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
    @ColumnInfo(name = "client_operation_id") val clientOperationId: String?
)

@Entity(
    tableName = "provider_request_attempt",
    indices = [
        Index(value = ["request_operation_id", "attempt_number"], name = "ux_provider_request_attempt_operation", unique = true),
        Index(value = ["request_attempt_id", "provider_id", "integration_id"], name = "ux_provider_request_attempt_identity", unique = true),
        Index(value = ["offering_id"], name = "ix_provider_request_attempt_offering")
    ],
    foreignKeys = [
        ForeignKey(
            entity = RequestOperationEntity::class,
            parentColumns = ["request_operation_id"],
            childColumns = ["request_operation_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = PdmProviderEntity::class,
            parentColumns = ["provider_id"],
            childColumns = ["provider_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ModelOfferingEntity::class,
            parentColumns = ["offering_id"],
            childColumns = ["offering_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = AccessContextEntity::class,
            parentColumns = ["access_context_id"],
            childColumns = ["access_context_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = CredentialBindingEntity::class,
            parentColumns = ["credential_binding_id"],
            childColumns = ["credential_binding_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ProviderRequestAttemptEntity::class,
            parentColumns = ["request_attempt_id"],
            childColumns = ["retry_of_attempt_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ProviderIntegrationEntity::class,
            parentColumns = ["provider_id", "integration_id"],
            childColumns = ["provider_id", "integration_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class ProviderRequestAttemptEntity(
    @PrimaryKey
    @ColumnInfo(name = "request_attempt_id") val requestAttemptId: String,
    @ColumnInfo(name = "request_operation_id") val requestOperationId: String,
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "integration_id") val integrationId: String,
    @ColumnInfo(name = "offering_id") val offeringId: String,
    @ColumnInfo(name = "access_context_id") val accessContextId: String,
    @ColumnInfo(name = "credential_binding_id") val credentialBindingId: String?,
    @ColumnInfo(name = "provider_request_id") val providerRequestId: String?,
    @ColumnInfo(name = "client_attempt_id") val clientAttemptId: String?,
    @ColumnInfo(name = "attempt_number") val attemptNumber: Int,
    @ColumnInfo(name = "started_at_epoch_ms") val startedAtEpochMs: Long,
    @ColumnInfo(name = "completed_at_epoch_ms") val completedAtEpochMs: Long?,
    @ColumnInfo(name = "request_status") val requestStatus: String,
    @ColumnInfo(name = "http_status") val httpStatus: Int?,
    @ColumnInfo(name = "provider_error_code") val providerErrorCode: String?,
    @ColumnInfo(name = "retry_of_attempt_id") val retryOfAttemptId: String?,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String?
)

@Entity(
    tableName = "freshness_policy",
    foreignKeys = [
        ForeignKey(
            entity = MetricDefinitionEntity::class,
            parentColumns = ["metric_id"],
            childColumns = ["metric_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ScopeDescriptorEntity::class,
            parentColumns = ["scope_id"],
            childColumns = ["scope_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class FreshnessPolicyEntity(
    @PrimaryKey
    @ColumnInfo(name = "policy_id") val policyId: String,
    @ColumnInfo(name = "metric_id") val metricId: String,
    @ColumnInfo(name = "scope_id") val scopeId: String?,
    @ColumnInfo(name = "expected_update_interval_seconds") val expectedUpdateIntervalSeconds: Int,
    @ColumnInfo(name = "staleness_threshold_seconds") val stalenessThresholdSeconds: Int,
    @ColumnInfo(name = "source_delay_seconds", defaultValue = "0") val sourceDelaySeconds: Int,
    @ColumnInfo(name = "unknown_when_expired", defaultValue = "0") val unknownWhenExpired: Int,
    @ColumnInfo(name = "effective_from_epoch_ms") val effectiveFromEpochMs: Long,
    @ColumnInfo(name = "effective_until_epoch_ms") val effectiveUntilEpochMs: Long?,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long
)

// ---------------------------------------------------------------------------
// Semantics + constraints (Group ب remainder) + observation (Group د)
// + the evidence core.
// ---------------------------------------------------------------------------

@Entity(
    tableName = "metric_relationship",
    indices = [
        Index(value = ["source_metric_id", "target_metric_id", "relation_type", "effective_from_epoch_ms"], name = "ix_metric_relationship_pair"),
        Index(value = ["scope_id"], name = "ix_metric_relationship_scope")
    ],
    foreignKeys = [
        ForeignKey(
            entity = MetricDefinitionEntity::class,
            parentColumns = ["metric_id"],
            childColumns = ["source_metric_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = MetricDefinitionEntity::class,
            parentColumns = ["metric_id"],
            childColumns = ["target_metric_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ScopeDescriptorEntity::class,
            parentColumns = ["scope_id"],
            childColumns = ["scope_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class MetricRelationshipEntity(
    @PrimaryKey
    @ColumnInfo(name = "relation_id") val relationId: String,
    @ColumnInfo(name = "source_metric_id") val sourceMetricId: String,
    @ColumnInfo(name = "target_metric_id") val targetMetricId: String,
    @ColumnInfo(name = "relation_type") val relationType: String,
    @ColumnInfo(name = "scope_id") val scopeId: String?,
    @ColumnInfo(name = "effective_from_epoch_ms") val effectiveFromEpochMs: Long,
    @ColumnInfo(name = "effective_until_epoch_ms") val effectiveUntilEpochMs: Long?,
    @ColumnInfo(name = "relation_status") val relationStatus: String
)

@Entity(
    tableName = "resource_constraint",
    indices = [
        Index(value = ["applicability_scope_id"], name = "ix_resource_constraint_scope")
    ],
    foreignKeys = [
        ForeignKey(
            entity = PdmProviderEntity::class,
            parentColumns = ["provider_id"],
            childColumns = ["provider_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ScopeDescriptorEntity::class,
            parentColumns = ["scope_id"],
            childColumns = ["applicability_scope_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = MetricDefinitionEntity::class,
            parentColumns = ["metric_id"],
            childColumns = ["metric_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = UnitDefinitionEntity::class,
            parentColumns = ["unit_id"],
            childColumns = ["unit_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class ResourceConstraintEntity(
    @PrimaryKey
    @ColumnInfo(name = "constraint_id") val constraintId: String,
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "applicability_scope_id") val applicabilityScopeId: String,
    @ColumnInfo(name = "constraint_type") val constraintType: String,
    @ColumnInfo(name = "constraint_source_type") val constraintSourceType: String,
    @ColumnInfo(name = "metric_id") val metricId: String?,
    @ColumnInfo(name = "limit_value_precise") val limitValuePrecise: String?,
    @ColumnInfo(name = "unit_id") val unitId: String?,
    @ColumnInfo(name = "window_type") val windowType: String,
    @ColumnInfo(name = "window_duration_seconds") val windowDurationSeconds: Int?,
    @ColumnInfo(name = "limiting_algorithm") val limitingAlgorithm: String?,
    @ColumnInfo(name = "refill_policy") val refillPolicy: String?,
    @ColumnInfo(name = "effective_from_epoch_ms") val effectiveFromEpochMs: Long,
    @ColumnInfo(name = "effective_until_epoch_ms") val effectiveUntilEpochMs: Long?,
    @ColumnInfo(name = "constraint_status") val constraintStatus: String
)

@Entity(
    tableName = "observation",
    indices = [
        Index(value = ["metric_id", "observed_at_epoch_ms"], name = "ix_observation_metric_time"),
        Index(value = ["subject_access_context_id", "metric_id", "observed_at_epoch_ms"], name = "ix_observation_context_metric_time"),
        Index(value = ["subject_offering_id", "metric_id", "observed_at_epoch_ms"], name = "ix_observation_offering_metric_time")
    ],
    foreignKeys = [
        ForeignKey(
            entity = PdmProviderEntity::class,
            parentColumns = ["provider_id"],
            childColumns = ["provider_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = PdmProviderEntity::class,
            parentColumns = ["provider_id"],
            childColumns = ["subject_provider_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = AccessContextEntity::class,
            parentColumns = ["access_context_id"],
            childColumns = ["subject_access_context_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ProviderScopeNodeEntity::class,
            parentColumns = ["scope_node_id"],
            childColumns = ["subject_provider_scope_node_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ProviderModelRecordEntity::class,
            parentColumns = ["provider_model_record_id"],
            childColumns = ["subject_provider_model_record_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ModelEntity::class,
            parentColumns = ["model_id"],
            childColumns = ["subject_model_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ModelOfferingEntity::class,
            parentColumns = ["offering_id"],
            childColumns = ["subject_offering_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ProviderRequestAttemptEntity::class,
            parentColumns = ["request_attempt_id"],
            childColumns = ["subject_request_attempt_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = MetricDefinitionEntity::class,
            parentColumns = ["metric_id"],
            childColumns = ["metric_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = UnitDefinitionEntity::class,
            parentColumns = ["unit_id"],
            childColumns = ["unit_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ScopeDescriptorEntity::class,
            parentColumns = ["scope_id"],
            childColumns = ["scope_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = FreshnessPolicyEntity::class,
            parentColumns = ["policy_id"],
            childColumns = ["freshness_policy_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = AcquisitionRunEntity::class,
            parentColumns = ["acquisition_run_id"],
            childColumns = ["acquisition_run_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ObservationEntity::class,
            parentColumns = ["observation_id"],
            childColumns = ["correction_of_observation_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class ObservationEntity(
    @PrimaryKey
    @ColumnInfo(name = "observation_id") val observationId: String,
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "subject_provider_id") val subjectProviderId: String?,
    @ColumnInfo(name = "subject_access_context_id") val subjectAccessContextId: String?,
    @ColumnInfo(name = "subject_provider_scope_node_id") val subjectProviderScopeNodeId: String?,
    @ColumnInfo(name = "subject_provider_model_record_id") val subjectProviderModelRecordId: String?,
    @ColumnInfo(name = "subject_model_id") val subjectModelId: String?,
    @ColumnInfo(name = "subject_offering_id") val subjectOfferingId: String?,
    @ColumnInfo(name = "subject_request_attempt_id") val subjectRequestAttemptId: String?,
    @ColumnInfo(name = "metric_id") val metricId: String,
    @ColumnInfo(name = "value_type") val valueType: String,
    @ColumnInfo(name = "numeric_value_precise") val numericValuePrecise: String?,
    @ColumnInfo(name = "text_value") val textValue: String?,
    @ColumnInfo(name = "boolean_value") val booleanValue: Int?,
    @ColumnInfo(name = "state_value") val stateValue: String?,
    @ColumnInfo(name = "unit_id") val unitId: String?,
    @ColumnInfo(name = "scope_id") val scopeId: String?,
    @ColumnInfo(name = "observed_at_epoch_ms") val observedAtEpochMs: Long,
    @ColumnInfo(name = "valid_from_epoch_ms") val validFromEpochMs: Long?,
    @ColumnInfo(name = "valid_until_epoch_ms") val validUntilEpochMs: Long?,
    @ColumnInfo(name = "validity_status") val validityStatus: String,
    @ColumnInfo(name = "reset_at_epoch_ms") val resetAtEpochMs: Long?,
    @ColumnInfo(name = "reset_after_epoch_ms") val resetAfterEpochMs: Long?,
    @ColumnInfo(name = "source_authority") val sourceAuthority: String,
    @ColumnInfo(name = "knowledge_status") val knowledgeStatus: String,
    @ColumnInfo(name = "unknown_reason_code") val unknownReasonCode: String?,
    @ColumnInfo(name = "freshness_policy_id") val freshnessPolicyId: String?,
    @ColumnInfo(name = "acquisition_run_id") val acquisitionRunId: String?,
    @ColumnInfo(name = "derivation_rule_id") val derivationRuleId: String?,
    @ColumnInfo(name = "confidence_score") val confidenceScore: String?,
    @ColumnInfo(name = "recorded_at_epoch_ms") val recordedAtEpochMs: Long,
    @ColumnInfo(name = "correction_of_observation_id") val correctionOfObservationId: String?
)

@Entity(
    tableName = "evidence",
    indices = [
        Index(value = ["payload_digest"], name = "ix_evidence_digest"),
        Index(value = ["acquisition_run_id"], name = "ix_evidence_acquisition")
    ],
    foreignKeys = [
        ForeignKey(
            entity = AcquisitionRunEntity::class,
            parentColumns = ["acquisition_run_id"],
            childColumns = ["acquisition_run_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class EvidenceEntity(
    @PrimaryKey
    @ColumnInfo(name = "evidence_id") val evidenceId: String,
    @ColumnInfo(name = "acquisition_run_id") val acquisitionRunId: String?,
    @ColumnInfo(name = "evidence_type") val evidenceType: String,
    @ColumnInfo(name = "source_location") val sourceLocation: String?,
    @ColumnInfo(name = "source_field") val sourceField: String?,
    @ColumnInfo(name = "source_reference") val sourceReference: String?,
    @ColumnInfo(name = "retrieved_at_epoch_ms") val retrievedAtEpochMs: Long?,
    @ColumnInfo(name = "provider_request_id") val providerRequestId: String?,
    @ColumnInfo(name = "payload_digest") val payloadDigest: String?,
    @ColumnInfo(name = "hash_algorithm") val hashAlgorithm: String?,
    @ColumnInfo(name = "payload_storage_mode") val payloadStorageMode: String,
    @ColumnInfo(name = "verification_available", defaultValue = "0") val verificationAvailable: Int,
    @ColumnInfo(name = "redaction_status") val redactionStatus: String,
    @ColumnInfo(name = "retention_until_epoch_ms") val retentionUntilEpochMs: Long?,
    @ColumnInfo(name = "evidence_status") val evidenceStatus: String,
    @ColumnInfo(name = "security_classification") val securityClassification: String,
    @ColumnInfo(name = "sanitized_payload") val sanitizedPayload: String?,
    @ColumnInfo(name = "recorded_at_epoch_ms") val recordedAtEpochMs: Long
)

// ---------------------------------------------------------------------------
// The twelve typed evidence-link tables — ONE shared physical contract
// (PDM §3.4): composite PK (target, evidence, role); the validity window
// is epoch-ms; is_primary defaults to 0. All inserts are ABORT (a silent
// replace of an evidence link is forbidden — ADR-B4 family).
// ---------------------------------------------------------------------------

@Entity(
    tableName = "currency_conversion_rule_evidence",
    primaryKeys = ["currency_conversion_rule_id", "evidence_id", "evidence_role"],
    foreignKeys = [
        ForeignKey(
            entity = CurrencyConversionRuleEntity::class,
            parentColumns = ["currency_conversion_rule_id"],
            childColumns = ["currency_conversion_rule_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = EvidenceEntity::class,
            parentColumns = ["evidence_id"],
            childColumns = ["evidence_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class CurrencyConversionRuleEvidenceEntity(
    @ColumnInfo(name = "currency_conversion_rule_id") val currencyConversionRuleId: String,
    @ColumnInfo(name = "evidence_id") val evidenceId: String,
    @ColumnInfo(name = "evidence_role") val evidenceRole: String,
    @ColumnInfo(name = "confidence_state") val confidenceState: String,
    @ColumnInfo(name = "is_primary", defaultValue = "0") val isPrimary: Int,
    @ColumnInfo(name = "valid_from_epoch_ms") val validFromEpochMs: Long?,
    @ColumnInfo(name = "valid_until_epoch_ms") val validUntilEpochMs: Long?,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long
)

@Entity(
    tableName = "entitlement_evidence",
    primaryKeys = ["entitlement_id", "evidence_id", "evidence_role"],
    foreignKeys = [
        ForeignKey(
            entity = EntitlementRecordEntity::class,
            parentColumns = ["entitlement_id"],
            childColumns = ["entitlement_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = EvidenceEntity::class,
            parentColumns = ["evidence_id"],
            childColumns = ["evidence_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class EntitlementEvidenceEntity(
    @ColumnInfo(name = "entitlement_id") val entitlementId: String,
    @ColumnInfo(name = "evidence_id") val evidenceId: String,
    @ColumnInfo(name = "evidence_role") val evidenceRole: String,
    @ColumnInfo(name = "confidence_state") val confidenceState: String,
    @ColumnInfo(name = "is_primary", defaultValue = "0") val isPrimary: Int,
    @ColumnInfo(name = "valid_from_epoch_ms") val validFromEpochMs: Long?,
    @ColumnInfo(name = "valid_until_epoch_ms") val validUntilEpochMs: Long?,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long
)

@Entity(
    tableName = "metric_relationship_evidence",
    primaryKeys = ["relation_id", "evidence_id", "evidence_role"],
    foreignKeys = [
        ForeignKey(
            entity = MetricRelationshipEntity::class,
            parentColumns = ["relation_id"],
            childColumns = ["relation_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = EvidenceEntity::class,
            parentColumns = ["evidence_id"],
            childColumns = ["evidence_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class MetricRelationshipEvidenceEntity(
    @ColumnInfo(name = "relation_id") val relationId: String,
    @ColumnInfo(name = "evidence_id") val evidenceId: String,
    @ColumnInfo(name = "evidence_role") val evidenceRole: String,
    @ColumnInfo(name = "confidence_state") val confidenceState: String,
    @ColumnInfo(name = "is_primary", defaultValue = "0") val isPrimary: Int,
    @ColumnInfo(name = "valid_from_epoch_ms") val validFromEpochMs: Long?,
    @ColumnInfo(name = "valid_until_epoch_ms") val validUntilEpochMs: Long?,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long
)

@Entity(
    tableName = "model_identity_match_evidence",
    primaryKeys = ["match_id", "evidence_id", "evidence_role"],
    foreignKeys = [
        ForeignKey(
            entity = ModelIdentityMatchEntity::class,
            parentColumns = ["match_id"],
            childColumns = ["match_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = EvidenceEntity::class,
            parentColumns = ["evidence_id"],
            childColumns = ["evidence_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class ModelIdentityMatchEvidenceEntity(
    @ColumnInfo(name = "match_id") val matchId: String,
    @ColumnInfo(name = "evidence_id") val evidenceId: String,
    @ColumnInfo(name = "evidence_role") val evidenceRole: String,
    @ColumnInfo(name = "confidence_state") val confidenceState: String,
    @ColumnInfo(name = "is_primary", defaultValue = "0") val isPrimary: Int,
    @ColumnInfo(name = "valid_from_epoch_ms") val validFromEpochMs: Long?,
    @ColumnInfo(name = "valid_until_epoch_ms") val validUntilEpochMs: Long?,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long
)

@Entity(
    tableName = "observation_evidence",
    primaryKeys = ["observation_id", "evidence_id", "evidence_role"],
    foreignKeys = [
        ForeignKey(
            entity = ObservationEntity::class,
            parentColumns = ["observation_id"],
            childColumns = ["observation_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = EvidenceEntity::class,
            parentColumns = ["evidence_id"],
            childColumns = ["evidence_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class ObservationEvidenceEntity(
    @ColumnInfo(name = "observation_id") val observationId: String,
    @ColumnInfo(name = "evidence_id") val evidenceId: String,
    @ColumnInfo(name = "evidence_role") val evidenceRole: String,
    @ColumnInfo(name = "confidence_state") val confidenceState: String,
    @ColumnInfo(name = "is_primary", defaultValue = "0") val isPrimary: Int,
    @ColumnInfo(name = "valid_from_epoch_ms") val validFromEpochMs: Long?,
    @ColumnInfo(name = "valid_until_epoch_ms") val validUntilEpochMs: Long?,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long
)

@Entity(
    tableName = "pricing_component_evidence",
    primaryKeys = ["pricing_component_id", "evidence_id", "evidence_role"],
    foreignKeys = [
        ForeignKey(
            entity = PricingComponentEntity::class,
            parentColumns = ["pricing_component_id"],
            childColumns = ["pricing_component_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = EvidenceEntity::class,
            parentColumns = ["evidence_id"],
            childColumns = ["evidence_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class PricingComponentEvidenceEntity(
    @ColumnInfo(name = "pricing_component_id") val pricingComponentId: String,
    @ColumnInfo(name = "evidence_id") val evidenceId: String,
    @ColumnInfo(name = "evidence_role") val evidenceRole: String,
    @ColumnInfo(name = "confidence_state") val confidenceState: String,
    @ColumnInfo(name = "is_primary", defaultValue = "0") val isPrimary: Int,
    @ColumnInfo(name = "valid_from_epoch_ms") val validFromEpochMs: Long?,
    @ColumnInfo(name = "valid_until_epoch_ms") val validUntilEpochMs: Long?,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long
)

@Entity(
    tableName = "pricing_profile_evidence",
    primaryKeys = ["pricing_profile_id", "evidence_id", "evidence_role"],
    foreignKeys = [
        ForeignKey(
            entity = PricingProfileEntity::class,
            parentColumns = ["pricing_profile_id"],
            childColumns = ["pricing_profile_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = EvidenceEntity::class,
            parentColumns = ["evidence_id"],
            childColumns = ["evidence_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class PricingProfileEvidenceEntity(
    @ColumnInfo(name = "pricing_profile_id") val pricingProfileId: String,
    @ColumnInfo(name = "evidence_id") val evidenceId: String,
    @ColumnInfo(name = "evidence_role") val evidenceRole: String,
    @ColumnInfo(name = "confidence_state") val confidenceState: String,
    @ColumnInfo(name = "is_primary", defaultValue = "0") val isPrimary: Int,
    @ColumnInfo(name = "valid_from_epoch_ms") val validFromEpochMs: Long?,
    @ColumnInfo(name = "valid_until_epoch_ms") val validUntilEpochMs: Long?,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long
)

@Entity(
    tableName = "pricing_profile_version_evidence",
    primaryKeys = ["pricing_profile_version_id", "evidence_id", "evidence_role"],
    foreignKeys = [
        ForeignKey(
            entity = PricingProfileVersionEntity::class,
            parentColumns = ["pricing_profile_version_id"],
            childColumns = ["pricing_profile_version_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = EvidenceEntity::class,
            parentColumns = ["evidence_id"],
            childColumns = ["evidence_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class PricingProfileVersionEvidenceEntity(
    @ColumnInfo(name = "pricing_profile_version_id") val pricingProfileVersionId: String,
    @ColumnInfo(name = "evidence_id") val evidenceId: String,
    @ColumnInfo(name = "evidence_role") val evidenceRole: String,
    @ColumnInfo(name = "confidence_state") val confidenceState: String,
    @ColumnInfo(name = "is_primary", defaultValue = "0") val isPrimary: Int,
    @ColumnInfo(name = "valid_from_epoch_ms") val validFromEpochMs: Long?,
    @ColumnInfo(name = "valid_until_epoch_ms") val validUntilEpochMs: Long?,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long
)

@Entity(
    tableName = "provider_billing_record_evidence",
    primaryKeys = ["provider_billing_record_id", "evidence_id", "evidence_role"],
    foreignKeys = [
        ForeignKey(
            entity = ProviderBillingRecordEntity::class,
            parentColumns = ["provider_billing_record_id"],
            childColumns = ["provider_billing_record_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = EvidenceEntity::class,
            parentColumns = ["evidence_id"],
            childColumns = ["evidence_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class ProviderBillingRecordEvidenceEntity(
    @ColumnInfo(name = "provider_billing_record_id") val providerBillingRecordId: String,
    @ColumnInfo(name = "evidence_id") val evidenceId: String,
    @ColumnInfo(name = "evidence_role") val evidenceRole: String,
    @ColumnInfo(name = "confidence_state") val confidenceState: String,
    @ColumnInfo(name = "is_primary", defaultValue = "0") val isPrimary: Int,
    @ColumnInfo(name = "valid_from_epoch_ms") val validFromEpochMs: Long?,
    @ColumnInfo(name = "valid_until_epoch_ms") val validUntilEpochMs: Long?,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long
)

@Entity(
    tableName = "reconciliation_evidence",
    primaryKeys = ["reconciliation_id", "evidence_id", "evidence_role"],
    foreignKeys = [
        ForeignKey(
            entity = ReconciliationRecordEntity::class,
            parentColumns = ["reconciliation_id"],
            childColumns = ["reconciliation_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = EvidenceEntity::class,
            parentColumns = ["evidence_id"],
            childColumns = ["evidence_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class ReconciliationEvidenceEntity(
    @ColumnInfo(name = "reconciliation_id") val reconciliationId: String,
    @ColumnInfo(name = "evidence_id") val evidenceId: String,
    @ColumnInfo(name = "evidence_role") val evidenceRole: String,
    @ColumnInfo(name = "confidence_state") val confidenceState: String,
    @ColumnInfo(name = "is_primary", defaultValue = "0") val isPrimary: Int,
    @ColumnInfo(name = "valid_from_epoch_ms") val validFromEpochMs: Long?,
    @ColumnInfo(name = "valid_until_epoch_ms") val validUntilEpochMs: Long?,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long
)

@Entity(
    tableName = "resource_constraint_evidence",
    primaryKeys = ["constraint_id", "evidence_id", "evidence_role"],
    foreignKeys = [
        ForeignKey(
            entity = ResourceConstraintEntity::class,
            parentColumns = ["constraint_id"],
            childColumns = ["constraint_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = EvidenceEntity::class,
            parentColumns = ["evidence_id"],
            childColumns = ["evidence_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class ResourceConstraintEvidenceEntity(
    @ColumnInfo(name = "constraint_id") val constraintId: String,
    @ColumnInfo(name = "evidence_id") val evidenceId: String,
    @ColumnInfo(name = "evidence_role") val evidenceRole: String,
    @ColumnInfo(name = "confidence_state") val confidenceState: String,
    @ColumnInfo(name = "is_primary", defaultValue = "0") val isPrimary: Int,
    @ColumnInfo(name = "valid_from_epoch_ms") val validFromEpochMs: Long?,
    @ColumnInfo(name = "valid_until_epoch_ms") val validUntilEpochMs: Long?,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long
)

@Entity(
    tableName = "usage_record_evidence",
    primaryKeys = ["usage_record_id", "evidence_id", "evidence_role"],
    foreignKeys = [
        ForeignKey(
            entity = UsageRecordEntity::class,
            parentColumns = ["usage_record_id"],
            childColumns = ["usage_record_id"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = EvidenceEntity::class,
            parentColumns = ["evidence_id"],
            childColumns = ["evidence_id"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class UsageRecordEvidenceEntity(
    @ColumnInfo(name = "usage_record_id") val usageRecordId: String,
    @ColumnInfo(name = "evidence_id") val evidenceId: String,
    @ColumnInfo(name = "evidence_role") val evidenceRole: String,
    @ColumnInfo(name = "confidence_state") val confidenceState: String,
    @ColumnInfo(name = "is_primary", defaultValue = "0") val isPrimary: Int,
    @ColumnInfo(name = "valid_from_epoch_ms") val validFromEpochMs: Long?,
    @ColumnInfo(name = "valid_until_epoch_ms") val validUntilEpochMs: Long?,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long
)
