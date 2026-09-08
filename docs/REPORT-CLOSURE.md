# تقرير إغلاق الفجوات — Report Closure (v13)

> هذا المستند يغلق رسمياً فجوات التقرير التنفيذي الأخير على الـ main (b7775ae).
> كل بند أدناه: الحكم السابق ← الإصلاح الجذري ← الدليل.

## 1. البنية المعمارية (Domain Boundaries)

| البند | الحكم السابق | الإغلاق |
|---|---|---|
| domain → application violation | NOT FIXED | `ResourceCapabilityGraph` لم يعد يستورد `ResourceRegistryService`؛ جذر التركيب (AppContainer/ComponentRegistry) يمرر مورد سجلات نصي `ResourceCapabilityGraph { registry.listResources() }`. |
| domain → infrastructure violation | NOT FIXED | `CaseBase` لم يستورد `DecisionCaseDao/Entity`؛ المطاوعة عبر منفذ domain مملوك `DecisionCaseStore` + تنفيذ Room `RoomDecisionCaseStore` (نفس نمط `MdpLearningStore`). |
| persistence semantics (silent swallow) | غير مكتملة | إخفاقات الحفظ تُحصى صادقة (`persistenceFailureCount` + `lastPersistenceError`) وتُعرض، لا تُبتلع. |

**الدليل:** `grep -rn "import com.example.(application|infrastructure)" domain/` → 0 نتائج. اختبار `CaseBaseHonestPersistenceTest`.

## 2. سياسة القبول بالقدرات (Capability Admission)

- الحكم السابق: `any` بدل `containsAll` + `matchesRequired || matchesOptional` — مرشح بنصف المتطلبات يُقبل، و optional يعوّض required.
- **الإغلاق:** سياسة واحدة موحدة في `ResourceCapabilityGraph`:
  - `required` = بوابة ALL صارمة (`containsAll`).
  - `optional` لا يقبل أبداً — للترتيب فقط.
  - `admittedByTypeRequirements()`: إسقاط المتطلبات على نطاق قدرات نوع المورد قبل البوابة (مهمة برمج تتطلب TOOL_EXECUTION من طرف الأداة لا من طرف LLM).
- `DecisionService` يفوّض للسياسة نفسها (لا فلتر خاصاً به بعد اليوم).
- **الدليل:** `CapabilityAdmissionPolicyTest`.

## 3. مسار legacy للمزودين

- الحكم السابق: `ProviderAdapterFactory` ما زال يمكن أن يرد `LocalDeterministicEmbeddingAdapter` لأي flavor تضمين.
- **الإغلاق:** المسار مُحذف جذرياً (`ProviderAdapterFactory.kt` + `infrastructure/llm/custom/OpenAiCompatibleAdapter.kt`) — لا مستدعين، لا ازدواج. المسار القانوني الوحيد: `ProtocolAdapterFactory` → Resource Registry → RuntimeAdapterResolver.

## 4. الجلسات الدائمة (Durable Sessions) — كانت محذوفة بلا بديل

- **الإغلاق (DB v13):** `chat_sessions` + `chat_turns` — وُلدت مرتبطة بمساحة العمل:
  - متصفح جلسات + استرجاع بعد إعادة التشغيل + استئناف المحادثة (التاريخ يُعاد حقنه LLM history).
  - مجموعات (دورات/توكنات) تُحدَّث ضمن معاملة واحدة مع الدورة (`withTransaction`).
  - ربط النموذج الدقيق يُحفظ على الجلسة.
- **الدليل:** `ConversationSessionDurabilityTest` (موت عملية بقاعدة ملفية: الجلسة + التاريخ الكامل ينجوان) + `DurableWorkspaceE2ETest` على الجهاز.

## 5. Quick Chat + منتقي النموذج

- الحكم السابق: `val agent = current.activeAgent ?: return` — المحادثة مشروطة بوكيل.
- **الإغلاق:**
  - وضعان: `QUICK_CHAT` (مستقل عن الوكلاء؛ يرتبط بالنموذج المختار) و `AGENT`.
  - `agent_quick_chat` وكيل قانوني seeded في السجل الدائم — التنفيذ يمر بنفس النواة المحكومة (سلطة تنفيذ واحدة، لا مسار توليد موازٍ غير محكوم).
  - منتقي النموذج في الاستوديو يعرض الموارد المفعلة ويحوّلها إلى `assignedModelId` (ربط دقيق يُحترم في طبقة القرار PINNED_MODEL).
  - فشل وضع الوكيل بلا وكيل = رسالة صريحة لا return صامت.
- **الدليل:** UI `ChatModeAndSessionsBar/ModelPickerRow/SessionBrowserDialog`؛ تجربة E2E للتجهيز.

## 6. مكتبة خطط العمل (Workflow Library) — كان التنفيذ دائماً والتعريف لا

