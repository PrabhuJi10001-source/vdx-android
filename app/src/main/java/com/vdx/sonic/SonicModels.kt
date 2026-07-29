package com.vdx.sonic

/**
 * VDX Sonic — core data models.
 *
 * All shared types for the 10-layer voice action system.
 */

// ──────────────────────────────────────────────────────────────────
// Bubble States
// ──────────────────────────────────────────────────────────────────

enum class BubbleState {
    IDLE,
    LISTENING,
    PROCESSING,
    CLARIFICATION_REQUIRED,
    EXECUTING,
    DONE,
    ERROR,
    BLOCKED_PERMISSION
}

// ──────────────────────────────────────────────────────────────────
// Capture Metadata
// ──────────────────────────────────────────────────────────────────

data class CaptureSession(
    val audioData: ShortArray,
    val timestamp: Long,
    val foregroundPackage: String?,
    val focusedFieldState: FocusedFieldState?,
    val uiSnapshot: ScreenModel?
)

data class FocusedFieldState(
    val text: String?,
    val hint: String?,
    val isEditable: Boolean,
    val bounds: Rect?
)

// ──────────────────────────────────────────────────────────────────
// Voice Understanding
// ──────────────────────────────────────────────────────────────────

data class AsrResult(
    val text: String,
    val segments: List<TranscriptSegment>? = null,
    val language: String? = null,
    val confidence: Float = 0.0f,
    val provider: String = "unknown"
)

data class TranscriptSegment(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val confidence: Float
)

data class CleanupResult(
    val cleanedText: String,
    val originalText: String,
    val confidence: Float,
    val provider: String
)

data class EntityRepairResult(
    val repairedText: String,
    val originalText: String,
    val entities: List<RepairedEntity>,
    val overallConfidence: Float
)

data class RepairedEntity(
    val original: String,
    val repaired: String,
    val source: EntitySource,
    val confidence: Float
)

enum class EntitySource {
    INSTALLED_APP,
    CONTACT,
    PLACE,
    VOCABULARY,
    RECENT_COMMAND,
    UI_LABEL,
    PHONETIC_MATCH,
    UNCHANGED
}

// ──────────────────────────────────────────────────────────────────
// Intent
// ──────────────────────────────────────────────────────────────────

enum class IntentMode {
    COMMAND,
    DICTATION,
    EDIT,
    READ,
    NAVIGATION,
    SYSTEM_QUERY
}

enum class IntentType {
    // Communication
    CALL,
    WHATSAPP,
    SMS,
    EMAIL,

    // Transport
    BOOK_RIDE,

    // App control
    APP_LAUNCH,
    APP_SWITCH,
    GO_BACK,
    GO_HOME,

    // Search
    SEARCH,
    YOUTUBE_SEARCH,

    // Screen reading
    READ_SCREEN,
    READ_FOCUSED,
    READ_NOTIFICATIONS,

    // System
    SYSTEM_QUERY,
    SETTINGS_NAVIGATION,

    // Editing
    FORM_FILL,
    TEXT_EDIT,

    // Memory
    MEMORY_STORE,
    MEMORY_RECALL,

    // Fallback
    UNKNOWN
}

data class SonicIntent(
    val mode: IntentMode,
    val type: IntentType,
    val targetApp: String? = null,
    val entities: Map<String, String> = emptyMap(),
    val rawText: String = "",
    val confidence: Float = 0.0f,
    val requiresConfirmation: Boolean = false,
    val clarificationNeeded: Boolean = false,
    val clarificationQuestion: String? = null,
    val alternatives: List<SonicIntent>? = null
)

// ──────────────────────────────────────────────────────────────────
// Planner
// ──────────────────────────────────────────────────────────────────

data class ExecutionPlan(
    val intent: SonicIntent,
    val steps: List<ActionStep>,
    val requiresConfirmation: Boolean = false,
    val estimatedDurationMs: Long = 0
)

data class ActionStep(
    val id: String,
    val action: ActionPrimitive,
    val description: String,
    val expectedPostcondition: String? = null,
    val timeoutMs: Long = 5000,
    val retryCount: Int = 2
)

sealed class ActionPrimitive {
    data class OpenApp(val packageName: String) : ActionPrimitive()
    data class WaitForPackage(val packageName: String, val timeoutMs: Long = 8000) : ActionPrimitive()
    data class ReadUiState(val timeoutMs: Long = 2000) : ActionPrimitive()
    data class FindNode(val selector: NodeSelector) : ActionPrimitive()
    data class FocusNode(val selector: NodeSelector) : ActionPrimitive()
    data class SetText(val selector: NodeSelector, val text: String) : ActionPrimitive()
    data class ClickNode(val selector: NodeSelector) : ActionPrimitive()
    data class LongClickNode(val selector: NodeSelector) : ActionPrimitive()
    data class ScrollContainer(val selector: NodeSelector, val direction: ScrollDirection) : ActionPrimitive()
    data class SelectOption(val selector: NodeSelector) : ActionPrimitive()
    data class ReadVisibleResult(val selector: NodeSelector) : ActionPrimitive()
    data class AskUser(val question: String) : ActionPrimitive()
    data class WaitForUserConfirmation(val prompt: String) : ActionPrimitive()
    data class DispatchGesture(val x: Float, val y: Float, val type: GestureType) : ActionPrimitive()
    object GoBack : ActionPrimitive()
    data class FailWithReason(val reason: String) : ActionPrimitive()
}

