package com.example.application.governed

import com.example.domain.core.Outcome
import com.example.domain.core.storage.StorageFailure
import com.example.domain.core.storage.WorkspaceFileEntry
import com.example.domain.core.security.governance.PrincipalType
import com.example.domain.core.security.governance.Permission
import com.example.domain.core.security.governance.SecurableResourceType
import com.example.domain.ports.governed.AdmissionAuditPort
import com.example.domain.ports.governed.HumanApprovalStorePort
import com.example.domain.ports.governed.PrincipalAuthorizationPort
import com.example.domain.ports.security.SecurityGuardPort
import com.example.domain.ports.storage.WorkspaceStoragePort
import com.example.infrastructure.governed.InMemoryHumanApprovalStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * ============================================================================
 * GovernedTestFakes — Phase 1 test doubles
 * ============================================================================
 * Pure-Kotlin fakes so the admission pipeline is testable on the JVM with
 * NO Android framework and NO Room. The REAL SecurityGuardService is pure
 * Kotlin and is used directly in tests (not faked) wherever possible.
 */

/** In-memory workspace storage mirroring WorkspaceStoragePort semantics. */
class FakeWorkspaceStorage : WorkspaceStoragePort {
    private val files = ConcurrentHashMap<String, String>() // "projectId/relPath" -> content
    private val mutex = Mutex()

    private fun key(projectId: Long, relativePath: String) = "$projectId/$relativePath"

    override suspend fun readFile(projectId: Long, relativePath: String): Outcome<String, StorageFailure> =
        mutex.withLock {
            val content = files[key(projectId, relativePath)]
            if (content == null) Outcome.Error(StorageFailure.FileNotFound(relativePath))
            else Outcome.Success(content)
        }

    override suspend fun writeFile(projectId: Long, relativePath: String, content: String): Outcome<Unit, StorageFailure> =
        mutex.withLock {
            // Mirror production containment behaviour: no escape from project dir.
            if (relativePath.contains("..")) {
                return@withLock Outcome.Error(StorageFailure.AccessDenied(relativePath, "خارج نطاق المشروع."))
            }
            files[key(projectId, relativePath)] = content
            Outcome.Success(Unit)
        }

    override suspend fun listFiles(projectId: Long, subDirectory: String?): Outcome<List<WorkspaceFileEntry>, StorageFailure> =
        mutex.withLock {
            val prefix = "$projectId/" + (subDirectory?.trimEnd('/')?.plus("/") ?: "")
            val entries = files.keys
                .filter { it.startsWith(prefix) }
                .map { full ->
                    val rel = full.removePrefix("$projectId/")
                    WorkspaceFileEntry(relativePath = rel, isDirectory = false, sizeBytes = files[full]!!.length.toLong())
                }
                .sortedBy { it.relativePath }
            Outcome.Success(entries)
        }

    override suspend fun deleteFile(projectId: Long, relativePath: String): Outcome<Unit, StorageFailure> =
        mutex.withLock {
            val k = key(projectId, relativePath)
            if (!files.containsKey(k)) Outcome.Error(StorageFailure.FileNotFound(relativePath))
            else {
                files.remove(k)
                Outcome.Success(Unit)
            }
        }

    override suspend fun fileExists(projectId: Long, relativePath: String): Boolean =
        files.containsKey(key(projectId, relativePath))

    fun seed(projectId: Long, relativePath: String, content: String) {
        files[key(projectId, relativePath)] = content
    }
}

/** Allow-all principal authorization with deny-list support. */
class FakePrincipalAuthorization(private val denied: Set<String> = emptySet()) : PrincipalAuthorizationPort {
    override suspend fun check(
        principalType: PrincipalType,
        principalId: String,
        resourceType: SecurableResourceType,
        resourceId: String,
        permission: Permission,
        workspaceId: String?
    ): Boolean = "$principalId|$resourceId" !in denied
}

/** Captures every audit record for assertions. */
class RecordingAuditSink : AdmissionAuditPort {
    data class Record(
        val severity: String,
        val actor: String,
        val action: String,
        val resourceId: String,
        val decision: String,
        val reason: String,
        val attributes: Map<String, String>
    )

    val records = mutableListOf<Record>()

