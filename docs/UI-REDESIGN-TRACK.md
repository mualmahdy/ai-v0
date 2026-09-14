# UI-REDESIGN-TRACK — مسار إعادة تصميم الواجهة (Design Closure 2026)

> هذا المستند يعرّف المسار، يسجّل كل شريحة مُسلَّمة، ويحدّد الشريحة
> التالية. المرجع الحاكم: ADR-6 (03-adr-set.md §10) + خطة §12/§13
> (04-implementation-plan.md).

## قرار المسار (حاكم)

إعادة التصميم تجري **متزامنة مع تفكيك ADR-6** (خيار ج): لا إعادة تصميم
فوق MainViewModel دون تفكيك (تضخيم الدين)، ولا تفكيك كامل مسبق (تسلسل
بطيء). لكل شريحة: ViewModel ميزة مستقل + سلوكه المختبر (GAP-21) +
إعادة التصميم البصري للشاشات التي تملكها.

## الشريحة 1 (مُسلَّمة — Phase 5)

### ما سُلِّم

1. **FilesViewModel** (استخراج كامل — أول نمط مكتمل لـ ADR-6):
   - الحالة: `files / selectedFilePath / selectedFileContent / isFileLoading`
     غادرت `UiState` المشتركة (264→~255 حقلاً) إلى `FilesUiState` خاصة؛
     MainViewModel فقد 6 دوال + `currentProjectIdOrInform` + تبعية
     `manageWorkspaceFilesUseCase` بالكامل.
   - السلوك: تحديث تلقائي للقائمة عند تغيّر المشروع النشط (يحل محل
     استدعاء `refreshFiles()` في `observeWorkspace` القديم) مع إزالة
     تكرار بمعرّف المشروع.
   - الاختبارات: `FilesViewModelTest` — 15 اختبار سلوكي (Outcome صادق،
     بوابة المشروع بوعي حالة الإقلاع، دورة المحرر، الحفظ/الحذف،
     التبديل التلقائي).

2. **SettingsViewModel** (إدارة مساحات العمل):
   - `switchWorkspace / createWorkspace / updateWorkspaceNetworkPolicy /
     setAutonomyPolicy` — كلها عبر WorkspaceRuntimeService السلطوي. `setAutonomyPolicy` حُذفت من
     MainViewModel نهائياً؛ مرآة العرض `UiState.autonomyPolicy` تُزامَن
     الآن من العمود المُخزَّن في `observeWorkspace`.
   - تحسين صدق: خدمة التبديل تُرجع false لمساحة غير موجودة (بلا رمي) —
     كان ذلك صمتاً في UI؛ الآن يظهر رسالة صريحة.
   - الاختبارات: `SettingsViewModelTest` — 7 اختبارات (الدوال الجديدة +
     عمود الاستقلالية السلطوي + فشل التبديل الصادق).

3. **إعادة تصميم الشاشات الثلاث**:
   - Settings: **إصلاح صدق** — بطاقة «حول» كانت تعرض «Room v12» بينما
     القاعدة v17؛ الآن تقرأ `AppDatabase.SCHEMA_VERSION` (مصدر الحقيقة
     الواحد). صفوف السياسات تحمل شرحاً بلغة واضحة، بطاقات مساحات
     بهيكل معلومات + شارات.
   - Files: ترتيب (الاسم/الحجم/آخر تعديل)، مجلدات أولاً في مجموعة
     مسماة، عنوان حي بعدادات (مجلد/ملف/حجم)، بانر تشخيصي محلي.
   - Explorer: أربع أقسام مسماة (ذكاء التنفيذ / الموارد والأدوات /
     المعرفة والمحتى / الخطط والجلسات)، شارة عدد + سهم لكل صف، وعدد
     الملفات من FilesViewModel.
   - Studio: شريط السياسات يستقبل `onAutonomyPolicy` من
     SettingsViewModel (العرض في MainViewModel، التغيير في ميزة الإعدادات).

