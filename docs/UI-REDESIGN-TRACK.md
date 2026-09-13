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

### حالة e2e-device.yml (غير مشخَّصة — قيد صادق)

فشل في كل تشغيلات push (9–13). تنزيل السجلات يتطلب صلاحيات admin على
المستودع (غير متاحة هنا) ولا يوجد محاكي في بيئة التحقق المحلية، لذا
**لم يُشخَّص السبب ولم يُطبَّق أي إصلاح أعمى**. مالك المستودع يستطيع
فتح سجلات آخر تشغيلة من تبويب Actions لتحديد الخطوة الفاشلة (المشتبه
الأول: إقلاع المحاكي أو خطوة الاختبارات نفسها). لا يعيق هذا إنتاج
apk-debug (android.yml مسار مستقل).

## الشرائح التالية (الترتيب المقترح)

| الشريحة | المحتوى | ملاحظات |
|---|---|---|
| 2 | StudioViewModel + SessionsViewModel (الاستوديو: المحادثة، الجلسات الدائمة، quick chat) | الأثقل؛ ينقل `setNetworkPolicy` (مدخل جلسة) و`provisionLocalSemanticModel` مع KnowledgeViewModel |
| 3 | KnowledgeViewModel + وصل المعرفة | `semanticModelReady` المشتركة تنتقل هنا |
| 4 | GovernanceViewModel (+ الموافقات) | يعتمد على أسطح ADR-2 القائمة |
| 5 | ProvidersViewModel + wizard | 1231 سطراً حالياً — أكبر شاشة |
| 6 | RadarViewModel / Decision / Tasks المتبقي | مع مصفوفة Roborazzi (GAP-21) |
| 7 | i18n قرار | D-1 موثّق (عربي فقط)؛ أي توسع لغوي = استخراج سلاسل |

## قواعد المسار (موروثة من الخطة)

- أي ميزة جديدة → ViewModel ميزة جديد فقط (تجميد توسيع MainViewModel).
- كل VM ميزة = اختبارات سلوكية (GAP-21) قبل اعتبار الشريحة مكتملة.
- لا إعادة تصميم لشاشة قبل استخراج VM الخاصة بها (خيار ج).
- الصدق الظاهري أولاً: أي نص/عدّاد في UI يجب أن يقرأ مصدر حقيقة واحد.
