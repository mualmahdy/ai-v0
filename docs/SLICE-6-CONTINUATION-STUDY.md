# دراسة شاملة للاستمرار — AI-V0 Ultimate / مسار بناء الواجهة الجديدة

> هذه الدراسة جُمعت من قراءة كاملة للمستودع (رأس `2429fb8` — Phase 10 /
> الشريحة 5) لأجل الاستمرار في مسار إعادة تصميم الواجهة (Design Closure
> 2026 — ADR-6 خيار ج). المرجع الحاكم للمسار: `docs/UI-REDESIGN-TRACK.md`.
> كل رقم أدناه مُتحقَّق منه من الكود مباشرة، لا من الذاكرة.

---

## 1. هوية التطبيق والتقنيات

**AI-V0 Ultimate** — تطبيق Android **أصلي** (وليس ويب): مساحة عمل ذكية
متعددة الوكلاء ذاتية الحكم، عربية أولاً (RTL مثبَّت في `MainActivity` بقرار
D-1: عربي فقط، بلا بنية i18n — ~600 حرفية داخل شاشات Compose).

| البند | القيمة (المتحقق منها) |
|---|---|
| اللغة/الواجهة | Kotlin 2.2.10 + Jetpack Compose (BOM 2024.09) + Material 3 |
| البنية | Clean Architecture صارمة: `domain` (core+ports) / `application` (services+usecases) / `infrastructure` / `presentation` |
| قاعدة البيانات | Room 2.7.0 — `AppDatabase.SCHEMA_VERSION = 17` (مخططات 16/17 مسجلة في `app/schemas/`) |
| التنقل | `androidx.navigation` NavHost — 5 وجهات رئيسية + 8 ثانوية (`WorkspaceRoutes`) |
| الاختبارات | **765 اختباراً / 114 suites / 0 فشل** (Robolectric 4.16.1 JVM) + Roborazzi 1.59.0 (مُفعَّل في `app/build.gradle.kts` مع `GreetingScreenshotTest` كنمط) |
| CI | `android.yml` (apk-debug — ناجح منذ الإصلاح)، `e2e-device.yml` (جذران شُخِّصا وأُصلِحا؛ الحكم النهائي معلق على التشغيلة 17)، `release.yml` (AAB موقّع بشرط KEYSTORE_PATH) |
| البناء | AGP 9.1.1، Gradle 9.3.1، compileSdk 36، minSdk 24، desugaring مفعّل |
| الحجم الحالي | `assembleDebug` ≈ 100.27 MB (مُتحقَّق منه في Phase 10) |

**القدرات الخلفية الحقيقية** (كلها موصولة بإقلاع فعلي في `AppContainer.bootstrapRuntime()`):
مزوّدو LLM معمّمون (Gemini + OpenAI-compatible مع اكتشاف ديناميكي)،
Tool calling حقيقي (SSE)، تفويض متعدد الوكلاء بميزانيات مقتطعة، تنفيذ
دائم قابل للاستئناف (durable execution + checkpoints + idempotency)،
RAG هجين محلي (تطبيع عربي + ONNX دلالي محلي)، ذاكرة طويلة الأمد
بتضاؤل/تجميع، محرك قرار CBR-MDP (حالات + Q-learning)، رادار قدرات
وتطور، حوكمة كاملة (بوابات قبول، موافقات بشرية دائمة التخزين، كسارات
دائرة persistent)، اقتصاد (ميزانيات/قيود معدل)، EgressControl مغلق
افتراضياً، قبو مفاتيح مشفّر (S-2)، ونقل/تصدير مشاريع كاملة.

---

## 2. الوضع الحالي لمسار الواجهة الجديدة (بعد الشريحة 5)

المسار: إعادة التصميم **متزامنة مع تفكيك ADR-6 (خيار ج)** — لا إعادة
تصميم فوق MainViewModel دون تفكيك، ولا تفكيك كامل مسبق. لكل شريحة:
ViewModel ميزة مستقل + سلوكه المختبر (GAP-21) + إعادة التصميم البصري
للشاشات التي تملكها.

