package com.onion.macrolearn.ai

import com.onion.macrolearn.data.MacroStep

/**
 * 녹화된 스텝을 "매일 재사용 가능한" 규칙으로 일반화한다.
 *
 * 규칙:
 * 1. 사용자가 동적(isDynamic)으로 표시한 스텝 → findBy = "dynamic:today"
 *    (값이 1~31 숫자가 아니면 Laya 로 "날짜인가?"를 확인해 0.8 미만이면 정적 유지)
 * 2. 그 외 스텝은 가장 안정적인 식별자를 1순위로 고른다.
 *    텍스트가 비었거나 길거나 숫자를 포함하면(바뀔 수 있음) viewId 우선, 없으면 좌표.
 */
class LayaGeneralizer(private val laya: LayaClient = LayaClient()) {

    suspend fun generalize(steps: List<MacroStep>, dynamicIndices: Set<Int>): List<MacroStep> =
        steps.mapIndexed { index, step ->
            if (index in dynamicIndices && isTodayLike(step)) toDynamic(step) else stabilize(step)
        }

    /** 사용자가 체크하지 않았어도 날짜로 보이는 스텝 후보 (다이얼로그 기본 체크용, 로컬 규칙만 사용) */
    fun suggestDynamic(steps: List<MacroStep>): Set<Int> =
        steps.withIndex().filter { isDayNumber(it.value.text) }.map { it.index }.toSet()

    private suspend fun isTodayLike(step: MacroStep): Boolean {
        if (isDayNumber(step.text)) return true
        val label = step.text ?: step.fallback ?: return false
        return laya.confirm(
            state = "버튼 텍스트: $label",
            id = "is_date",
            instructions = "이 텍스트는 달력의 날짜(일)를 나타내는가?",
        )
    }

    private fun toDynamic(step: MacroStep) = step.copy(
        findBy = MacroStep.FIND_DYNAMIC_TODAY, value = "today", isDynamic = true,
    )

    private fun stabilize(step: MacroStep): MacroStep {
        val text = step.text
        val volatileText = text.isNullOrBlank() || text.length > MAX_STABLE_TEXT || text.any(Char::isDigit)
        val (findBy, value) = when {
            !volatileText -> MacroStep.FIND_TEXT to text!!
            step.viewId != null -> MacroStep.FIND_ID to step.viewId
            !text.isNullOrBlank() -> MacroStep.FIND_TEXT to text
            step.bounds != null -> MacroStep.FIND_BOUNDS to step.bounds
            else -> return step
        }
        return step.copy(findBy = findBy, value = value, isDynamic = false)
    }

    private fun isDayNumber(text: String?): Boolean = text?.trim()?.toIntOrNull()?.let { it in 1..31 } == true

    private companion object {
        const val MAX_STABLE_TEXT = 20
    }
}