4. **CI — إصلاح البناء التلقائي للـ apk-debug** (طلب المستخدم الصريح):
   - **الجذر**: android.yml كان فاشلاً في **52/52 تشغيلة** منذ إنشائه —
     قيمة `name:` في الخطوة 43 تحوي `": "` غير مُقتبسة → ملف YAML غير
     صالح → لا تُنشأ أي jobs أصلاً. build-apk.yml (المحذوف في Phase 3)
     كان المنتج الفعلي الوحيد للـ APK، فبقي المستودع بلا بناء APK على
     GitHub بعد دمج Phase 3.
   - **إصلاح ثانٍ ( signing)**: خطوة bundleRelease كانت تحقن قيم
     توقيع فارغة (`-Pandroid.injected.signing.*=`) — يبني AGP إعداد
     توقيع `externalOverride` مكسوراً ويفشل `validateSigningRelease`.
     الآن: `build.gradle.kts` لا يُسند signingConfig للـ release إلا
     عند توفر KEYSTORE_PATH أو ملف مفتاح محلي → AAB غير موقّع صادق في
     CI، والموقّع في release.yml (الذي يزوّد KEYSTORE_PATH).
   - **التحقق المحلي (CI-تساوي)**: compileDebugKotlin ✓،
     testDebugUnitTest ✓، assembleDebug ✓، bundleRelease ✓ (AAB غير
     موقّع 36MB) — أول تشغيل ناجح مُوثّق لسلسلة android.yml كاملة.
   - اسم工件 الـ APK: `ai-v0-ultimate-debug-apk` (نفس اصطلاح
     build-apk.yml المحذوف) + retention 14 يوماً.

### حالة e2e-device.yml (مُشخَّصة ومُصلَحة — Phase 7)

**الجذر (مُثبت)**: فشلت كل التشغيلات 1–15 (من إنشاء الـ workflow في
3b10cc2) على الخطوة نفسها. الأدلة العامة (بدون صلاحيات admin): تعليق
الخطوة "The process '/usr/bin/sh' failed with exit code 1"، نجاح خطوة
رفع النتائج (`if: always()`) بما يعني وجود ملفات نتائج (19.6KB —
حجم سيناريو اختبار فاشل مع stack trace، وليس فشل تجميع أو إقلاع)،
ومدة الـ job الكاملة 4m35s (متسقة مع بناءٍ ذي كاش دافئ + تشغيل اختبارات
قصير). الجذر في الكود: `DurableWorkspaceE2ETest.durableSession_survivesDatabaseReopen`
ينمذج موت العملية بـ `close()` ثم `getInstance()` — لكن `getInstance`
سِنغلتِن عمليات: ظل `INSTANCE` يشير إلى القاعدة **المغلقة** بعد الـ
close، فأعادت `getInstance` المثيل المغلق نفسه، وأول استعلام في مرحلة
"إعادة الفتح" قذف `IllegalStateException`. اختبارات JVM للديمومة لم
تلتقط هذا أبداً لأنها تبني مثيلاتها مباشرة
(`ProcessDeathRecoveryTest`/`ConversationSessionDurabilityTest` بـ
`openDb()` خاص) بينما الـ e2e يستخدم مسار الإنتاج الصحيح
(`AppContainer → getInstance`) — فاصطدم بالنصف المفقود من نمذجة موت
العملية.

**الإصلاح (Phase 7 — patch مستقل)**:
1. `AppDatabase.resetInstanceForProcessDeath()` — خطاف **اختباري فقط**
   (internal، لا سلوك إنتاجي يتغير): يمثل النصف الذاكري لموت العملية؛
   العملية الحقيقية تبدأ بذاكرة فارغة والملف الدائم يبقى على القرص.
2. الـ e2e: `close()` ثم `resetInstanceForProcessDeath()` ثم
   `getInstance()` — مثيل جديد مفتوح على الملف نفسه، كما في إعادة
   التشغيل الحقيقية. (وعنوان اختبار الإقلاع صُحّح: v13 → latest/SCHEMA_VERSION
   مع assert فعلي على `readableDatabase.version`.)
3. `AppDatabaseSingletonProcessDeathTest` (JVM/Robolectric) — مرآة
   كاملة لمسار e2e على مسار الإنتاج نفسه، مع تشغيل تحكّمي موثّق: بدون
   الـ reset يعود المثيل المغلق نفسه (RED مُعاد إنتاجه محلياً)، ومعه
   يعود مثيل جديد والبيانات باقية (GREEN).
4. نظافة عابرة للاختبارات: `Migration14to15AndDurableApprovalTest` كان
   يحذف الملف في `finally` دون تصفير السِنغلتِن (لغم لكل مستخدم
   `getInstance` لاحق في نفس الـ JVM) — أُضيف الـ reset هناك.

