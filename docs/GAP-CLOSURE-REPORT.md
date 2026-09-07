# AI-V0 — تقرير إغلاق الفجوات الموحّد (Gap-Closure v2)

**الفرع المصدر:** `fix/gap-closure-v2` (من `main` عند `7f16fe8`)
**وسيلة الدمج:** Pull Request إلى `main` — تم التحقق من الدمج والبناء والاختبارات قبل التسليم.

---

## الخلاصة التنفيذية

أُغلقت الفجوات الثمانية الحرجة (P0) بالكامل، وأُغلقت الفجوات المعمارية العشرون (P1) عبر ثلاث ركائز: **Canonical Execution Kernel** (سياق تنفيذ قانوني موحّد + هوية مستقرة + سجل idempotency)، **Single Source of Truth** (سجل وكلاء دائم + توحيد الحوكمة)، و**Honest Surfaces** (كل مسار فشل يُخبر الحقيقة بدل ابتلاعها). ديون الـLegacy (P2) عُزلت وجُمّدت باختبارات معمارية تمنع أي نمو جديد (استراتيجية «عزل وتأمين» المتفق عليها — دون حذف جسري).

**النتائج:**
- `assembleDebug`: BUILD SUCCESSFUL (APK ~98.7MB).
- `testDebugUnitTest`: **352+ اختباراً ناجحاً** (منها ~40 اختبار إغلاق جديد موجّه لسلوك كل إصلاح).
- Room DB: الترقية v10 → v11 (إضافة `tasks.executionContextJson` + جدولا `action_intents` و`agent_definitions`).

---

## P0 — المشاكل الحرجة (مغلقة بالكامل)

| # | المشكلة | الإصلاح | الدليل |
|---|---------|---------|--------|
| P0-01 | ExecutionHost عالمي بمهمة واحدة يلغي السابقة | أعيدت كتابته كـ**سجل تنفيذات متعدد** مفتوح بالمفتاح (taskId): `launch(key)` لا يلغي غيره، `cancel(key)` يستهدف تنفيذاً واحداً، `activeExecutions` سجل حي | `ExecutionHostMultiExecutionTest` |
| P0-02 | Workspace Context غير مثبت داخل التنفيذ | **CanonicalExecutionContext** يُنشأ مرة واحدة عند البدء + عنصر coroutine `ExecutionScope` يثبّت الذاكرة/الـRAG + `Started.workspaceId` يربط الـtelemetry بالتنفيذ مهما تبدّلت المساحة النشطة | `OrchestratorKernelTest`, `CanonicalExecutionContextTest` |
| P0-03 | fail-open عند غياب Workspace (`"default"`) | `requireActiveWorkspaceId()` أصبح **fail-closed** (`NoActiveWorkspaceStateException`) + `activeWorkspaceIdOrNull()` (null = غير منسوب بصدق) + `awaitActiveWorkspaceId()` للتهيئة + مسار التنفيذ يفشل بأمان بـ`WORKSPACE_CONTEXT_REQUIRED` | `WorkspaceRuntimeServiceTest`, `OrchestratorKernelTest` |
| P0-04 | Workspace جديدة تنتهي لمشروع fallback `1L` | كل مساحة جديدة تنشئ **مشروع sandbox خاصاً بها** (`ProjectDao`) + `FileSystemTool`/`ScaffolderSkill` تست两人的 **projectIdProvider** لكل استدعاء (لا مشاريع مشتركة صامتة) + الواجهة تعرض 0 = «بدون مشروع» بصدق | `FileSystemToolScopeTest`, `WorkspaceRuntimeServiceTest` |
| P0-05 | Recovery لا يضمن Exactly-Once (checkpoint يُبتلع فشله) | فشل حفظ الـcheckpoint أصبح **فادحاً وأميناً** (`CHECKPOINT_PERSISTENCE_FAILED` — يتوقف التنفيذ لأن استئنافه لم يعد مضموناً) | `AgentOrchestrator` (executeClosedLoop) |
| P0-06 | لا يوجد Action Idempotency Protocol | **سجل النوايا الدائم** (`action_intents`): نية → أثر → اكتمال. الاستئناف **يعيد تشغيل** الإجراء المكتمل من بصمته المخزنة بدل إعادة تنفيذه | `ActionIdempotencyServiceTest` |
| P0-07 | SKIPPED لا يمنع نجاح الـWorkflow | خطوة محجوبة/تبعية معلّقة → **فشل صريح** `BLOCKED_STEPS`/`DANGLING_DEPENDENCY` + التحقق من التبعيات المعلقة في `validatePlan` | `WorkflowEngineGapClosureTest` |
| P0-08 | Decision Intelligence محاكاة وليس المحرك الحقيقي | `evaluatePolicy` يستدعي **`CbrMdpEngine.evaluateAndSelectAction` الحقيقي** + `lookahead` يقرأ خلايا Q فعلية (`getQEntry`) ويعيد 0f أميناً عند غياب التعلم | `DecisionIntelligenceRealEngineTest` |