| الشريحة | المرحلة | ما سُلِّم | الحالة |
|---|---|---|---|
| 1 | Phase 5 | FilesViewModel + SettingsViewModel + إصلاح android.yml | ✅ |
| 2 | Phase 6 | StudioViewModel + SessionsViewModel + ناقل StudioSignal | ✅ |
| 3 | Phase 8 | KnowledgeViewModel | ✅ |
| 4 | Phase 9 | GovernanceViewModel (+ إصلاح flatMapLatest للرادار) | ✅ |
| 5 | Phase 10 | ProvidersViewModel (+ إصلاح صدق S-2) | ✅ |
| **6** | — | **RadarViewModel / Decision / Tasks المتبقي + مصفوفة Roborazzi** | ⬅ **التالية** |
| 7 | — | قرار i18n (D-1) | لاحق |

**تقلّص MainViewModel**: 2293 → 1730 → 1630 → 1385 → 1384 → **1052 سطراً**
(رأس `2429fb8`). عدد ViewModels الميزة المُنشأة: 10 (Main + Tasks + Files +
Settings + Studio + Sessions + Knowledge + Governance + Providers —
والـ Tasks أُنشئت مبكراً في GAP-11).

**النمط الثابت المستقر عبر الشرائح الخمس** (يجب اتباعه حرفياً):
1. الحالة تغادر `UiState` المشتركة إلى `XxxUiState` خاصة + قناتا خطأ
   وبانر خاصتين بالميزة.
2. السلوك يُنقل **حرفياً** — الإصلاحات المتعمدة الوحيدة المسموح بها هي
   من عائلة «الصدق الظاهري» وبسابقة موثقة (مثل flatMapLatest لإصلاح
   الجامعات المتراكمة، أو توجيه diagnosticMessage إلى الخانة التي تقرأها
   الواجهة فعلاً).
3. MainViewModel يفقد التبعيات كاملة (لا يبقى منها شيء في المشترك).
4. الشاشات تُركَّب على مالك الميزة (owner-VM composition)، أو تستقبل
   قيمة+lambda إن كانت الشاشة ميزة أخرى (نمط التفويض).
5. قناة خطأ الميزة تُعرض في snackbar العام من مصدر حقيقتها الخاص،
   والبانر التشخيصي محلي قابل للإخفاء.
6. اختبارات GAP-21 سلوكية فوق الخدمات الحقيقية (Room في الذاكرة تحت
   Robolectric، مزيفات عند PORT فقط، EgressControl خاص غير مُثبَّت
   = فشل حتمي بلا شبكة).
7. التحقق: `compileDebugKotlin` + `testDebugUnitTest` كاملة (0 فشل)
   + `assembleDebug` + تحقق إضافي أن الصنف داخل dex الحزمة.
8. توثيق التسليم في `UI-REDESIGN-TRACK.md` مع جدول الشرائح التالية
   مُعاد الترقيم.

---

## 3. الجرد الدقيق للمتبقي في MainViewModel (1052 سطراً)

قراءة سطر-بسطر للرأس الحالي، مصنّفة حسب الميزة:

### أ. الرادار والتطور (نطاق الشريحة 6) — ~90 سطراً + 3 جامعات
- **حالة**: `radarItems` / `evolutionCandidates` / `isRadarRefreshing`
- **دوال**: `refreshRadar()` (كاتب isRadarRefreshing الوحيد — إصلاح
  GAP-23) / `advanceCandidateStage()` / `recordCandidateSecurityAudit()` /
  `recordCandidateGovernanceApproval()` / `measureRegisteredCapability()` /
  `retireRegisteredCapability()` — كلها عبر `IntelligenceRadarPipeline`
  بنتائج Outcome صادقة (F-10 / P1-17).
- **تبعيات تُفقد**: `intelligenceRadarPipeline` بالكامل + جامعا
  `radarItems`/`evolutionCandidates` من `observeSubsystems()`.
