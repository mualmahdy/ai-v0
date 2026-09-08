# P0 Convergence & Hardening Report

> التقرير التزامي لإصلاح نقاط الخلل المذكورة في "الخطوة 12 — التقرير التركيبي النهائي"
> (P0 Correctness + Architectural Convergence + Remnant Cleanup)

**الفرع:** `fix/p0-convergence-hardening` (أساس: `main` @ `b83b28f`)
**الحالة:** تم التحقق من البناء والاختبارات قبل التسليم.

---

## 1. ما الذي أُصلح (مطابقة بنود التقرير)

### P0-أ — توحيد ملكية Workspace (§6، §19-أ)

| الخلل كما وصفه التقرير | الإصلاح |
|---|---|
| "بعض المكونات ما زالت تحمل إرث `projectId = 1L`" | أُزيلت كل الدوال الافتراضية `defaultProjectId = 1L` من `FileSystemTool` و`CleanArchitectureScaffolderSkill` و`MultiSourceSearchAdapter` و`ProviderAdapterFactory` — لم يعد يوجد أي مسار إنشاء يمكن أن يعيد تسليح المشروع المشترك (fail-closed بـ `PROJECT_CONTEXT_REQUIRED`). |
| "بل ظهر هذا أيضاً داخل MCP workspace summary" | جسر MCP المحلي (`workspace_summary`) كان يقرأ `listFiles(1L)` حرفياً — يقرأ الآن مشروع الـ workspace النشط (المثبّت في `ExecutionScope` أولاً)، ويفشل بصدق عند عدم وجود مشروع مرتبط. |
| "Project ليس workspace-scoped بصورة كاملة" | عمود `workspaceId` صريح في جدول `projects` + backfill في MIGRATION_11_TO_12 من عمود الجسر `lastActiveProjectId`. |
| "Resource ليس workspace-owned" | تم توثيق ملكية الموارد عبر سلسلة Workspace → Project (sandbox root) — وكل استدعاء يمر عبر المشروع المملوك، بلا مرجع ضمني. |
| "بعض الجداول تعتمد فقط على projectId" | جدول `sessions` كان بقايا ميتة (لا قارئ/كاتب إنتاجي على الإطلاق في `SessionRepositoryPort`) — أُزيل بالكامل مع الـ port والكيان والـ DAO (DROP TABLE). |
| الـ bootstrap الافتراضي | الـ workspace الافتراضي ينشئ الآن مشروع sandbox خاصاً به عبر `ProjectDao` (بدل الإشارة الضمنية إلى 1L). المرجع القديم في التركيبات القائمة يُحوَّل إلى صف مملوك حقيقي في الـ migration. |
| تثبيت التنفيذ | `CanonicalExecutionContext.projectId` كان دائماً `null` — يُثبَّت الآن من `projectIdProvider` عند الإطلاق ويُمرَّر داخل `ExecutionScope`، بحيث لا يستهدف تبديل الـ workspace منتصف التنفيذ عمليات الملفات إلى sandbox آخر (مُثبَّت باختبار). |

### P0-ب — متانة Workflow (§5، §19-ب)

الخلل: `serializePlan()` كان يحفظ `(id, description, agentRole)` فقط و`deserializePlan()` يعيد `steps = emptyList()` — الخطة تُفقد بنيتها عند موت العملية.

الإصلاح: تسلسل **بلا خسارة** لكامل `WorkflowPlan` وكل حقول `StepNode` (taskId, requirements الكاملة مع AcceptanceCriterion وdependencies وexpectedOutputs وevidenceRequirements وstatus وoutputSummary وdurationMs) + الـ goal وexecutionMode وworkflowId. الصفوف القديمة (v11) تُستعاد بأمان مع الحفاظ على ما حُفظ فعلاً (خطوات حقيقية وليست خطة فارغة).

### P0-ج — صحة Telemetry (§12، §19-ج)

الخلل: `recordBatch()` كان يكتب الـ entities ثم يستدعي `record()` لكل sample — فيُكتب كل عينة **مرتين** في `metric_events`، ويُعدّ مضاعفاً في كل ما يبنى عليها (analytics / economics / adaptive learning / radar).

الإصلاح: مسارَا `record()` و`recordBatch()` يكتبان الآن **مرة واحدة بالضبط** لكل عينة (`persistSamples` موحّد + `updateCaches` مشترك). مُثبَّت باختبارات على قاعدة Room حقيقية (عدّ الصفوف + aggregate buckets).

### P0-د/هـ — اختبارات Process Death وWorkspace Switch (§19-د، §19-هـ)

- `ProcessDeathRecoveryTest`: قاعدة Room **ملفية** تُغلق فعلياً وتُعاد فتحها (موت عملية حقيقي عند طبقة الثبات) — يثبت استعادة الخطة الكاملة + تقدم الخطوات، وبيانات RAG (metadata/العناوين/mimeType/مصداقية retrievalSource)، وخلايا التعلم الواعية بالمورد.
- `WorkspaceSwitchDuringExecutionTest`: يثبت أن telemetry والعمليات الملفية تبقى مرتبطة بالـ workspace/project **المثبّت** أثناء التنفيذ حتى بعد تبديل الـ workspace النشط منتصف العملية.

