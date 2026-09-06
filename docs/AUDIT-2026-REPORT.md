# تقرير التحويل — AI-V0 Production Candidate (سبتمبر 2026)

**الفرع:** `production-candidate-2026` (commit `01156d4`) — مبني على `main @ f698c51`
**الحالة النهائية:** compile ✅ | 185/185 unit tests ✅ | assembleDebug ✅ (APK ~98MB)

---

## 1. نقطة البداية الحقيقية

المستودع على `main` **لم يكن يُبنى أصلاً**:
- فهارس Room في `TelemetryEntities.kt` + كل استعلامات `TelemetryDaos.kt` استخدمت أسماء أعمدة snake_case بينما الكيانات camelCase → فشل KSP كاملاً.
- أخطاء compile في `DecisionIntelligenceService`, `MemoryLifecycleService`, `TelemetryService`, `SearchIntelligenceService`, `WorkflowPersistenceService`, `MainViewModel`.
- كل "طبقة Intelligence" من آخر commit كانت كوداً غير مُصنّف مطلقاً (لم يُبنَ ولا اختُبر يوماً).

## 2. أهم ما أُصلح فعلياً

### أ. Tool Calling (كان مكسوراً في 3 نقاط)
1. `GeminiLlmAdapter`: كان يتجاهل `availableTools` كلياً. الآن: يرسل `functionDeclarations`، يفسّر `functionCall` في stream وgenerate، ويُرجّع النتائج عبر `functionResponse`.
2. `OpenAiCompatibleLlmAdapter`: كان stream وهمياً (generate + chunk واحد). أُعيدت كتابته: SSE حقيقي، tool calling، كشف isLocal صادق (Ollama/LM Studio تعمل offline)، أحداث usage/completed.
3. `ExecutionService`: كانت وسائط الأداة من النموذج تُمرَّر كسلسلة `rawJson` لا تقرأها الأدوات (FileSystemTool كانت تنفّذ "list" صامتاً دائماً). الآن تُفكَّك JSON إلى بارامترات الأداة الحقيقية، والفشل يعود للنموذج كخطأ قابل للاسترداد.

### ب. Multi-Agent Delegation (كان وهمياً 100%)
`DELEGATE/SELECT_AGENT` كانت تُرجع "تم تعيين وتوجيه المهمة..." دون تنفيذ أي وكيل. الآن:
- تحليل الوكيل الهدف من Registry + رفض التفويض الذاتي + سقف عمق 2 مستويات.
- ميزانية الابن تُقتطع من ميزانية الأب + timeout + إلغاء بنيوي (إلغاء الأب يُلغي الابن).
- الابن يُنفّذ عبر نفس الحلقة المغلقة ويُخزَّن كصف مهمة مستقل (parentTaskId/delegationDepth) ويمتلك trace خاصاً.
- `DecisionService` يولّد مرشحات DELEGATE حقيقية عندما يفتقد الوكيل قدرات يملكها وكيل مسجّل آخر.
- `CapabilityType.AGENT_DELEGATION` مُضافة؛ الوكيل الشامل يملكها.

### ج. Durable Execution / Recovery
- Checkpoint لكل خطوة في الحلقة (step, evidence, output, tokens) → `tasks.checkpointJson` + ترحيل DB v8→v9.
- `resumeTask` يستعيد الحالة بدل إعادة التشغيل من الصفر (وأصلح تحويل JSONArrays→Lists الذي كان سيكسر حقن الأدلة المستعادة).
- `bootstrapRuntime()` كانت **كوداً ميتاً لا يُستدعى أبداً** — تُستدعى الآن من MainActivity، وتُشغّل مسح الاستئناف للمهام المقاطَعة بفعل موت العملية (bounded، top-level فقط).
- الإلغاء يخزّن `CANCELLED` (كانت الصفوف تبقى RUNNING أبدًا — مهام زومبي).
- التنفيذ انتقل من `viewModelScope` إلى `ExecutionHost` (نطاق تطبيق) + `AgentExecutionForegroundService` (foregroundServiceType=dataSync) — الخروج من الشاشة لا يقتل المهمة، وخدمة Foreground ترفع أولوية العملية.
- `WorkflowEngine` أصبح يخزّن start/checkpoint/steps/terminal عبر `WorkflowPersistenceService` (كانت موجودة غير موصولة).

### د. Security Enforcement
- `PermissionGrantService` كانت **نظاماً زخرفياً**: لا شيء يستدعيها. الآن `executeTool`/`executeMcpAction`/`handleToolExecution` تفرضها: الأدوات الحساسة وكل أدوات MCP تتطلب منحاً صريحاً (AGENT→TOOL→EXECUTE) — الدفاع fail-closed، وكل قرار ALLOW/DENY يُدوَّن في `audit_trail` عبر TelemetryPort.
- اختبار PATH I يثبت: الرفض **يمنع التنفيذ فعلياً** (executeCount=0) + حدث تدقيق DENY.

### هـ. Observability
- `TelemetryService` موصولة بناشر أحداث المنسق (`executionEventPublisher`): كل تنفيذ ينتج ExecutionTraceNodes + مقاييس + تدقيق محفوظة في Room (كانت البنية كلها ميتة).
- أُصلحت خرائط الأحداث (selectedAction→chosenAction، stepIndex، إلخ).