- **شاشة**: `RadarScreen.kt` (449 سطراً) — تُركَّب حالياً على
  `viewModel.uiState` بالكامل؛ فيها فلترة مرحلة/تصنيف بـ
  `rememberSaveable` وسبينر تحديث وأزرار دورة حياة الترقية الكاملة.

### ب. ذكاء القرار CBR-MDP (نطاق الشريحة 6) — ~70 سطراً + مرايا
- **حالة**: `latestDecision` / `caseBaseList` / `isSimulatingDecision` /
  `decisionTaskComplexity` / `decisionUncertainty`
- **دوال**: `updateDecisionComplexity()` / `updateDecisionUncertainty()` /
  `simulateDecision()` (عبر `DecisionSimulationUseCase` — GAP-19) مع
  المسار الصادق عند غياب حالة الاستخدام.
- **⚠ الدرزة الأهم في الشريحة**: مرايا القرار تُغذّى من **ناقل
  StudioSignal** (انظر `observeStudioSignals()`): `DecisionMade` →
  latestDecision، `ObservationRecorded` → decisionUncertainty +
  caseBaseList (من `cbrMdpEngine.getCaseBase()`)،
  `Completed`/`Error` → caseBaseList، و`NetworkPolicyChanged` → مرآة
  السياسة + إعادة محاكاة. عند الاستخراج، **DecisionViewModel يجمع
  حصته من الناقل بنفسه** — نفس نمط GovernanceViewModel (الذي يبقي
  مرآة سياسة خاصة متزامنة من الناقل). إشارة `Started` (activeExecutionId)
  تبقى ملك النشاط في MainViewModel.
- **تبعيات تُفقد**: `cbrMdpEngine` + `decisionSimulationUseCase`.
- **شاشة**: `DecisionScreen.kt` (331 سطراً) — تُركَّب على uiState؛
  منزلقات متجه الحالة + محاكاة + دونات ثقة + بدائل مرتبة + إحصاءات
  قاعدة الحالات + شرّاح صدق للآلية.

### ج. خطط العمل "Tasks المتبقي" (نطاق الشريحة 6) — ~240 سطراً + جامعان
> TasksViewModel (لوحة المهام الدائمة + resume) **موجودة منذ GAP-11**
> وتعمل؛ المتبقي في MainViewModel هو **منشئ خطط العمل + مكتبتها +
> الاستئناف** — وهو ما تعنيه خانة "Tasks المتبقي" في جدول المسار.
- **حالة**: `workflowReport` / `isExecutingWorkflow` / `workflowBuilder`
  (WorkflowBuilderState) / `workflowLibrary` / `resumableWorkflows`
  (+ `WorkflowBuilderStep/State` في `UiState.kt` — تنتقل مع الميزة).
- **دوال**: `executeWorkflow()` (بذر البذور المكتملة للاستئناف الدائم) /
  9 دوال منشئ (name/goal/mode/addStep/updateStep/removeStep/moveStep/
  toggleDependency/assignAgent — الربط القانوني للوكيل) /
  applyWorkflowTemplate / `saveWorkflowDefinition()` /
  `loadWorkflowDefinitionIntoBuilder()` / `runWorkflowDefinition()` /
  `cloneWorkflowDefinition()` / `deleteWorkflowDefinition()` /
  `resumeWorkflow()` (نطاق مساحة عمل — عائلة العيب 1) /
  `loadResumableWorkflows()` / `observeWorkspaceScopedAssets()` (جزء
  المكتبة والقابل للاستئناف منه).
- **تبعيات تُفقد**: `executeWorkflowUseCase` + `workflowLibraryService` +
  `workflowPersistenceService`.
- **شاشة**: `TasksScreen.kt` (**986 سطراً** — أكبر شاشة متبقية) — تركيب
  مزدوج حالياً: `tasksViewModel` (اللوحة) + `viewModel` (المنشئ/المكتبة).
  بعد الشريحة: تركيب كامل على مالك الميزة.

