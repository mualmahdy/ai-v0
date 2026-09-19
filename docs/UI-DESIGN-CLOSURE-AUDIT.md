# UI Design Closure Audit — المرحلة A (مسح ما قبل التصميم)

> التاريخ: 2026-09-20 · الأساس: فرع `main` عند الالتزام `7a4d3a8` («ui: establish home chat projects activity more shell»).
> كل بند في هذه الوثيقة مستنبط من قراءة فعلية للكود الموجود في المستودع — لا افتراضات ولا بيانات مفبركة.
> القاعدة الحاكمة من الحزمة: **«لا تبدأ إعادة التصميم قبل اكتمال هذه المرحلة»** — اكتملت.

---

## 1. جرد الشاشات (Screen Inventory)

| # | الوجهة | الملف | ملاحظات الوضع الراهن |
|---|--------|-------|------------------------|
| 1 | `home` | `screens/home/HomeScreen.kt` (114 سطرًا، أُنشئت في `7a4d3a8`) | مركز عمل اسميًا فقط: بطاقة CTA + شبكة أزرار ثابتة. لا سياق حي، لا استئناف جلسات، لا مشاريع حديثة |
| 2 | `studio` | `screens/studio/StudioScreen.kt` (1389 سطرًا) | لوحة دردشة حقيقية (transcript + streaming + sessions). عناصر التحكم المتقدمة (سياسات + ميزانية الرموز) مكشوفة دائمًا فوق المحادثة |
| 3 | `activity` | `screens/activity/UnifiedActivityFeedScreen.kt` | موجز نشاط موحّد حقيقي (trace + audit) |
| 4 | `projects` | **لا شاشة موجودة** — المسار يفتح `TasksScreen` | العيب الجوهري الموثق في الحزمة (انظر §9/D-01) |
| 5 | `knowledge` | `screens/knowledge/KnowledgeScreen.kt` | قاعدة معرفة RAG + محرك دلالي + ذاكرة |
| 6 | `files` | `screens/files/FilesScreen.kt` | مستكشف ملفات الـsandbox |
| 7 | `more` | `screens/dashboard/DashboardScreen.kt` | لوحة ثانوية مسطّحة: 8 بطاقات بلا تجميع (انظر §9/D-06) |
| 8 | `providers` | `screens/ProviderServiceManagerScreen.kt` | غرفة تحكم المزودين (حقيقية) |
| 9 | `tasks` | `screens/tasks/TasksScreen.kt` (≈990 سطرًا) | لوح المهام + باني الخطط + المكتبة |
| 10 | `decision` | `screens/decision/DecisionScreen.kt` | قمرة قرار CBR-MDP |
| 11 | `radar` | `screens/radar/RadarScreen.kt` | مرصد التطور |
| 12 | `governance` | `screens/governance/GovernanceScreen.kt` | مرصد الحوكمة والميزانية |
| 13 | `extensions` | `screens/extensions/ExtensionsScreen.kt` | MCP + مهارات + إضافات |
| 14 | `settings` | `screens/settings/SettingsScreen.kt` (523 سطرًا) | 4 أقسام مسطّحة: سياسات التنفيذ / إدارة المساحات / النموذج الدلالي / حول (انظر §9/D-07) |
| 15 | `explorer` | `screens/explorer/WorkspaceExplorerScreen.kt` | مستكشف الكائنات الموحّد |

مكوّنات مشتركة: `components/WorkspaceComponents.kt` (473 سطرًا: SectionHeader/StatCard/StatusBadge/InfoRow/EmptyState/MetricBar/DonutChart/ConfirmDialog/BusyIndicator) و`components/StudioComponents.kt` (DiagnosticBanner/TokenBudgetGauge/ExecutionEventTimelineItem).

## 2. مخطط التنقل (Navigation Graph) — كما هو فعلًا

