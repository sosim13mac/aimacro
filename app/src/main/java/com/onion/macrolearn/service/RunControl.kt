package com.onion.macrolearn.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 매크로 실행 중 사용자 개입(홈 버튼 등) 감지용 신호.
 * 접근성 서비스가 홈 런처 윈도우 전환을 감지하면 [notifyHome] 을 호출하고,
 * 실행기는 [armed] 상태에서만 이 신호를 받아 실행을 취소한다.
 */
object RunControl {
    /** 대상 앱 실행이 끝나 "홈으로 나가면 취소" 감시를 시작해도 되는 상태 */
    @Volatile var armed = false

    private val _homePressed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val homePressed: SharedFlow<Unit> = _homePressed.asSharedFlow()

    fun notifyHome() {
        if (armed) _homePressed.tryEmit(Unit)
    }
}