### د. يلي الشريحة 6 (خارج نطاقها الحالي المعلن — للشرائح اللاحقة)
- **الوكلاء** (~120 سطراً): `activeAgent`/`availableAgents` +
  `initializeAgents`/`refreshAgentCatalog`/`createAgent`/`deleteAgent`/
  `selectAgent` — تبعيات `agentRegistryService` + `componentRegistry`.
  (درزة الوكيل النشط المشترك مع Studio/Tasks/Explorer موثقة في الشريحة 2.)
- **الامتدادات** (~45 سطراً + 4 جامعات): skills/plugins/mcpServers/
  integrations + `toggleSkill`/`executeSkillDirectly`/`togglePlugin`/
  `toggleMcpServer`/`pingMcpServer`/`registerMcpServer`/
  `connectIntegration` — تبعية `extensionManager`. الشاشة:
  `ExtensionsScreen.kt` (586 سطراً) — آخر شاشة كاملة على MainViewModel.
- **تدفق النشاط**: `activeExecutionTrace`/`recentAuditEvents` (تُقرأ من
  `UnifiedActivityFeedScreen` عبر MainViewModel) — مرشح طبيعي لشريحة
  لاحقة (ActivityViewModel) مع إشارة `Started` من الناقل.
- **يبقى في MainViewModel مشروعاً**: حالة الإقلاع (بوابة الفشل الصادقة
  + retryBootstrap)، مراقبة مساحة العمل (activeProject + مرآة
  autonomyPolicy)، جمع إشارة Started للنشاط، ومرايا العرض العابرة
  المتبقية.

---

## 4. مصفوفة Roborazzi (مطلب الشريحة 6 — GAP-21)

البنية جاهزة بالكامل: `roborazzi` 1.59.0 في الكتالوج + `alias(libs.plugins.roborazzi)`
+ `testImplementation` الأربعة + `GreetingScreenshotTest` كنمط مرجعي
(`RobolectricTestRunner` + `GraphicsMode.NATIVE` + `RobolectricDeviceQualifiers.Pixel8`
+ `captureRoboImage`). المرجع الحالي يلتقط مكوناً واحداً؛ **الجديد
المطلوب**: مصفوفة لقطات للشاشات المعاد تصميمها في الشريحة (Radar /
Decision / Tasks) عبر حالات فارغة/محمّلة/تدهور — تُبنى فوق مكونات
`presentation/ui/components/` (SectionHeader / EmptyState / StatusBadge /
DonutChart / MetricBar / InfoRow / DiagnosticBanner).

---

## 5. بيئة البناء (متحقَّق منها في هذه الجلسة)

- Java 21 (OpenJDK 21.0.12) ✅ — متوافق مع AGP 9.1.1 / Gradle 9.3.1.
- **لا يوجد Android SDK** في بيئة العمل الحالية — يتطلب تثبيت
  cmdline-tools + `platforms;android-36` + `build-tools` وقبول التراخيص
  قبل أي `compileDebugKotlin`/`assembleDebug`/`testDebugUnitTest` محلي.
- Gradle wrapper سينزّل 9.3.1 عند أول تشغيل.
- اختبارات الوحدة JVM/Robolectric لا تحتاج محاكياً؛ مسار e2e-device.yml
  (المحاكي) يبقى حكر GitHub CI.

## 6. مخاطر ونقاط حرجة للشريحة 6 (مستخلصة من سوابق الشرائح)

1. **مرايا القرار من الناقل**: أخطاء التوجيه هنا هي أرجح عطل — القرار
   «يعتمد» على سياسة الجلسة (مدخل محاكاة) وعلى أحداث التنفيذ (مرايا).
   كلاهما يأتي عبر StudioSignal؛ يجب أن يجمع DecisionViewModel حصته
   بنفسه ولا يُترك أي مصرف مشترك.
2. **أحداث الطرف المعروفة** (موثقة في الشريحة 2، مؤجلة صراحة إلى «شريحة
   Decision»): خطأ المزود يمرّ ثم تصدر الحلقة `Completed` بنص احتياطي
   فيلحق دوران (فاشل ثم «ناجح») — **قرار هذا السلوك الآن ملك الشريحة 6**
   (تصحيح عقد التنفيذ الطرفي أو توثيقه مرة أخرى).