- `MainActivity` → `MainAppScreen` (NavHost واحد، `startDestination = home`).
- شريط سفلي (NavigationBar) بخمس وجهات أولية: `الرئيسية(home) → الدردشة(studio) → المشاريع(projects) → النشاط(activity) → المزيد(more)` مع `popUpTo(start){saveState}` + `launchSingleTop` + `restoreState` — سليم بنيويًا.
- وجهات ثانوية تُفتح بـ`navigate(route){launchSingleTop}` من Home/More/Settings/Explorer: `knowledge, files, providers, tasks, decision, radar, governance, extensions, settings, explorer`.
- KDoc في `WorkspaceRoutes.kt` **متقادم**: يصف الشريط السفلي القديم («Studio / unified Activity / Knowledge / Files / More») بينما الواقع «Home / Chat / Projects / Activity / More» (انظر §9/D-08).

## 3. خريطة ملكية الحالة (Ownership Map) — بعد مسار «Design Closure 2026»

قشرة صادقة (`MainViewModel`, 165 سطرًا): بوابة bootstrap + مرايا عرض (workspace/autonomy) + قناة خطأ عامة. الميزات كلها في VMs مملوكة: Files/Settings/Studio/Sessions/Knowledge/Governance/Providers/Radar/Decision/Workflows/Agents/Extensions/Activity (+Tasks).
**النتيجة**: البنية تحت الاحترام الصارم — ADR-6 يجمّد نموّ MainViewModel. أي شاشة مشاريع جديدة يجب أن تجيء بـ**VM ميزة جديد** (سابقة: TasksViewModel في GAP-11)، لا بحقن في القشرة.

## 4. علاقات السياق الهرمية (Workspace → Project → Session)

- النموذج الخلفي **كامل ومُحكم**: `WorkspaceRuntimeService` (تبديل مساحات + `setActiveProject` المُصادِق عبر `ProjectRuntimeService.selectProject` + حفظ `lastActiveProjectId`) و`ProjectRuntimeService` (إنشاء/اختيار/تسمية/أرشفة/استرجاع/مهملات/حذف/تنقية/نقل + تدقيق كامل + جدول انتقالات §27).
- الجلسات نطاقها المشروع: `ConversationSessionService.observeSessionsForProject(wsId, projectId)` و`SessionsViewModel` يعيد التحجيم عبر `flatMapLatest`.
- **الخلل في طبقة العرض**: `MainViewModel.observeWorkspace` يبني `UiState.activeProject` من **بيانات مساحة العمل ذاتها** (`name = workspace.name`) — مرآة ملصق عليها «مشروع» وهي مساحة عمل. `MainAppScreen` يستهلكها كعنوان/عنوان فرعي في TopBar فيحدث الخلط الموثق في الحزمة: العنوان = اسم المساحة (بديل: اسم «المشروع» المزيف)، والعنوان الفرعي = اسم المساحة مرة ثانية (أو «AI Studio S0» بالإنجليزية). لا يوجد أي سطح عرض يعرض **المشروع الحقيقي النشط** رغم توفره الكامل في الخلفية (انظر §9/D-02).
- لا توجد أي واجهة لإدارة المشاريع (إنشاء/فتح/تبديل) في أي مكان بالتطبيق — القدرة الخلفية الكاملة **غير مكشوفة إطلاقًا** (§9/D-01).

## 5. جرد النظام البصري (Visual System Inventory)

- الثيم: `ui/theme/Color.kt` (لوحة Slate/Cyan/Emerald/Amber/Crimson/Purple — تُستخدم عبر `Theme.kt` في dark/light schemes) + `Type.kt`.
- **خرق التوكنز**: `StudioComponents.kt` يصلب ألوانًا hex مباشرة: `Color(0xFF2E7D32)`, `Color(0xFF00897B)`, `Color(0xFF1B5E20)` خارج أي token — مخالفة لقاعدة «ممنوع hex داخل مكونات الميزة».
- لا توجد ألوان دلالية موحدة للنجاح/التحذير/المعلومات خارج colorScheme (error موجود؛ success/warning مُصلَّبة يدويًا في أماكن متفرقة).
- `strings.xml` يحتوي **اسم التطبيق فقط** — كل النصوص inline (هذا هو القرار D-12 الموثق: نهاية مقصودة للمسار، مع قاعدة أن أي **شاشة جديدة** تلتزم بالموارد من اليوم الأول). أي شاشات جديدة/معاد كتابتها في هذه الحزمة تلتزم `stringResource(...)`.

