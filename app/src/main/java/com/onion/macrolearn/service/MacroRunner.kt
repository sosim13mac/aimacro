package com.onion.macrolearn.service

import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import com.onion.macrolearn.ai.LayaClient
import com.onion.macrolearn.ai.LayaConfig
import com.onion.macrolearn.data.Macro
import com.onion.macrolearn.data.MacroStep
import com.onion.macrolearn.util.Notifier
import com.onion.macrolearn.util.RunLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

sealed interface RunResult {
    data object Success : RunResult
    /** 사용자가 홈 버튼을 눌러 중단 */
    data object Cancelled : RunResult
    data class Failed(val stepNo: Int, val reason: String) : RunResult
}

/**
 * 저장된 매크로를 순차 실행하는 엔진.
 * 스텝마다: 팝업 감지(Laya) → 노드 탐색(3단계 fallback) → 클릭.
 * 노드를 못 찾으면 Laya 에 대안 행동을 묻고, [MAX_ATTEMPTS]회 실패하면 알림으로 보고한다.
 */
class MacroRunner(
    private val context: Context,
    private val laya: LayaClient = LayaClient(),
) {

    /** 실행 중 홈 버튼(홈 런처로의 전환)이 감지되면 즉시 취소한다. */
    suspend fun run(macro: Macro): RunResult = coroutineScope {
        val body = async { runSteps(macro) }
        // UNDISPATCHED: 신호 수집이 확실히 시작된 뒤 본문이 진행되도록
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            RunControl.homePressed.first()
            body.cancel(CancellationException("홈 버튼으로 취소"))
        }
        try {
            body.await()
        } catch (e: CancellationException) {
            // 바깥 코루틴이 취소된 게 아니라 홈 버튼으로 body 만 취소된 경우
            if (!isActive) throw e
            Notifier.reportCancelled(context, macro.name)
            RunResult.Cancelled
        } finally {
            watcher.cancel()
            RunControl.armed = false
        }
    }

    private suspend fun runSteps(macro: Macro): RunResult {
        // 동시에 두 매크로가 화면을 조작하지 않도록 직렬화
        if (!lock.tryLock()) return RunResult.Failed(0, "다른 매크로가 실행 중입니다")
        try {
            RunLog.log("매크로 '${macro.name}' 시작 (스텝 ${macro.steps.size}개, 앱=${macro.targetPackage ?: "현재 화면"}, Laya=${LayaConfig.baseUrl()})")
            val svc = MacroRecorderService.instance
                ?: return fail(macro, 0, "접근성 서비스가 꺼져 있습니다")
            launchTargetApp(svc, macro)
            RunControl.armed = true // 이후 홈으로 나가면 취소
            macro.steps.forEachIndexed { i, step ->
                if (!runStep(svc, step, i + 1)) return fail(macro, i + 1, "'${describe(step)}' 을(를) ${MAX_ATTEMPTS}회 시도했지만 실패")
            }
            Notifier.reportSuccess(context, macro.name)
            return RunResult.Success
        } finally {
            lock.unlock()
        }
    }

    private fun fail(macro: Macro, stepNo: Int, reason: String): RunResult {
        RunLog.log("실패: ${stepNo}번째 스텝 — $reason")
        Notifier.reportFailure(context, macro.name, stepNo, reason)
        return RunResult.Failed(stepNo, reason)
    }

    private suspend fun launchTargetApp(svc: MacroRecorderService, macro: Macro) {
        val pkg = macro.targetPackage ?: return
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
        svc.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        delay(LAUNCH_WAIT_MS)
    }

    /** 한 스텝을 최대 [MAX_ATTEMPTS]회 시도. 성공(또는 건너뜀) 시 true */
    private suspend fun runStep(svc: MacroRecorderService, step: MacroStep, no: Int): Boolean {
        repeat(MAX_ATTEMPTS) { attempt ->
            RunLog.log("스텝 $no '${describe(step)}' 시도 ${attempt + 1}/$MAX_ATTEMPTS")
            // (a) 팝업 감지 — 스텝당 첫 시도에서만 수행해 호출 수를 줄인다
            if (attempt == 0) svc.root()?.let { closePopupIfNeeded(svc, it) }

            // 스플래시/광고 등으로 화면이 늦게 뜰 수 있어 노드가 나타날 때까지 폴링
            val target = awaitTarget(svc, step)
            if (target != null && click(svc, target)) {
                RunLog.log("  클릭 성공")
                awaitScreenSettled()
                return true
            }
            // 팝업 닫기 스텝은 팝업이 없거나 이미 위에서 닫혔을 수 있으므로 못 찾아도 통과
            if (looksLikeCloseStep(step)) { RunLog.log("  닫기 스텝: 대상 없음, 건너뜀"); return true }

            // (b) 화면이 예상과 다름 → Laya 에 대안 행동 질의
            RunLog.log("  노드를 찾지 못함 → Laya 에 대안 질의")
            askLayaForAlternative(svc, step)
        }
        return false
    }

    private suspend fun awaitTarget(svc: MacroRecorderService, step: MacroStep): Target? {
        val timeout = if (looksLikeCloseStep(step)) CLOSE_FIND_TIMEOUT_MS else FIND_TIMEOUT_MS
        var waited = 0L
        while (true) {
            svc.root()?.let { root -> NodeFinder.find(root, step)?.let { return it } }
            if (waited >= timeout) return null
            delay(POLL_MS); waited += POLL_MS
        }
    }

    private suspend fun click(svc: MacroRecorderService, target: Target): Boolean {
        val clicked = target.node?.let(NodeFinder::clickableAncestor)
            ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        if (clicked) return true
        // 노드 클릭 실패/노드 없음 → 좌표 탭
        return svc.tap(target.rect.exactCenterX(), target.rect.exactCenterY())
    }

    /** 클릭 후 윈도우 전환(최대 2초)을 기다린 뒤 화면이 안정되도록 잠시 대기 */
    private suspend fun awaitScreenSettled() {
        val before = MacroRecorderService.windowChangeCount
        var waited = 0L
        while (MacroRecorderService.windowChangeCount == before && waited < WINDOW_WAIT_MS) {
            delay(100); waited += 100
        }
        delay(SETTLE_MS)
    }

    // ---- Laya 판단 ----

    /** 팝업이 있다고 Laya 가 확신(>=0.8)하면 닫기 버튼을 눌러 true 반환 */
    private suspend fun closePopupIfNeeded(svc: MacroRecorderService, root: AccessibilityNodeInfo): Boolean {
        val screen = NodeFinder.allScreenText(root)
        if (screen.isBlank()) return false
        if (!laya.confirm(screen, "has_popup", "광고나 이벤트 팝업을 닫아야 하는가?")) return false

        val closeNode = CLOSE_KEYWORDS.firstNotNullOfOrNull { NodeFinder.findNodeByText(root, it, exactOnly = true) }
            ?: CLOSE_KEYWORDS.firstNotNullOfOrNull { k ->
                NodeFinder.findNodeByText(root, k)?.takeIf { it.isClickable || NodeFinder.clickableAncestor(it) != null }
            }
        val target = closeNode?.let(NodeFinder::targetOf)
            ?: run {
                // 키워드로 못 찾으면 Laya 에게 눌러야 할 버튼을 고르게 한다
                val labels = NodeFinder.clickableLabels(root)
                val picked = laya.choose(screen, "close_target", "팝업을 닫으려면 어떤 버튼을 눌러야 하는가?", labels)
                    ?: return false
                NodeFinder.findNodeByText(root, picked)?.let(NodeFinder::targetOf)
            }
        if (target == null || !click(svc, target)) return false
        delay(SETTLE_MS)
        return true
    }

    /** 노드를 못 찾았을 때 "지금 무엇을 해야 하는가?" 를 묻고 그 결과를 수행 */
    private suspend fun askLayaForAlternative(svc: MacroRecorderService, step: MacroStep) {
        val root = svc.root() ?: run { delay(RETRY_WAIT_MS); return }
        val labels = NodeFinder.clickableLabels(root)
        val options = labels + OPTION_WAIT + OPTION_BACK
        val answer = laya.choose(
            state = NodeFinder.allScreenText(root),
            id = "next_action",
            instructions = "목표는 '${describe(step)}' 을(를) 누르는 것이다. 현재 화면에서 지금 무엇을 해야 하는가?",
            options = options,
        )
        when (answer) {
            null, OPTION_WAIT -> delay(RETRY_WAIT_MS)
            OPTION_BACK -> { svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK); delay(SETTLE_MS) }
            else -> NodeFinder.findNodeByText(root, answer)
                ?.let(NodeFinder::targetOf)
                ?.let { if (click(svc, it)) awaitScreenSettled() }
        }
    }

    private fun looksLikeCloseStep(step: MacroStep): Boolean =
        step.text in CLOSE_KEYWORDS || step.fallback?.contains("팝업") == true

    private fun describe(step: MacroStep): String = when (step.findBy) {
        MacroStep.FIND_DYNAMIC_TODAY -> step.fallback ?: "오늘 날짜"
        else -> step.text ?: step.fallback ?: step.value
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        private const val LAUNCH_WAIT_MS = 3000L
        private const val RETRY_WAIT_MS = 1500L
        private const val FIND_TIMEOUT_MS = 8000L
        private const val CLOSE_FIND_TIMEOUT_MS = 2000L
        private const val POLL_MS = 500L
        private const val SETTLE_MS = 600L
        private const val WINDOW_WAIT_MS = 2000L
        private const val OPTION_WAIT = "대기"
        private const val OPTION_BACK = "뒤로가기"
        private val CLOSE_KEYWORDS = listOf("닫기", "닫음", "Close", "close", "×", "X")
        private val lock = Mutex()
    }
}
