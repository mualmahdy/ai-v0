package com.example.data.local.db

import com.example.data.local.db.entities.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DatabaseInitializer {
    suspend fun populateInitialData(db: AppDatabase) {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        // 1. Default Project
        val defaultProjectId = db.projectDao().insertProject(
            ProjectEntity(
                name = "المشروع الافتراضي (Workspace)",
                localPath = "workspace/default_project",
                description = "مساحة العمل الأساسية لـ AI-V0 Ultimate Android",
                isDefault = true,
                createdAt = now,
                lastOpenedAt = now
            )
        )

        // 2. Default Session
        db.sessionDao().insertOrUpdateSession(
            SessionEntity(
                sessionId = "default_session",
                projectId = defaultProjectId,
                title = "جلسة العمل المباشرة",
                agentName = "direct",
                messageCount = 1,
                createdAt = now,
                updatedAt = now
            )
        )

        db.messageDao().insertMessage(
            MessageEntity(
                projectId = defaultProjectId,
                sessionId = "default_session",
                role = "assistant",
                content = "مرحباً بك في AI-V0 Ultimate Android!\nالمنظومة الأصلية المستقلة للوكلاء الذكيين وبيئة العمل المتكاملة تعمل الآن محلياً بالكامل (Offline-First) مع إمكانية استخدام النماذج والبحث الخارجي عند توفر الإنترنت.",
                providerName = "local_heuristic",
                modelName = "native-cbr-engine",
                status = "success",
                createdAt = now
            )
        )

        // 3. Default Built-in Configurable Agents
        val defaultAgents = listOf(
            AgentConfigEntity(
                projectId = defaultProjectId,
                name = "direct",
                description = "استجابة فورية سريعة عبر نموذج التوجيه والتنسيق المباشر",
                modelRole = "fast_model",
                systemPrompt = "أنت وكيل مباشر وسريع تقدم إجابات واضحة وحاسمة.",
                budget = 20000,
                createdAt = now
            ),
            AgentConfigEntity(
                projectId = defaultProjectId,
                name = "planner",
                description = "وكيل التخطيط الاستراتيجي لتفكيك الأهداف إلى خطوات ومخطط عمل",
                modelRole = "planner_model",
                systemPrompt = "أنت وكيل التخطيط. قم بتحليل الهدف وتفكيكه إلى خطوات عمل تنفيذية دقيقة.",
                budget = 10000,
                createdAt = now
            ),
            AgentConfigEntity(
                projectId = defaultProjectId,
                name = "code",
                description = "وكيل كتابة وتحليل وتعديل وفحص الأكواد البرمجية",
                modelRole = "coding_model",
                toolsJson = "[\"file_reader\", \"calculator\", \"code_runner\"]",
                systemPrompt = "أنت وكيل برمجي متقدم قادر على فحص وكتابة وتصحيح الأكواد في بيئة العمل.",
                budget = 30000,
                createdAt = now
            ),
            AgentConfigEntity(
                projectId = defaultProjectId,
                name = "research",
                description = "وكيل البحث والتحليل والتعمق في المعرفة والإنترنت",
                modelRole = "search_model",
                toolsJson = "[\"web_search\", \"memory_search\", \"offline_knowledge\"]",
                systemPrompt = "أنت باحث ذكي قادر على جمع المعلومات من مصادر متعددة والربط بينها.",
                budget = 50000,
                createdAt = now
            ),
            AgentConfigEntity(
                projectId = defaultProjectId,
                name = "search",
                description = "وكيل البحث الميداني السريع عن النصوص والمستندات",
                modelRole = "search_model",
                toolsJson = "[\"web_search\", \"offline_knowledge\"]",
                systemPrompt = "أنت وكيل مخصص للبحث السريع وإعادة النتائج المصنفة.",
                budget = 15000,
                createdAt = now
            ),
            AgentConfigEntity(
                projectId = defaultProjectId,
                name = "reviewer",
                description = "وكيل المراجعة وتقييم الجودة وفحص الأخطاء (CBR Evaluation)",
                modelRole = "review_model",
                systemPrompt = "أنت مراجع دقيق يقيم جودة النتائج بدقة ويعطي درجة بين 0 و 1.",
                budget = 15000,
                createdAt = now
            ),
            AgentConfigEntity(
                projectId = defaultProjectId,
                name = "memory",
                description = "وكيل إدارة الذاكرة طويلة المدى واسترجاع التفضيلات والحالات السابقة",
                modelRole = "fast_model",
                toolsJson = "[\"memory_search\"]",
                systemPrompt = "أنت مسؤول الذاكرة طويلة الأمد واسترجاع المعارف والتفضيلات السابقة.",
                budget = 10000,
                createdAt = now
            )
        )
        defaultAgents.forEach { db.agentConfigDao().insertAgent(it) }

        // 4. Default Model Providers
        val localProviderId = db.modelProviderDao().insertProvider(
            ModelProviderEntity(
                projectId = defaultProjectId,
                name = "Local Native Engine",
                providerType = "local_heuristic",
                defaultModel = "native-cbr-engine",
                priority = 1,
                enabled = true,
                isOnlineOnly = false,
                capabilitiesJson = "{\"streaming\": true, \"offline\": true, \"tools\": true}",
                createdAt = now,
                updatedAt = now
            )
        )

        val geminiProviderId = db.modelProviderDao().insertProvider(
            ModelProviderEntity(
                projectId = defaultProjectId,
                name = "Google Gemini Cloud",
                providerType = "gemini",
                baseUrl = "https://generativelanguage.googleapis.com",
                defaultModel = "gemini-3.5-flash",
                priority = 2,
                enabled = true,
                isOnlineOnly = true,
                capabilitiesJson = "{\"streaming\": true, \"offline\": false, \"tools\": true}",
                createdAt = now,
                updatedAt = now
            )
        )

        // Map default model roles
        val roles = listOf(
            "fast_model" to (localProviderId to "native-cbr-engine"),
            "planner_model" to (localProviderId to "native-cbr-engine"),
            "coding_model" to (localProviderId to "native-cbr-engine"),
            "review_model" to (localProviderId to "native-cbr-engine"),
            "search_model" to (localProviderId to "native-cbr-engine"),
            "fallback_model" to (localProviderId to "native-cbr-engine")
        )
        roles.forEach { (roleName, provAndModel) ->
            db.modelProviderDao().setModelRole(
                ModelRoleEntity(
                    projectId = defaultProjectId,
                    roleName = roleName,
                    providerId = provAndModel.first,
                    modelName = provAndModel.second
                )
            )
        }

        // 5. Default Search Providers
        db.searchProviderDao().insertSearchProvider(
            SearchProviderEntity(
                projectId = defaultProjectId,
                name = "Local Knowledge RAG",
                providerType = "offline_knowledge",
                enabled = true,
                priority = 1,
                isOnlineOnly = false,
                createdAt = now
            )
        )
        db.searchProviderDao().insertSearchProvider(
            SearchProviderEntity(
                projectId = defaultProjectId,
                name = "Local Session & Long-term Memory",
                providerType = "local_memory",
                enabled = true,
                priority = 2,
                isOnlineOnly = false,
                createdAt = now
            )
        )
        db.searchProviderDao().insertSearchProvider(
            SearchProviderEntity(
                projectId = defaultProjectId,
                name = "Brave Web Search API",
                providerType = "brave",
                enabled = false,
                priority = 3,
                isOnlineOnly = true,
                createdAt = now
            )
        )

        // 6. Default Embedding Provider
        db.embeddingProviderDao().insertEmbeddingProvider(
            EmbeddingProviderEntity(
                projectId = defaultProjectId,
                name = "Native Token Embedder (Offline)",
                providerType = "local_embedder",
                embeddingModel = "native-bag-of-tokens",
                dimension = 64,
                priority = 1,
                enabled = true,
                isDefault = true
            )
        )

        // 7. Initial Knowledge Documents
        db.knowledgeDao().insertCollection(
            KnowledgeCollectionEntity(
                projectId = defaultProjectId,
                name = "architecture_docs",
                description = "توثيق معمارية CBR-MDP ونظام التشغيل المحلي",
                createdAt = now
            )
        )
        db.knowledgeDao().insertDocument(
            DocumentEntity(
                projectId = defaultProjectId,
                docId = "cbr_mdp_primer",
                title = "نموذج قرار ماركوف المزدوج للموارد والاعتقاد (CBR-MDP)",
                content = "يقوم نموذج CBR-MDP على فضاء الحالة S = X × Y حيث تمثل X الموارد المادية (الأرصدة، الطوابير، حالة الوكلاء) وتمثل Y عناصر المهمة المعرفية (الاعتقاد الاحتمالي، الرسم البياني، الذاكرة). يتميز بقرارات STOP و ADD_NODE و REQUEST لحفظ الموارد والوصول لأعلى جودة.",
                collectionName = "architecture_docs",
                metadataJson = "{\"author\": \"System\", \"version\": \"1.0\"}"
            )
        )

        // 8. Default Long Term Memories
        db.memoryDao().insertMemory(
            LongTermMemoryEntity(
                projectId = defaultProjectId,
                content = "المستخدم يفضل إجابات دقيقة واضحة مع إبراز حالة النظام واستخدام الموارد ومؤشرات الجودة.",
                memoryType = "preference",
                status = "active",
                importance = 0.95f,
                confidence = 1.0f,
                provenance = "system_initialization",
                timestamp = now
            )
        )

        // 9. Initial Workspace Layout Components (Data-driven UI)
        val defaultComponents = listOf(
            WorkspaceComponentEntity("chat_panel", "محادثة الوكلاء", "chat", true, 0, 1.0f),
            WorkspaceComponentEntity("agents_panel", "سجل الوكلاء والميزانيات", "agent", true, 1, 1.0f),
            WorkspaceComponentEntity("code_editor", "محرر الأكواد والملفات", "code", true, 2, 1.0f),
            WorkspaceComponentEntity("terminal_panel", "منصة الأوامر (Terminal)", "terminal", true, 3, 1.0f),
            WorkspaceComponentEntity("workflow_panel", "محرك التدفقات (CBR Workflows)", "workflow", true, 4, 1.0f),
            WorkspaceComponentEntity("knowledge_panel", "قاعدة المعرفة (Local RAG)", "knowledge", true, 5, 1.0f),
            WorkspaceComponentEntity("memory_panel", "الذاكرة طويلة المدى", "memory", true, 6, 1.0f),
            WorkspaceComponentEntity("providers_panel", "مزودو النماذج والبحث", "providers", true, 7, 1.0f),
            WorkspaceComponentEntity("diagnostics_panel", "الموارد والتشخيص الحي", "diagnostics", true, 8, 1.0f),
            WorkspaceComponentEntity("settings_panel", "إعدادات المنظومة", "settings", true, 9, 1.0f)
        )
        db.workspaceComponentDao().insertComponents(defaultComponents)

        // 10. Initial Settings
        db.appSettingDao().setSetting(AppSettingEntity("decision.wasserstein_eta", "0.3"))
        db.appSettingDao().setSetting(AppSettingEntity("decision.discount_gamma", "0.9"))
        db.appSettingDao().setSetting(AppSettingEntity("decision.queue_threshold", "5"))
        db.appSettingDao().setSetting(AppSettingEntity("security.injection_detection", "true"))
        db.appSettingDao().setSetting(AppSettingEntity("network.offline_mode", "false"))
    }
}