## 6. جرد إمكانية الوصول (Accessibility Inventory)

- عناصر الشريط السفلي: `contentDescription = label` لكل عنصر — سليم.
- زر الإعدادات في TopBar: contentDescription عربي — سليم.
- **عيوب**: أربع `contentDescription` إنجليزية مكشوفة لقارئ الشاشة في `StudioComponents.kt` («Diagnostic Alert», «Token Usage») ونصوص مستخدم تحوي مصطلحات إنجليزية («Tkn», «(Observation)», «(Token Budget)») (§9/D-09). عناصر RTL مثبتة على مستوى النشاط (`LayoutDirection.Rtl`) — سليم ومتوافق مع عربية-أولًا. لا فحص TalkBack آلي موجود (Robolectric لا يشغّل TalkBack — يُوثَّق كغير مُتحقق).
- أهداف اللمس: عناصر M3 القياسية (NavigationBarItem/IconButton) تلبي 48dp؛ لا أزرار أصغر من الحد مُكتشفة في الهيكل.

## 7. جرد الاستجابة للقياسات (Responsive Inventory)

- **لا يوجد أي تكيّف**: `NavigationBar` ثابتة لكل العروض؛ لا `WindowSizeClass` ولا Rail ولا تقسيم قائمة/تفصيل. الهيكل جاهز للتقسيم (Scaffold + NavHost) لكن لا فرع medium/expanded إطلاقًا (§9/D-10).
- Insets: يُدار صح (statusBars/navigationBars/imePadding في Studio) — سليم.

## 8. مصفوفة الحالات (State Matrix) — التغطية الراهنة

| الحالة | أين موجودة | أين غائبة |
|--------|------------|-----------|
| Loading | BusyIndicator + CircularProgressIndicator مواقع متعددة | Home (بدون تحميل لأنه ثابت — سيتغير مع البيانات الحية) |
| Loaded | كل الشاشات | — |
| Empty | `EmptyState` مكوّن عام + شاشات تستخدمه (Sessions browser, Explorer rows…) | Home (لا حالة فارغة لأي قسم)، Projects (لا وجود أصلًا) |
| Error | قنوات خطأ لكل ميزة + snackbar عام + بوابة bootstrap كاملة الشاشة | — (بنية ممتازة) |
| Degraded | بانر Studio التنفيذي + صف DEGRADED في تقرير الخطط (يُعرَّب «متراجع») | — |
| Disabled | بوابات السياسات (زر التنفيذ معطّل بلا LLM نشط… إلخ) | — |
| Offline/الشبكة | NetworkMonitor يُستهلك في Studio/Governance عبر VMs | لا تمييز بصري موحّد (مقبول: السياسة تُعرض في الأشرطة) |
| تنفيذ جارٍ | LiveExecutionCard + streamText + إلغاء | — |
| نجاح/تأكيد | بانرات dismissible لكل ميزة + snackbar | — |
| استرجاع/إعادة محاولة | بوابة bootstrap (retry) + استئناف الخطط + استرجاع الجلسات | — |

الخلاصة: البنية سليمة؛ الفجوة كلها في **الشاشة/الشاشتين الجديدتين** (Projects/Home الحية) اللتين يجب أن تحققا المصفوفة كاملة من اليوم الأول.

## 9. سجل العيوب (Defect Register) — مدخلات المرحلة B/C

