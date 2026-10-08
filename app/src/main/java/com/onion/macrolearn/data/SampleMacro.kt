package com.onion.macrolearn.data

/**
 * 동작 확인용 샘플 매크로: 전체메뉴 → 팝업 닫기 → 출석 메뉴 → 오늘 날짜 클릭.
 * 실제 앱의 문구에 맞게 text 값만 바꿔서 사용한다. (팝업 닫기는 팝업이 없으면 건너뛴다)
 */
object SampleMacro {
    fun create(): Macro = Macro(
        name = "샘플: 출석체크",
        steps = listOf(
            MacroStep(findBy = MacroStep.FIND_TEXT, value = "전체메뉴", text = "전체메뉴"),
            MacroStep(findBy = MacroStep.FIND_TEXT, value = "닫기", text = "닫기", fallback = "팝업 닫기"),
            MacroStep(findBy = MacroStep.FIND_TEXT, value = "출석", text = "출석", fallback = "출석 메뉴"),
            MacroStep(
                findBy = MacroStep.FIND_DYNAMIC_TODAY, value = "today",
                isDynamic = true, fallback = "오늘 날짜",
            ),
        ),
    )
}