data class NodeSelector(
    val text: String? = null,
    val contentDescription: String? = null,
    val hint: String? = null,
    val className: String? = null,
    val resourceId: String? = null,
    val isEditable: Boolean? = null,
    val isClickable: Boolean? = null,
    val isFocused: Boolean? = null,
    val index: Int? = null
)

enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }
enum class GestureType { TAP, SWIPE_UP, SWIPE_DOWN, SWIPE_LEFT, SWIPE_RIGHT }

// ──────────────────────────────────────────────────────────────────
// Harness (Semantic UI)
// ──────────────────────────────────────────────────────────────────

data class ScreenModel(
    val packageName: String,
    val activityName: String? = null,
    val isEditableFieldFocused: Boolean = false,
    val focusedFieldText: String? = null,
    val focusedFieldHint: String? = null,
    val elements: List<UiElement> = emptyList(),
    val clickableElements: List<UiElement> = emptyList(),
    val editableElements: List<UiElement> = emptyList(),
    val scrollableContainers: List<UiElement> = emptyList(),
    val windowCount: Int = 1,
    val timestamp: Long = System.currentTimeMillis()
)

data class UiElement(
    val ref: String,
    val text: String? = null,
    val contentDescription: String? = null,
    val hint: String? = null,
    val className: String = "",
    val packageName: String = "",
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val isFocused: Boolean = false,
    val isScrollable: Boolean = false,
    val isChecked: Boolean? = null,
    val bounds: Rect? = null,
    val childCount: Int = 0,
    val supportedActions: List<String> = emptyList()
)

data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

// ──────────────────────────────────────────────────────────────────
// Execution Result
// ──────────────────────────────────────────────────────────────────

sealed class ExecutionResult {
    data class Success(val message: String, val stepsCompleted: Int) : ExecutionResult()
    data class ClarificationNeeded(val question: String, val context: SonicIntent) : ExecutionResult()
    data class ConfirmationNeeded(val prompt: String, val plan: ExecutionPlan) : ExecutionResult()
    data class Failed(val reason: String, val step: String? = null, val recoverable: Boolean = false) : ExecutionResult()
    data class Cancelled(val reason: String = "User cancelled") : ExecutionResult()
}

// ──────────────────────────────────────────────────────────────────
// Diagnostics
// ──────────────────────────────────────────────────────────────────

data class HealthReport(
    val microphone: HealthCheck = HealthCheck(),
    val overlay: HealthCheck = HealthCheck(),
    val accessibility: HealthCheck = HealthCheck(),
    val batteryOptimization: HealthCheck = HealthCheck(),
    val serviceRunning: HealthCheck = HealthCheck(),
    val harnessReady: HealthCheck = HealthCheck(),
    val networkAvailable: HealthCheck = HealthCheck()
)

data class HealthCheck(
    val ok: Boolean = false,
    val message: String = "",
    val fixAction: HealthFixAction? = null
)

enum class HealthFixAction {
    OPEN_MIC_SETTINGS,
    OPEN_OVERLAY_SETTINGS,
    OPEN_ACCESSIBILITY_SETTINGS,
    OPEN_BATTERY_SETTINGS,
    START_SERVICE,
    ENABLE_HARNESS,
    CONFIGURE_NETWORK
}

// ──────────────────────────────────────────────────────────────────
// Clarification
// ──────────────────────────────────────────────────────────────────

data class ClarificationRequest(
    val id: String,
    val question: String,
    val type: ClarificationType,
    val options: List<String>? = null,
    val context: SonicIntent? = null
)

enum class ClarificationType {
    ENTITY_DISAMBIGUATION,
    SPELLING_CONFIRMATION,
    CONTACT_SELECTION,
    ACTION_CONFIRMATION,
    DESTRUCTIVE_ACTION,
    AMBIGUOUS_INTENT
}

// ──────────────────────────────────────────────────────────────────
// App Adapter
// ──────────────────────────────────────────────────────────────────

interface AppAdapter {
    val packageName: String
    val displayName: String

    suspend fun findContactField(screen: ScreenModel): UiElement?
    suspend fun findMessageField(screen: ScreenModel): UiElement?
    suspend fun findSendButton(screen: ScreenModel): UiElement?
    suspend fun findSearchField(screen: ScreenModel): UiElement?
    suspend fun findDestinationField(screen: ScreenModel): UiElement?
    suspend fun findPriceInfo(screen: ScreenModel): String?
    suspend fun findConfirmButton(screen: ScreenModel): UiElement?
    suspend fun findCallButton(screen: ScreenModel): UiElement?
    suspend fun findDialPad(screen: ScreenModel): UiElement?
}
