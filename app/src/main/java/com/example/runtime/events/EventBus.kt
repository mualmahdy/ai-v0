package com.example.runtime.events

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SystemEvent(
    val id: Long = System.currentTimeMillis(),
    val topic: String,
    val payload: Map<String, Any> = emptyMap(),
    val summary: String = "",
    val timestamp: String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
)

object EventBus {
    private val _events = MutableSharedFlow<SystemEvent>(replay = 50, extraBufferCapacity = 100)
    val events: SharedFlow<SystemEvent> = _events.asSharedFlow()

    suspend fun publish(topic: String, summary: String, payload: Map<String, Any> = emptyMap()) {
        val event = SystemEvent(
            topic = topic,
            summary = summary,
            payload = payload
        )
        _events.emit(event)
    }

    suspend fun publishAgentStarted(agentName: String, taskPrompt: String) {
        publish("agent.started", "الوكيل $agentName بدأ تنفيذ المهمة", mapOf("agent" to agentName, "prompt" to taskPrompt))
    }

    suspend fun publishAgentCompleted(agentName: String, status: String, durationMs: Long) {
        publish("agent.completed", "أنهى الوكيل $agentName التنفيذ ($status) في ${durationMs}ms", mapOf("agent" to agentName, "status" to status))
    }

    suspend fun publishToolCalled(toolName: String, args: String) {
        publish("tool.called", "استدعاء الأداة: $toolName", mapOf("tool" to toolName, "args" to args))
    }

    suspend fun publishToolCompleted(toolName: String, isSuccess: Boolean) {
        publish("tool.completed", "اكتملت الأداة: $toolName (نجاح=$isSuccess)", mapOf("tool" to toolName, "success" to isSuccess))
    }

    suspend fun publishModelFallback(role: String, originalProvider: String, reason: String) {
        publish("model.fallback", "تحويل مزود النموذج لدور $role بسبب: $reason", mapOf("role" to role, "provider" to originalProvider, "reason" to reason))
    }

    suspend fun publishWorkflowStep(stepId: Int, action: String, status: String) {
        publish("workflow.step", "خطوة سير العمل #$stepId: $action ($status)", mapOf("step" to stepId, "action" to action, "status" to status))
    }

    suspend fun publishMemoryFormed(type: String, content: String) {
        publish("memory.formed", "تكونت ذاكرة جديدة ($type)", mapOf("type" to type, "content" to content.take(60)))
    }
}