**التحقق المحلي (تشغيلات فعلية)**: الاختبار الجديد RED→GREEN مُوثّق؛
كامل `testDebugUnitTest` بـ `--rerun`: 110 suites / 708 tests /
0/0/0؛ `compileDebugAndroidTestKotlin` و `assembleDebugAndroidTest`
(960,884 bytes) و `assembleDebug` (100,270,222 bytes) ناجحة كلها.

**حكم الـ CI الفعلي**: معلق على التشغيلة 16 بعد دمج هذا الـ patch —
لا يُدَّعى النجاح قبل حدوثه. (apk-debug مستقل عن هذا المسار كما كان.)

## الشريحة 2 (مُسلَّمة — Phase 6)

### ما سُلِّم

1. **StudioViewModel** (زمن التشغيل المحادثي — الأثقل كما وُصف في الجدول):
   - الحالة: 18 حقلاً غادرت `UiState` (promptInput / isExecuting /
     executionLog / streamText / studioSession / sessionTurnStartMs /
     chatMode / activeSessionId / selectedModelResourceId /
     selectedModelDisplayName / isDegraded / degradedReason /
     currentTokensConsumed / sessionTotalTokens / remainingBudget /
     networkPolicy / قناتا الخطأ والبانر الخاصتين بالميزة) إلى
     `StudioUiState` خاصة.
   - السلوك: نواة التنفيذ كاملة (`executePrompt` / `cancelExecution` عبر
     ExecutionHost المُسنَد إلى مساحة العمل)، quick-chat عبر الوكيل
     القانوني، تثبيت النموذج (`selectModel` → `setSessionModel`)،
     `setNetworkPolicy` (مدخل الجلسة)، ضمان الجلسة الدائمة قبل التنفيذ
     (`ensureActiveSession`)، وحفظ الدورات (`persistTurnDurably` مع
     التسمية من أول سؤال).
   - **درزة الوكيل**: `activeAgent` بقيت حالة مشتركة في MainViewModel
     (يقرؤها TasksScreen وExplorerScreen) — الشاشة تمرر الوكيل المختار
     معاملاً إلى `executePrompt(agent)` / `startNewSession(agent)`.
   - **ناقل إشارات الميزة** (`StudioSignal`): أحداث التنفيذ ذات الأثر
     العابر للميزة (Started للربط بتتبع النشاط، DecisionMade /
     ObservationRecorded / Completed / Error لمرايا العرض القرارية) +
     تغيّر سياسة الشبكة تُنشَر على `MutableSharedFlow` يملكه MainActivity؛
     MainViewModel يجمعها في `observeStudioSignals` ويحدّث المرايا —
     لا حالة قابلة للتغيير مشتركة بين الاثنين.

2. **SessionsViewModel** (سجل الجلسات الدائمة):
   - الحالة: قائمة الجلسات (نطاق GAP-14: مشروع/مشترك)، علم متصفح
     الجلسات، قناة خطأ خاصة.
   - السلوك: المراقبة عبر **flatMapLatest** — إصلاح سباق «آخر كاتب»
     الموروث (المراقب القديم كان يُطلق جامعاً جديداً لكل تبديل دون
     إلغاء السابق، فتتراكم الجامعات ويتنافس إرسالها). `deleteSession`
     تُخطِر الاستوديو عبر callback يُطلق بعد استقرار الاستدعاء (ترتيب
     حتمي لمسح الربط النشط).

3. **تكليف MainViewModel** (تقلّص من 2293 إلى ~1730 سطراً):
   - فُقدت تبعيتان كاملتان: `conversationSessionService` و`appContext`
     (الأخيرة كانت لأجل خدمة التنفيذ الأمامية فقط).
   - مرايا العرض القرارية (latestDecision / decisionUncertainty /
     caseBaseList) وشبكة الجلسة (networkPolicy) تبقى لكن مصدرها ناقل
     الإشارات — نفس نمط autonomyPolicy من الشريحة 1.

4. **الشاشات**:
   - Studio: تركيب ثلاثي (viewModel للكتالوج/الموارد، studioViewModel
     للمحادثة، sessionsViewModel للسجل)؛ شريط الجلسات يحسب عنوان الجلسة
     النشطة من قائمة السجل بمعرّف ربط المحادثة.
   - Settings: قسم «سياسات التنفيذ (الجلسة)» يعرض ويغيّر سياسة الشبكة
     عبر قيمة + lambda إلى ميزة الاستوديو (نمط تفويض الشريحة 1 معكوساً).
   - Explorer: صف «الجلسات» يقرأ القائمة من SessionsViewModel (صاحبها).