- **الإغلاق:** `workflow_definitions` (DB v13) + `WorkflowLibraryService`:
  - حفظ → قائمة → تحميل → تحرير (ب bump إصدار) → نسخ → تشغيل (مسجل) → حذف.
  - نفس التسلسل lossless للتنفيذ (schema v3 يضيف `assignedAgentId/assignedModelId`).
  - حالة البناء انتقلت من Compose `remember` إلى ViewModel — التعريف أصل قابل لإعادة التحرير فعلاً.
- **الدليل:** `WorkflowLibraryDurabilityTest`.

## 7. ربط الوكلاء القانونيين لخطوات العمل (Canonical Agent Binding)

- الحكم السابق: `AgentId("workflow_agent_${step.id}")` اصطناعي لكل خطوة.
- **الإغلاق:** محرك الـ workflow يحل كل خطة عبر `agentResolver`:
  1. `step.assignedAgentId` → الوكيل الدائم الذي اختاره المستخدم/المكتبة (بربط الدور).
  2. مطابقة دور من السجل الدائم (scope المساحة).
  3. تنشيط (materialize) وكيل دور دائم وتسجيله — السجل يبقى السلطة الوحيدة.
  - الوكيل الاصطناعي احتياط أخير موثق لاختبارات JVM الخالصة فقط.
- **الدليل:** `WorkflowCanonicalAgentBindingTest`.

## 8. الاستئناف (Resume)

- **الإغلاق:** `executePlan(completedStepIds)` يزرع الخطوات المكتملة كـ COMPLETED (مع مخرجاتها المسجلة كسياق) ولا يعيد تنفيذها أبداً؛ بطاقة "تنفيذات قابلة للاستئناف" في شاشة المهام.

## 9. توصيل النشاط الموحد (Activity Trace Wiring)

- الحكم السابق: `traceForExecution("")` — معرف فارغ لا يطابق شيئاً.
- **الإغلاق:** التقاط `executionId` الحقيقي من `ExecutionEvent.Started` في `activeExecutionId` ثم `flatMapLatest`:
  - تنفيذ جارٍ → تيار التنفيذ نفسه.
  - لا تنفيذ → نافذة أحدث الآثار `recentTraceNodes(50)` (إضافة للمنفذ + Room).
- **الدليل:** السلك الجديد في `MainViewModel` + `ExecutionTraceDao.recent` الموجودة أصلاً أصبحت مستهلكة فعلياً.

## 10. الأمان الشبكي (Fail-Closed)

- الحكم السابق: `networkMonitorProvider?.isNetworkAvailable?.value ?: true` (fail-open).
- **الإغلاق:** `?: false` في كل المواضع — غياب المراقب = غير متصل (السياسات تعمل بصدق).
- **إضافة جذرية — فرض الخروج الشبكي (Egress Enforcement):** `EgressControl` (سجل سياسة + معترض OkHttp) مثبت على عملاء MCP/LLM/البحث: سياسة OFFLINE لمساحة العمل ترفض أي اتصال قبل فتح المقبس (فشل مغلق)، وتحظر جلسات sandbox محددة الخروج حتى تحت HYBRID.
- **الدليل:** `EgressControlTest`.

## 11. الوكلاء الدائمون بدقة كاملة

- الحكم السابق: الحفظ lossy (أهداف/سياسات تضيع).
- **الإغلاق:** أعمدة `goalsJson/networkRequirement/locality/authorityLevel` في `agent_definitions` + تعيين كامل في `AgentRegistryService`.

## 12. مستكشف موحد

- **الإغلاق:** شاشة `WorkspaceExplorerScreen` + مسار `explorer`: الوكلاء/النماذج/الموارد/الأدوات/المعرفة/الملفات/الخطط/الجلسات — بأعداد من الحقيقة الخلفية وربط عميق.

## 13. CI + الإثبات على الجهاز

- توحيد JDK 21 في كل الـ workflows (كان 17/21 مختلطاً).
- `e2e-device.yml` جديد: `connectedDebugAndroidTest` على محاكي API 34 (كان CI يكتفي بـ assemble).
- `DurableWorkspaceE2ETest`: فتح v13، بقاء الجلسات بعد إعادة الفتح، مكتبة الـ workflow، تجهيز الوكلاء.

## ما لم يُغلق (ولماذا — بصدق)

- **TRUE process isolation لـ run_code/run_tests:** يبقى الرفض الصريح (SANDBOX_INSUFFICIENT_ISOLATION) بدل تنفيذ غير معزول — العزل الحقيقي يحتاج عملية معزولة/حاوية لا يوفرها التشغيل داخل التطبيق. عوّضنا بالفرض الشبكي الحقيقي (EgressControl) فوق سياسة المسارات القائمة.
- **منظومة إضافات (Plugins) بـ runtime حقيقي:** تبقى الإضافة الفارغة الصادقة (حذف الواجهة الكاذبة كان قراراً صحيحاً)؛ MCP/المهارات حقيقية.

## البناء والاختبار

- `:app:assembleDebug` ✅
- `:app:testDebugUnitTest` → **411 اختباراً، 0 فشل** ✅
- `:app:compileDebugAndroidTestKotlin` ✅ (يعمل على المحاكي في CI)