## P1 — المعمارية العميقة (مغلقة عبر ثلاث ركائز)

| # | الإصلاح |
|---|---------|
| P1-01 | `CanonicalExecutionContext` (domain خالص + codec) — الوحدة الدائمة التي تربط executionId/taskId/workspaceId/projectId/agentId/agentRole/modelId/attempt |
| P1-02 | `TaskEntity.executionContextJson` — الـTask aggregate يحمل هوية السياق كاملة وتُستعاد عند الـresume |
| P1-03 | **executionId مستقر عبر الاستئناف** (`nextAttempt()` يرفع العداد فقط) — الاختبار يثبت تطابق المعرف بين المحاولتين |
| P1-04 | الاستئناف **يرفض الترحيل الصامت للوكيل** (`AGENT_UNAVAILABLE` / `AGENT_IDENTITY_MISMATCH`) |
| P1-05 | **DAG Scheduler حقيقي**: الفروع المستقلة تعمل بالتوازي (Semaphore بحد `maxConcurrentSteps`) لوضعي DAG/FAN-OUT؛ SEQUENTIAL يبقى ترتيبياً — اختبار يتأكد من تراكب التنفيذ |
| P1-06 | فشل persistence الـWorkflow **يُتتبع ويُخفض النتيجة بصدق** (`WORKFLOW_PERSISTENCE_DEGRADED`) + إصلاح خلل `complete()` الذي كان يكتب COMPLETED للحالتين |
| P1-07 | التوكنز من **المحاسبة الفعلية** (`executeTaskDetailed` → نفس أرقام الـledger) بدل `length/4` |
| P1-08 | **سجل الوكلاء القانوني الدائم** (`agent_definitions` + `AgentRegistryService` + `CanonicalAgentCatalog`): الكتالوج الذي تراه الواجهة هو الذي ينفذ — الوكلاء الستة (3 runtime + 3 UI سابقاً) صاروا فضاء هوية واحداً |
| P1-09 | دورة حياة الوكلاء دائمة: versioning رتيب (configuration lineage) + البقاء عبر إعادة التشغيل |
| P1-10 | **Agent Builder**: نموذج «بناء وكيل جديد» في Agent Studio (اسم/دور/وصف/prompt) → حفظ دائم → قابل للتنفيذ فوراً |
| P1-11/P1-12 | جيلَي الـProvider: **تجميد الجزيرة القديمة** (اختبار معماري) + توثيق الحد الدلالي بين `ResourceType`-الرسم البياني و`ResourceType`-الوقت التشغيلي |
| P1-13 | **تثبيت النموذج**: `assignedModelId` يُحترم حرفياً في توليد المرشحين؛ غيابه → `PINNED_MODEL_UNAVAILABLE` صريحاً (لا استبدال تلقائي) |
| P1-14 | فشل حفظ الـRAG **لم يعد يُبتلع**: `KnowledgePersistenceState` (PERSISTED/PENDING/FAILED) + إعادة محاولة واحدة + تشخيص يظهر للمستخدم |
| P1-15 | **حارس أجيال التحميل** يمنع النتيجة القديمة (A) من الكتابة فوق مجموعة العمل الأحدث (B) |
| P1-16 | التصنيف المعرفي (`CognitiveMemoryType`) موثّق كسلطة + الـlegacy عازل خلف mapper واحد + اختبار round-trip بلا خسائر |
| P1-17 | **حلقة الاكتساب مكتملة وقابلة للتشغيل**: تدقيق أمني (`recordSecurityAudit` — كان مستحيلاً سابقاً فتُقفل بوابة الحوكمة كلها!) + موافقة + دمج + تحقق + تسجيل + **قياس** (تدفق أدلة الرادار نفسه) + **تقاعد** (`retireCapability`) — أزرار كاملة في مرصد التطور |
| P1-18 | **UnifiedActivityFeedScreen ضمن التنقل الرئيسي** (تبويب «النشاط») — القدرات الخلفية صارت مرئية |
| P1-19 | قاع التنقل **context-centric**: الاستوديو/النشاط/المعرفة/الملفات + المزيد (المزودون انتقلوا للأدوات الثانوية) |
| P1-20 | خدمة الـForeground: **جامع واحد** (لا تسريب collectors) + إشعار يعكس عدد التنفيذات الحية + إيقاف عند تفريغ السجل |