5. **GAP-21 — الاختبارات السلوكية**: `StudioViewModelTest` (18 اختباراً
   عبر النواة الحقيقية: ExecuteAgentTaskUseCase → AgentOrchestrator مع
   مزوّد LLM محلي وهمي عند PORT فقط، وWorkspaceRuntimeService و
   ConversationSessionService الحقيقيين فوق مزيفات DAO/المستودع) +
   `SessionsViewModelTest` (8 اختبارات: عزل نطاق GAP-14، إعادة النطاق
   بالتبديل، حذف بترتيب callback). 26 اختباراً جديداً.

### صدق مكتشف (سلوك قائم لم يُغيَّر — موثَّق)

- طوبولوجيا أحداث النهاية عند فشل المزود: خطأ المزود يمرّ عبر
  ExecutionService **ثم** تصدر الحلقة `Completed` بنص الاحتياط
  «اكتملت معالجة المهمة.» — فيُلحق دوران (فاشل ثم «ناجح») في النص.
  هذا سلوك خط الأنابيب قبل الشريحة نفسه (المجمع المنقول حرفياً)،
  أثبته الاختبار كما هو؛ أي تصحيح له عقد التنفيذ الطرفي هو قرار
  مستقبلي (شريحة Decision) وليس من نطاق إعادة التصميم.
- إلغاء الاستوديو كان يصفّر `isExecutingWorkflow` ( علم ميزة المهام)
  دون أن يُلغي فعلاً تنفيذ خطة العمل الجارية — كذبة عرض موروثة؛ بعد
  التفكيك كل ميزة تصفّر علمها فقط.
- المراقب القديم لقائمة الجلسات كان يراكم الجامعات (سباق آخر كاتب) —
  أُصلح في SessionsViewModel بـ flatMapLatest (الملاحظة نفسها نقلت،
  فكُتبت صحيحة).

### التحقق من CI (طلب المستخدم الصريح — مُثبَت بقرار حقيقي)

- **android.yml ناجح على GitHub**: التشغيلة #53 «Build Android APK»
  على `0966d3d` (دمج المرحلة 5) — `completed / success` بتاريخ
  2026-09-13T15:24:42Z. نجاح الـ job يشمل خطوات التحميل (لا ينجح
  إلا إذا خرجت كل خطوة بـ 0)، فتتأكد آلية `ai-v0-ultimate-debug-apk`.
  أول نجاح كامل للـ workflow بعد 52 فشلاً متتالياً (الخلل YAML).
- e2e-device.yml: **مُشخَّص ومُصلَح في Phase 7** (الجذر والتحقق في
  القسم المخصّص أعلاه) — الجذر كان سِنغلتِن `AppDatabase` يعيد المثيل
  المغلق بعد `close()` في اختبار إعادة الفتح؛ الحكم النهائي = التشغيلة
  16 بعد الدمج.

## الشرائح التالية (الترتيب المقترح)

| الشريحة | المحتوى | ملاحظات |
|---|---|---|
| 3 | KnowledgeViewModel + وصل المعرفة | `semanticModelReady` المشتركة تنتقل هنا مع `provisionLocalSemanticModel` |
| 4 | GovernanceViewModel (+ الموافقات) | يعتمد على أسطح ADR-2 القائمة |
| 5 | ProvidersViewModel + wizard | 1231 سطراً حالياً — أكبر شاشة |
| 6 | RadarViewModel / Decision / Tasks المتبقي | مع مصفوفة Roborazzi (GAP-21) |
| 7 | i18n قرار | D-1 موثّق (عربي فقط)؛ أي توسع لغوي = استخراج سلاسل |

## قواعد المسار (موروثة من الخطة)

- أي ميزة جديدة → ViewModel ميزة جديد فقط (تجميد توسيع MainViewModel).
- كل VM ميزة = اختبارات سلوكية (GAP-21) قبل اعتبار الشريحة مكتملة.
- لا إعادة تصميم لشاشة قبل استخراج VM الخاصة بها (خيار ج).
- الصدق الظاهري أولاً: أي نص/عدّاد في UI يجب أن يقرأ مصدر حقيقة واحد.