| المعرّف | العيب | الدليل (ملف:سطر) |
|---------|-------|--------------------|
| D-01 | مسار `PROJECTS` يفتح `TasksScreen` — لا واجهة مشاريع إطلاقًا رغم خلفية كاملة | `MainAppScreen.kt:587-594` |
| D-02 | TopBar يخلط Workspace/Project: مرآة `activeProject` هي بيانات مساحة العمل بملصق مشروع؛ العنوان الفرعي يسقط إلى «AI Studio S0» | `MainViewModel.kt:128-141`, `MainAppScreen.kt:317-318` |
| D-03 | الرئيسية والدردشة تتشاركان أيقونة `Icons.Default.Psychology` في الشريط السفلي | `MainAppScreen.kt:337,344` |
| D-04 | زرّا «المشاريع» و«المستكشف» في Home يتشاركان `Icons.Default.Folder` | `HomeScreen.kt:82,102` |
| D-05 | Home ليست مركز عمل: لا جلسات حديثة، لا استئناف، لا سياق مشروع حقيقي، لا حالة فارغة | `HomeScreen.kt` كاملًا |
| D-06 | «المزيد» مسطّح بلا مجموعات الحزمة الثلاث (Workspace / Intelligence / Governance & System) | `DashboardScreen.kt:112-169` |
| D-07 | الإعدادات 4 أقسام فقط بدل التصنيف المطلوب بـ12 فئة | `SettingsScreen.kt:128-327` |
| D-08 | KDoc متقادم في `WorkspaceRoutes.kt` (يصف هيكل تنقل سابقًا) و`MainAppScreen.kt` (يذكر Knowledge/Files كوجهات سفلية) | `WorkspaceRoutes.kt:7-11`, `MainAppScreen.kt:100-107` |
| D-09 | مصطلحات إنجليزية مكشوفة للمستخدم/قارئ الشاشة: «Diagnostic Alert», «Token Usage», «Tkn», «(Observation)», «(Token Budget)» | `StudioComponents.kt:75,131,139,153,219` |
| D-10 | لا استجابة لعروض medium/expanded (لا NavigationRail ولا NavigationSuite) | `MainAppScreen.kt:328-370` |
| D-11 | ألوان hex صلبة داخل مكون ميزة (خارج نظام التوكنز) | `StudioComponents.kt:194,218,262` |
| D-12 | Studio يكشف كل عناصر التحكم المتقدمة فوق المحادثة (لا progressive disclosure) | `StudioScreen.kt:204-253` |

## 10. مراجع الأدلة (Evidence References)

- الالتزام المرجعي: `7a4d3a8` (HEAD عند المسح). آخر تاريخ مسار: `f29fa5a` (إغلاق مسار UI-redesign 8/8 شرائح + D-12/D-13).
- حواجز معمارية مُلزِمة: ADR-6 (تجميد نموّ MainViewModel)، D-12 (عربية فقط + inline كنهاية مقصودة للمسار، مع التزام الموارد لأي شاشة جديدة)، D-13 (إزالة الحالة العرضية عديمة الكاتب).
- خدمات جاهزة للاستهلاك في المرحلة B: `ProjectRuntimeService` (§27 lifecycle كامل)، `WorkspaceRuntimeService.setActiveProject/selectProject`، `ConversationSessionService.observeSessionsForProject`، `SessionsViewModel` (قائمة حية)، `StudioViewModel.openSession/startNewSession`.
- قاعدة الاختبار الموثقة في المستودع: أجنحة سلوكية فوق الحزمة الحقيقية (Room في الذاكرة + خدمات حقيقية) + مصفوفات Roborazzi؛ الاختبارات المتعلقة بالملكية تُنقل مع الملكية (سابقة: GAP-21 لكل شريحة).

---

**قرار البوابة**: المرحلة A مكتملة بالأدلة أعلاه. يُفتح العمل على المراحل B/C وفق سجل العيوب D-01…D-12، مع احترام القواعد الإحدى عشرة للحزمة (لا إعادة كتابة خلفية، لا حالة UI مشتركة قابلة للتغيير، لا نقل ملكية إلى القشرة، لا بيانات مفبركة، لا حذف قدرات، قابلية اكتشاف «المزيد» محفوظة، نجاح الترجمة ليس تحققًا).
