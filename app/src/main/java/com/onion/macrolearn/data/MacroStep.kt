package com.onion.macrolearn.data

import kotlinx.serialization.Serializable

/**
 * 매크로의 한 스텝.
 *
 * - action: "click" (현재는 클릭만 지원)
 * - findBy: "text" | "id" | "bounds" | "dynamic:today" — 1순위 탐색 방식
 * - value: findBy 에 대응하는 값 (dynamic:today 는 "today")
 * - fallback: Laya/사용자에게 보여줄 대체 설명 (contentDescription 등)
 *
 * 3단계 fallback(text → viewId → bounds)을 위해 녹화 시점의 세 가지 식별자를 모두 보관한다.
 */
@Serializable
data class MacroStep(
    val action: String = ACTION_CLICK,
    val findBy: String,
    val value: String,
    val isDynamic: Boolean = false,
    val fallback: String? = null,
    val text: String? = null,
    val viewId: String? = null,
    /** "left,top,right,bottom" 형식의 화면 좌표 */
    val bounds: String? = null,
) {
    companion object {
        const val ACTION_CLICK = "click"
        const val FIND_TEXT = "text"
        const val FIND_ID = "id"
        const val FIND_BOUNDS = "bounds"
        const val FIND_DYNAMIC_TODAY = "dynamic:today"
    }
}