    override suspend fun record(
        severity: String,
        actor: String,
        action: String,
        resourceType: String,
        resourceId: String,
        decision: String,
        reason: String,
        workspaceId: String?,
        attributes: Map<String, String>
    ) {
        records += Record(severity, actor, action, resourceId, decision, reason, attributes)
    }
}

/** Scriptable budget gate. */
class ScriptableBudgetGate(
    private var verdictProvider: (String) -> BudgetAuthorizationOutcome
) : com.example.application.governed.BudgetAuthorizationPort {
    override suspend fun authorize(request: com.example.domain.core.security.governance.ToolAdmissionRequest): BudgetAuthorizationOutcome =
        verdictProvider(request.toolName)

    fun script(provider: (String) -> BudgetAuthorizationOutcome) {
        verdictProvider = provider
    }
}

/** Scriptable rate-limit gate (default: allow). */
class ScriptableRateLimit(private var allow: Boolean = true) : com.example.application.governed.RateLimitCheckPort {
    override fun tryAcquire(scopeKey: String): Boolean = allow
    fun deny() { allow = false }
    fun allowAll() { allow = true }
}

/** Scriptable security guard for pipeline tests needing precise control. */
class ScriptableSecurityGuard(
    private var decisionProvider: (String) -> com.example.domain.core.security.SecurityEvaluation
) : SecurityGuardPort {

    override fun evaluateToolExecution(
        input: com.example.domain.core.tools.ToolInput,
        policy: com.example.domain.core.security.SecurityPolicy
    ): com.example.domain.core.security.SecurityEvaluation = decisionProvider(input.toolName)

    fun script(provider: (String) -> com.example.domain.core.security.SecurityEvaluation) {
        decisionProvider = provider
    }

    override fun sanitizeUntrustedOutput(rawOutput: String): String = rawOutput

    override suspend fun validateTokenBudget(
        requestedTokens: Int,
        sessionTotalTokens: Int,
        policy: com.example.domain.core.security.SecurityPolicy
    ): Outcome<Unit, com.example.domain.core.security.SecurityFailure> = Outcome.Success(Unit)
}

/** Shared factory: a fully wired pipeline with fakes + REAL patch engine. */
object GovernedPipelineFactory {

    fun build(
        storage: FakeWorkspaceStorage = FakeWorkspaceStorage(),
        approvalStore: HumanApprovalStorePort = InMemoryHumanApprovalStore(),
        audit: RecordingAuditSink = RecordingAuditSink(),
        security: SecurityGuardPort = com.example.application.security.SecurityGuardService(),
        budget: ScriptableBudgetGate = ScriptableBudgetGate {
            BudgetAuthorizationOutcome(
                com.example.domain.core.security.governance.BudgetAuthorizationVerdict.ALLOWED,
                "لا ميزانية مُعرّفة — سماح."
            )
        },
        rateLimit: ScriptableRateLimit = ScriptableRateLimit(),
        sandbox: SandboxLifecycleService = SandboxLifecycleService(
            hostIsolationLevel = com.example.domain.core.runtime.IsolationLevel.APP_SANDBOX_BEST_EFFORT
        ),
        workspaceRoots: MutableMap<Long, String> = mutableMapOf(1L to "/data/workspaces/proj_1")
    ): PipelineParts {
        val gate = HumanApprovalGate(store = approvalStore)
        val admission = AdmissionControlService(
            toolDeclarations = ToolDeclarationResolver { name -> CodingToolchainService.declarations[name] },
            principalAuthorization = FakePrincipalAuthorization(),
            securityGuard = security,
            budgetAuthorization = budget,
            rateLimitCheck = rateLimit,
            approvalGate = gate,
            sandboxService = sandbox,
            auditSink = audit,
            workspaceRootResolver = { projectId -> workspaceRoots[projectId] }
        )
        return PipelineParts(admission, gate, storage, audit, budget, rateLimit, sandbox, workspaceRoots)
    }

    data class PipelineParts(
        val admission: AdmissionControlService,
        val approvalGate: HumanApprovalGate,
        val storage: FakeWorkspaceStorage,
        val audit: RecordingAuditSink,
        val budget: ScriptableBudgetGate,
        val rateLimit: ScriptableRateLimit,
        val sandbox: SandboxLifecycleService,
        val workspaceRoots: MutableMap<Long, String>
    )
}