## P2 — الديون القديمة (عُزلت وجُمّدت)

استراتيجية «عزل وتأمين»: لا حذف جسري، بل **اختبارات معمارية تجمّد النمو**:

- **P2-01**: لا يوجد أي استيراد لـ`domain.models.*` في كود التشغيل (تجميد كامل) — `LegacyIsolationTest`.
- **P2-02**: استخدام `ProviderConfigEntity`/`providerConfigDao` محصور في مواقع الإعلان + `RoomProviderRepositoryAdapter` (الجسر القديم المجمّد).
- **P2-03**: نظام Quota القديم معزول في ملف إعلانه (`ResilienceModels.kt`).
- **P2-04**: `maxCostEstimatedUsd`/`estimatedCostUsd` غير مستهلكة (لا قراءة منها في كود التشغيل).
- **P2-05**: `maxTokenBudget`-overload للـRAG **deprecated** والمستدعون هاجروا للمسار الجديد؛ السلطة في `RagIntelligenceService`.
- **P2-06**: مسار الـlegacy observation موثّق كسلوك توافقي معزول (D-3 لا يزال يغذي المحرك الحقيقي).
- **P2-07**: الاختبارات المتبقية المرجعية للـlegacy هي سلاسل توثيقية (register) — لا اختبار يبني النماذج القديمة.

---

## ترقيمة قاعدة البيانات (v10 → v11)

```sql
ALTER TABLE tasks ADD COLUMN executionContextJson TEXT;
CREATE TABLE action_intents (...PRIMARY KEY(executionId, actionKey));  -- سجل الـidempotency
CREATE TABLE agent_definitions (...);                                   -- سجل الوكلاء القانوني
```
سلسلة الهجرات كاملة من v1 — الترقية آمنة لكل التثبيتات القائمة.

## الاختبارات الجديدة (موجّهة لسلوك الإصلاحات)

`ExecutionHostMultiExecutionTest` · `CanonicalExecutionContextTest` · `ActionIdempotencyServiceTest` · `OrchestratorKernelTest` (fail-closed + هوية الاستئناف + رفض الترحيل) · `WorkflowEngineGapClosureTest` (SKIPPED/DAG التوازي/persistence/التوكنز) · `DecisionIntelligenceRealEngineTest` · `RagPersistenceHonestyTest` (صدق الحفظ + حارس الأجيال) · `FileSystemToolScopeTest` · `AgentRegistryServiceTest` · `LegacyIsolationTest` (تجميد P2) · `MemoryTaxonomyRoundTripTest` + تحديث `WorkspaceRuntimeServiceTest`/`GovernancePersistenceTest` لعقود الإصلاح الجديدة.

## حدود نطاق هذه الجولة (بصدق)

- **الإدماج الفعلي لقدرة خارجية مكتشفة** (P1-17 INTEGRATED) يظل قرار مشغّل عبر شاشة الملحقات — النظام لا يدّعي تثبيتاً آلياً غير موجود.
- **إزالة جسدية** للـLegacy (P2) مؤجلة عمداً لجولة لاحقة حسب الاستراتيجية المختارة.
- `assembleRelease` يتطلب أسرار التوقيع من البيئة (كما هو مصمم) — التحقق هنا غطّى `assembleDebug` + كل اختبارات الوحدة.
