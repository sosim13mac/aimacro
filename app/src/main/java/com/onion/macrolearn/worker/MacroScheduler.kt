package com.onion.macrolearn.worker

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** 매크로별 "매일 지정 시각" 예약을 WorkManager PeriodicWork 로 관리한다. */
object MacroScheduler {
    const val DEFAULT_HOUR = 9

    private fun uniqueName(macroId: Long) = "daily_macro_$macroId"

    fun schedule(context: Context, macroId: Long, hour: Int = DEFAULT_HOUR) {
        val request = PeriodicWorkRequestBuilder<DailyMacroWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delayUntilNext(hour), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(DailyMacroWorker.KEY_MACRO_ID to macroId))
            .build()
        // UPDATE: 이미 예약된 경우에도 시각을 갱신한다
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(uniqueName(macroId), ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context, macroId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName(macroId))
    }

    /** 지금부터 다음 [hour]:00 까지의 밀리초 (이미 지났으면 내일) */
    internal fun delayUntilNext(hour: Int, now: LocalDateTime = LocalDateTime.now()): Long {
        var next = now.toLocalDate().atTime(LocalTime.of(hour, 0))
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next).toMillis()
    }
}
