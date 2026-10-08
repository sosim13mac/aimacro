package com.onion.macrolearn.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime

/** 실행/Laya 호출 진단 로그. 앱 화면에서 확인할 수 있고 logcat(MacroLearn)에도 남는다. */
object RunLog {
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun log(message: String) {
        runCatching { Log.d("MacroLearn", message) }
        val stamp = LocalTime.now().withNano(0)
        _lines.update { (it + "$stamp $message").takeLast(MAX_LINES) }
    }

    fun clear() { _lines.value = emptyList() }

    private const val MAX_LINES = 120
}
