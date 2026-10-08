package com.onion.macrolearn.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.onion.macrolearn.data.MacroStep
import java.time.LocalDate

/** 탐색 결과. node 가 null 이면 좌표([rect])만으로 탭해야 한다. */
data class Target(val node: AccessibilityNodeInfo?, val rect: Rect)

/** 접근성 노드 탐색 로직: text → viewId → bounds(좌표) 3단계 fallback */
object NodeFinder {

    /** 스텝의 1순위 방식부터 시작해 text → id → bounds 순으로 시도한다. */
    fun find(root: AccessibilityNodeInfo, step: MacroStep): Target? {
        if (step.findBy == MacroStep.FIND_DYNAMIC_TODAY) {
            // 동적 날짜: 오늘의 일(day)로 변환해 텍스트로 찾는다
            val today = LocalDate.now().dayOfMonth.toString()
            return findNodeByText(root, today, exactOnly = true)?.let(::targetOf)
        }
        val order = listOf(step.findBy, MacroStep.FIND_TEXT, MacroStep.FIND_ID, MacroStep.FIND_BOUNDS).distinct()
        for (mode in order) {
            val value = when {
                mode == step.findBy -> step.value
                mode == MacroStep.FIND_TEXT -> step.text
                mode == MacroStep.FIND_ID -> step.viewId
                else -> step.bounds
            }?.takeIf { it.isNotBlank() } ?: continue
            val target = when (mode) {
                MacroStep.FIND_TEXT -> findNodeByText(root, value)?.let(::targetOf)
                MacroStep.FIND_ID -> findNodeById(root, value)?.let(::targetOf)
                MacroStep.FIND_BOUNDS -> findByBounds(root, value)
                else -> null
            }
            if (target != null) return target
        }
        return null
    }

    /**
     * 정확히 일치하는 노드를 우선, 없으면 부분 일치 첫 노드(exactOnly 면 null).
     * text 가 숫자(날짜)면 "8" 뿐 아니라 "8일" 도 정확 일치로 본다.
     */
    fun findNodeByText(root: AccessibilityNodeInfo, text: String, exactOnly: Boolean = false): AccessibilityNodeInfo? {
        val candidates = root.findAccessibilityNodeInfosByText(text).orEmpty().filter { it.isVisibleToUser }
        val exact = candidates.filter { matchesExact(it, text) }
        // 클릭 가능한 노드(또는 클릭 가능한 조상이 있는 노드)를 우선
        exact.firstOrNull { clickableAncestor(it) != null }?.let { return it }
        exact.firstOrNull()?.let { return it }
        return if (exactOnly) null else candidates.firstOrNull()
    }

    fun findNodeById(root: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByViewId(viewId).orEmpty().firstOrNull { it.isVisibleToUser }

    private fun findByBounds(root: AccessibilityNodeInfo, raw: String): Target? {
        val rect = parseBounds(raw) ?: return null
        // 같은 영역의 노드가 있으면 노드 클릭, 없으면 좌표 탭
        val node = walk(root).firstOrNull { n -> Rect().also(n::getBoundsInScreen) == rect && n.isVisibleToUser }
        return Target(node, rect)
    }

    private fun matchesExact(n: AccessibilityNodeInfo, text: String): Boolean {
        val t = n.text?.toString()?.trim()
        val d = n.contentDescription?.toString()?.trim()
        val dayForm = if (text.toIntOrNull() != null) "${text}일" else null
        return t == text || d == text || (dayForm != null && (t == dayForm || d == dayForm))
    }

    /** 노드 자신 또는 가까운 조상 중 클릭 가능한 노드 */
    fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        repeat(MAX_ANCESTOR_DEPTH) {
            val n = cur ?: return null
            if (n.isClickable) return n
            cur = n.parent
        }
        return null
    }

    fun targetOf(node: AccessibilityNodeInfo) =
        Target(node, Rect().also(node::getBoundsInScreen))

    /** 클릭 대상의 표시 문구: text → contentDescription → 하위 노드 텍스트 */
    fun labelOf(node: AccessibilityNodeInfo): String? =
        node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?: walk(node).drop(1).firstNotNullOfOrNull { it.text?.toString()?.takeIf(String::isNotBlank) }

    /** 화면의 모든 텍스트 (Laya state 용) */
    fun allScreenText(root: AccessibilityNodeInfo): String =
        walk(root).mapNotNull { n ->
            (n.text ?: n.contentDescription)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }.distinct().joinToString("\n")

    /** 현재 화면에서 클릭 가능한 요소의 라벨 목록 (Laya 선택지 후보) */
    fun clickableLabels(root: AccessibilityNodeInfo, limit: Int = 20): List<String> =
        walk(root).filter { it.isClickable && it.isVisibleToUser }
            .mapNotNull(::labelOf).map { it.trim().take(30) }
            .distinct().take(limit).toList()

    private fun walk(root: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        yield(root)
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            yieldAll(walk(child))
        }
    }

    private fun parseBounds(raw: String): Rect? {
        val p = raw.split(",").mapNotNull { it.trim().toIntOrNull() }
        return if (p.size == 4) Rect(p[0], p[1], p[2], p[3]) else null
    }

    private const val MAX_ANCESTOR_DEPTH = 6
}
