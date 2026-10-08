package com.onion.macrolearn.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import com.onion.macrolearn.data.MacroStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** 녹화 단계: 대기(IDLE) → 앱 선택 후 시작 버튼 대기(READY) → 녹화 중(RECORDING) */
enum class RecordPhase { IDLE, READY, RECORDING }

/** UI 와 서비스가 공유하는 녹화 상태 */
object RecorderState {
    val phase = MutableStateFlow(RecordPhase.IDLE)
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

    private var overlay: LinearLayout? = null
    private var overlayMain: Button? = null
    private var lastClickKey: String? = null
    private var lastClickAt = 0L

    /** 홈 런처 패키지들 — 실행 중 이쪽으로 전환되면 홈 버튼을 누른 것으로 본다 */
    private val homePackages: Set<String> by lazy {
        packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
        ).map { it.activityInfo.packageName }.toSet() - "android"
    }

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
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                windowChangeCount++
                if (event.packageName?.toString() in homePackages) RunControl.notifyHome()
            }
            // 선택한 앱에서, 녹화 시작 버튼을 누른 이후의 클릭만 기록한다
            AccessibilityEvent.TYPE_VIEW_CLICKED ->
                if (RecorderState.phase.value == RecordPhase.RECORDING &&
                    event.packageName?.toString() == RecorderState.targetPackage.value
                ) capture(event)
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
        RecorderState.steps.value += MacroStep(
            findBy = findBy, value = value, fallback = desc,
            text = text?.takeIf { it.isNotBlank() }, viewId = viewId, bounds = bounds,
        )
    }

    /** 앱을 먼저 선택한 뒤 호출: 대상 앱을 열고 "녹화 시작" 플로팅 버튼을 띄운다. */
    fun prepareRecording(pkg: String): Boolean {
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        RecorderState.steps.value = emptyList()
        RecorderState.reviewPending.value = false
        RecorderState.targetPackage.value = pkg
        RecorderState.phase.value = RecordPhase.READY
        showOverlay()
        if (overlay == null) return false // 오버레이 권한 없음
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        return true
    }

    /** 플로팅 버튼의 "녹화 시작" — 이 시점부터 클릭이 기록된다 */
    private fun beginCapture() {
        lastClickKey = null
        RecorderState.phase.value = RecordPhase.RECORDING
        overlayMain?.apply {
            text = "■ STOP"
            setBackgroundColor(Color.parseColor("#D32F2F"))
        }
    }

    /** 준비 상태 취소(✕) */
    private fun cancelReady() {
        hideOverlay()
        RecorderState.phase.value = RecordPhase.IDLE
        RecorderState.targetPackage.value = null
    }

    fun stopRecording(openApp: Boolean = true) {
        if (RecorderState.phase.value == RecordPhase.IDLE) return
        val wasRecording = RecorderState.phase.value == RecordPhase.RECORDING
        RecorderState.phase.value = RecordPhase.IDLE
        hideOverlay()
        RecorderState.reviewPending.value = wasRecording && RecorderState.steps.value.isNotEmpty()
        if (openApp) {
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                startActivity(it)
            }
        }
    }

    // ---- 플로팅 컨트롤 (녹화 시작 / STOP) ----

    private fun showOverlay() {
        if (overlay != null) return
        val main = Button(this).apply {
            text = "● 녹화 시작"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1976D2"))
            setOnClickListener {
                if (RecorderState.phase.value == RecordPhase.READY) beginCapture() else stopRecording()
            }
        }
        val cancel = Button(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#616161"))
            setOnClickListener { if (RecorderState.phase.value == RecordPhase.RECORDING) stopRecording() else cancelReady() }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(main)
            addView(cancel)
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
        runCatching { getSystemService(WindowManager::class.java).addView(panel, params) }
            .onSuccess { overlay = panel; overlayMain = main }
            .onFailure { RecorderState.phase.value = RecordPhase.IDLE } // 오버레이 권한 없음
    }

    private fun hideOverlay() {
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        overlay = null
        overlayMain = null
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