3. **`observeWorkspaceScopedAssets` مكدّس الجامعات**: جامع المكتبة يُطلق
   `launch` جديداً لكل انبعاث مساحة عمل دون إلغاء السابق — نفس عائلة
   «آخر كاتب» التي أُصلحت في الشريحتين 2 و4 بـ flatMapLatest؛ فرصة
   الإصلاح الموثق عند النقل (بسابقة الشريحة 4).
4. **TasksScreen 986 سطراً**: إعادة التركيب عليها يجب أن تكون جراحة
   دقيقة (المنشئ/المكتبة/الاستئناف → الميزة الجديدة؛ اللوحة →
   TasksViewModel كما هي).
5. **سباقات جانب الاختبار** (سابقةProviders): الانتظار على الحالة
   النهائية لا العابرة، وعلى المجموعة الكاملة لا «غير الفارغ» — توثيق
   أي إصلاح اختبار دون تغيير منتج.
6. **عربي فقط**: أي نص جديد = حرفية عربية داخل الشاشة (D-1)؛ لا
   strings.xml جلبه هنا.

## 7. خطة الشريحة 6 المقترحة (خطوات مرتبة)

1. **RadarViewModel** (الأصغر): استخراج الحالة الثلاثة + الجامعين من
   `observeSubsystems` + الدوال الست عبر `IntelligenceRadarPipeline`؛
   شاشة الرادار تُركَّب على مالكها؛ اختبارات فوق الأنابيب الحقيقية
   (رادار + تطور)؛ لقطة Roborazzi (فارغ/محمّل).
2. **DecisionViewModel**: استخراج الحالة الخمسة + دوال المحاكاة عبر
   `DecisionSimulationUseCase`؛ جمع حصة الناقل (DecisionMade /
   ObservationRecorded / Completed / Error / NetworkPolicyChanged +
   إعادة المحاكاة)؛ **حسم سلوك الحدث الطرفي الموثق** (نقطة 2 أعلاه)؛
   شاشة القرار على مالكها؛ اختبارات تشمل: المسار الحقيقي لحالة
   الاستخدام، الصدق عند غيابها، المرايا من ناقل مُحقن في الاختبار،
   إعادة المحاكاة بتغير السياسة؛ لقطة.
3. **WorkflowsViewModel** (الأكبر): نقل المنشئ + المكتبة + الاستئناف
   بكل حقولهم؛ إصلاح `observeWorkspaceScopedAssets` بـ flatMapLatest
   (بسابقة 4)؛ TasksScreen تركيب كامل؛ اختبارات: حفظ→تحميل→تحرير→إعادة
   حفظ (الأصل الدائم القابل لإعادة التحرير)، تنفيذ بذر البذور، الاستئناف
   النطاقي، فشل صادق للحذف/الاستنساخ؛ لقطة.
4. **التحقق الكامل**: `compileDebugKotlin` + `compileDebugUnitTestKotlin` +
   `compileDebugAndroidTestKotlin` + `testDebugUnitTest` كاملة
   (المتوقع ≥ 765 + اختبارات الشريحة الجديدة، 0 فشل) + `assembleDebug` +
   تحقق صنوف الميزات داخل dex + تحديث `UI-REDESIGN-TRACK.md` ب تسليم
   الشريحة وإعادة ترقيم الجدول.

## 8. قواعد المسار (موروثة — ملزمة)

- أي ميزة جديدة → ViewModel ميزة جديد فقط (تجميد توسيع MainViewModel).
- كل VM ميزة = اختبارات سلوكية (GAP-21) قبل اعتبار الشريحة مكتملة.
- لا إعادة تصميم لشاشة قبل استخراج VM الخاصة بها (خيار ج).
- الصدق الظاهري أولاً: أي نص/عدّاد في UI يقرأ مصدر حقيقة واحد.