### §7 — RAG Metadata Durability

الخلل: metadata الـ chunks (embeddingResourceId/embeddingSemantic) كانت تُسقط عند الكتابة وتُعاد فارغة عند التحميل — ينهار حد التوافق الدلالي (compatibility boundary) بعد إعادة التشغيل، ويفقد reranking إشارات authority/recency، وmetadata filters لا تطابق شيئاً.

الإصلاح: عمود `metadataJson` في `document_chunks` + `mimeType` في `knowledge_documents` + إعادة بناء `documentTitle` من خريطة المستندات + مصداقية `retrievalSource` (lexical fallback لم يعد يُوسم SEMANTIC خطأً). مسار `RagIntelligenceService` يستعيد metadata الأصلية أيضاً.

### §4 — Decision Learning resource-aware (من أهم 5 تغييرات)

الخلل: مفتاح التعلم = `region + actionType` — كل الموارد/المزودين/النماذج تنهار في خلية واحدة، فيتعلم النظام "SEARCH جيد" لكن ليس "SEARCH عبر المورد X أفضل من Y".

الإصلاح: محور مورد صريح (`resourceKey`) من `DecisionRecord.selectedResourceId` (يفضَّل على targetId الخام) داخل مفتاح الخلية والجدول (PK جديد: regionKey + resourceKey + actionType — migration مع تحويل الصفوف القديمة إلى `R:none` حيث تستمر الأفعال عديمة المورد في التعلم). كذلك أصبح الـ state يميز **محور القدرة** (capabilityCoverageRatio bucket) — الإشارة التي كانت تُحسب ثم تُهمَل.

### التنظيف المعماري (بقايا فك الارتباط)

- حذف: `SessionRepositoryPort` + `WorkspaceSessionInfo` + `SessionEntity` + `SessionDao` + جدول `sessions` (بقايا ميتة بمسار مشروعي غير workspace-scoped مع bootstrap ضمني لـ 1L).
- حذف: `getActiveProject()` الـ 1L bootstrap في `SandboxWorkspaceStorageAdapter` + `isDefault = id == 1L`.
- حذف: عمود `knowledge_documents.projectId` الميت + فهرسه + استعلام `getDocumentsForProject` غير المستخدم.
- إزالة import الـ `SessionRepositoryPort` الميت من `MainViewModel`.

## 2. MIGRATION_11_TO_12

migration واحدة تسلسلية (لا خطوة مفقودة في السلسلة 1→12):

1. `projects`: `+workspaceId` + backfill من الجسر + تجسيد المرجع الضمني 1L كصف مملوك حقيقي (إصلاح بيانات شرطي).
2. `DROP TABLE sessions`.
3. إعادة بناء `knowledge_documents` (بدون projectId، + mimeType) مع حفظ البيانات.
4. `document_chunks`: `+metadataJson`.
5. إعادة بناء `mdp_q_values` بمفتاح `resourceKey` مع حفظ البيانات على `R:none`.

كل خطوة موثقة باختبار مباشر في `Migration11to12Test`.

## 3. الاختبارات المضافة (حزمة `com.example.convergence`)

| ملف | يثبت |
|---|---|
| `WorkflowPlanDurabilityTest` | round-trip بلا خسارة لكل حقول الخطة + تتبع الخطوات المكتملة + تدهور صادق للصفوف القديمة |
| `TelemetryNoDoubleCountTest` | كتابة مرة واحدة بالضبط (record وrecordBatch) على Room حقيقية |
| `ProcessDeathRecoveryTest` | استرداد موت العملية: workflow كامل، RAG metadata، خلايا MDP per-resource |
| `WorkspaceSwitchDuringExecutionTest` | التثبيت أثناء التنفيذ: telemetry + عمليات الملفات لا تُعاد استهدافها |
| `Migration11to12Test` | كل بنود migration على SQLite خام |
| `ResourceAwareDecisionLearningTest` | خلايا منفصلة لكل مورد، لا تسرب بين الموارد، محور R:none، محور القدرة، البقاء عبر store |
| تحديث `WorkspaceRuntimeServiceTest` | العقد الجديد: لا 1L ضمنياً؛ مشروع مملوك حقيقي عند توفر ProjectDao |

## 4. ما لم يشمله هذا التسليم (بنود product-surface من P1)

التقرير نفسه يقرر في P0: "يجب ألا يضاف فيها تقريباً أي Feature جديد" — لذلك لم تُنفَّذ في هذا الفرع ميزات الطبقة المنتَجة (Quick Chat / Sessions UX / Model Picker / Unified Explorer) ولا Sandbox على مستوى نظام التشغيل ولا predictive radar/economics؛ فهي مسار P1/P2 تلو اكتمال هذا الأساس.
