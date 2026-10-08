package com.onion.macrolearn.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import com.onion.macrolearn.data.MacroStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** UI 와 서비스가 공유하는 녹화 상태 */
object RecorderState {
    val recording = MutableStateFlow(false)
    val steps = MutableStateFlow<List<MacroStep>>(emptyList())
    /** STOP 이후 "동적 값이 있나요?" 다이얼로그를 띄워야 하는지 */
    val reviewPending = MutableStateFlow(false)
    val targetPackage = MutableStateFlow<String?>(null)
    /** 접근성 서비스 연결 여부 */
    val connected = MutableStateFlow(false)

    fun consume() {
        reviewPending.value = false
        steps.value = emptyList()
        targetPackage.value = null
    }
}

/**
 * 녹화(클릭 캡처)와 실행(노드 탐색/클릭/제스처)을 모두 담당하는 접근성 서비스.
 * 실행기는 [instance] 를 통해 rootInActiveWindow 와 제스처 API 를 사용한다.
 */
class MacroRecorderService : AccessibilityService() {

    private var overlay: Button? = null
    private var lastClickKey: String? = null
    private var lastClickAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        RecorderState.connected.value = true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopRecording(openApp = false)
        instance = null
        RecorderState.connected.value = false
        return super.onUnbind(intent)
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // 우리 앱(STOP 버튼, 앱 화면) 이벤트는 녹화/전환 감지에서 제외
        if (event.packageName == packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> windowChangeCount++
            AccessibilityEvent.TYPE_VIEW_CLICKED -> if (RecorderState.recording.value) capture(event)
        }
    }

    /** 클릭된 노드의 text/viewId/bounds/contentDescription 을 스텝으로 저장 */
    private fun capture(event: AccessibilityEvent) {
        val src = event.source ?: return
        val rect = Rect().also(src::getBoundsInScreen)
        val bounds = "${rect.left},${rect.top},${rect.right},${rect.bottom}"
        val now = System.currentTimeMillis()
        // 같은 클릭에 대해 이벤트가 중복 발생하는 경우 무시
        if (bounds == lastClickKey && now - lastClickAt < DEDUP_MS) return
        lastClickKey = bounds
        lastClickAt = now

        val text = NodeFinder.labelOf(src)
        val viewId = src.viewIdResourceName
        val desc = src.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        val (findBy, value) = when {
            !text.isNullOrBlank() -> MacroStep.FIND_TEXT to text
            viewId != null -> MacroStep.FIND_ID to viewId
            else -> MacroStep.FIND_BOUNDS to bounds
        }
        if (RecorderState.targetPackage.value == null) {
            RecorderState.targetPackage.value = event.packageName?.toString()
        }
        RecorderState.steps.value += MacroStep(
            findBy = findBy, value = value, fallback = desc,
            text = text?.takeIf { it.isNotBlank() }, viewId = viewId, bounds = bounds,
        )
    }

    fun startRecording() {
        RecorderState.steps.value = emptyList()
        RecorderState.targetPackage.value = null
        RecorderState.reviewPending.value = false
        RecorderState.recording.value = true
        showStopButton()
    }

    fun stopRecording(openApp: Boolean = true) {
        if (!RecorderState.recording.value) return
        RecorderState.recording.value = false
        hideStopButton()
        RecorderState.reviewPending.value = RecorderState.steps.value.isNotEmpty()
        if (openApp) {
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                startActivity(it)
            }
        }
    }

    // ---- 플로팅 STOP 버튼 ----

    private fun showStopButton() {
        if (overlay != null) return
        val button = Button(this).apply {
            text = "■ STOP"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#D32F2F"))
            setOnClickListener { stopRecording() }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            y = 200
        }
        runCatching { getSystemService(WindowManager::class.java).addView(button, params) }
            .onSuccess { overlay = button }
            .onFailure { RecorderState.recording.value = false } // 오버레이 권한 없음
    }

    private fun hideStopButton() {
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        overlay = null
    }

    // ---- 실행기용 API ----

    /** (x, y) 좌표를 탭한다. 제스처가 완료되면 true */
    suspend fun tap(x: Float, y: Float): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { if (cont.isActive) cont.resume(true) }
            override fun onCancelled(g: GestureDescription?) { if (cont.isActive) cont.resume(false) }
        }, null)
        if (!accepted && cont.isActive) cont.resume(false)
    }

    fun root(): AccessibilityNodeInfo? = rootInActiveWindow

    companion object {
        private const val DEDUP_MS = 300L

        @Volatile var instance: MacroRecorderService? = null
            private set

        /** 윈도우 전환 이벤트 누적 횟수 — 클릭 후 화면 전환 대기에 사용 */
        @Volatile var windowChangeCount = 0L
            private set
    }
}