### و. Local-First RAG
- **تضمين دلالي حقيقي على الجهاز**: `OnnxSemanticEmbeddingAdapter` — MiniLM-L6-v2 int8 عبر ONNX Runtime (أُضيف للـ APK)، تزويد كسول بمنزّل ~23MB مرة واحدة، محلل WordPiece مكتوب بـ Kotlin خالص، mean-pooling + L2 normalization.
- الصدق: قبل التزويد يُعلن عدم التوفر صريحاً (بدون ناقلات مزيفة)؛ و`EmbeddingQualityMarker.isSemantic=false` للمولّد الهاشي جعل RAG يوسم النتائج `LEXICAL_FALLBACK` بدل ادعاء `HYBRID` دلالي زائف.
- `ArabicTextNormalizer` (همزات/ة/ى/تشكيل/تطويل) في التسجيل المعجمي + التضمين الاحتياطي.
- إثبات ملكية المتجه: كل chunk يخزّن `embeddingResourceId` + فرض حدود التوافق فعلياً.
- `RETRIEVE_KNOWLEDGE` في حلقة الوكيل كانت **تتجاهل RAG كلياً** (memory فقط) — موصولة الآن بخط الـ RAG الكامل.

### ز. أخطاء إنتاجية اكتشفتها الاختبارات وأصلحتها
- `RagIntelligenceService`: نتائج RRF (فضاء ranks ~0.02) تُرشَّح ضد عتبة تشابه 0.2 → **خط v2 RAG لم يكن يستطيع إرجاع أي نتيجة أبداً**. نُقلت العتبة لفضاء التشابه.
- `SearchIntelligenceService`: `ZonedDateTime.parse(ISO_INSTANT)` يرمي دائماً → كل التواريخ تتحول صمتاً إلى 0.5 وترتيب TEMPORAL لم يكن يعمل أبداً.
- `ToolLifecycleService`: validate/authorize كانا معطّلين لكل الأدوات (declarationProvider هش) — أُضيف declaration cache وسلك استعلام سليم.

## 3. Golden Paths — تم التحقق منها باختبارات حقيقية (`GoldenPathTest`)
| المسار | التحقق |
|---|---|
| A: LLM→ToolRequested→تفكيك وسائط→تنفيذ أداة→ToolResult | ✅ (`tool.executeCount==1`, الوسائط الصحيحة) |
| B: RETRIEVE_KNOWLEDGE→RAG→أدلة مؤرضة تُحقن في السياق | ✅ |
| C: DELEGATE→وكيل ابن حقيقي→نتيجة→استمرار الأب | ✅ + رفض الذاتي |
| D: Checkpoint persist→restore→round-trip | ✅ (شامل JSON تالف) |
| E/F: Offline+مورد سحابي→فشل صادق (بدون نجاح مزيف) | ✅ |
| I: أداة MCP بدون إذن→منع تنفيذ + تدقيق DENY | ✅ |
| عربية: تطبيع + WordPiece | ✅ |

## 4. القيود المتبقية (صريحاً)
1. **لا جهاز/محاكي Android في بيئة التنفيذ** — لم يُنفَّذ inference الـ ONNX ولا Foreground Service على جهاز فعلي؛ الكود محمي بأخطاء صادقة + fallback، واختبارات المنطق (tokenizer/checkpoints/security) خضراء على JVM. اختبار Robolectric/جهاز مطلوب قبل النشر.
2. اختبارات Robolectric هنا تعمل بتنزيل SDK ديناميكي (تعمل محلياً بعد أول تنزيل).
3. تكامل WorkspaceContextEngine في تسجيل الأحداث الحية للتوصيات موجود لكن سطح التوصيات ما زال heuristic-only.
4. ذاكرة storeMemory العامة لا تمرر workspace/agent scope (المسار المُنطَّق `storeScoped/retrieveScoped` موجود في MemoryLifecycleService) — يحتاج إحلال شامل في المستودع.
5. الـ UI (شاشة RAG) لم تُربط بزر "تجهيز النموذج الدلالي المحلي" — الدالة `MainViewModel.provisionLocalSemanticModel()` جاهزة.
6. Foreground service يتطلب منح POST_NOTIFICATIONS على API 33+ (التدهور صادق: التنفيذ مستمر بلا إشعار).

## 5. المخاطر المتبقية (بالأولوية)
1. **عالي:** التحقق على جهاز Android فعلي (ONNX inference، السلوك الدلالي، الخدمة الأمامية).
2. **متوسط:** تغطية اختبار لمسارات ProviderControlPlane الكاملة (validate/materialize عبر شبكة حقيقية).
3. **متوسط:** أدوات حساسة داخل التطبيق (حذف ملفات) يُستحسن ربطها بموافقة صريحة في UI.
4. **منخفض:** `criticalFindingsRegister` في الاختبارات ما زال يحوي أوصافاً تاريخية لم تُحدَّث.

## 6. التقييم الصريح
AI-V0 لم يكن منصة عوامل عاملة؛ كان هيكلاً جيداً مع حلقات ميتة في كل capability حرجة تقريباً (تفويض وهمي، tool calling غير موصول، RAG لا يُستشار، صلاحيات زخرفية، استرداد غير مشغّل، تتبع غير موصول، وطبقة intelligence لا تُترجم). الآن: كل هذه السلاسل **مُنفَّذة وموصولة ومُختبرة end-to-end على JVM**، والبناء والاختبارات خضراء بالكامل. البقاء بين "production candidate" و"production ready" يتوقف الآن على التحقق الميداني على أجهزة حقيقية (بند 4.1) — وليس على implementations ناقصة.
