package com.onion.macrolearn.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.onion.macrolearn.data.AppDatabase
import com.onion.macrolearn.service.MacroRunner
import com.onion.macrolearn.service.MacroRunnerService

/** 매일 지정된 시간에 WorkManager 가 호출해 MacroRunnerService 로 매크로를 실행한다. */
class DailyMacroWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_MACRO_ID, -1L)
        val macro = AppDatabase.get(applicationContext).macroDao().getById(id) ?: return Result.failure()
        try {
            MacroRunnerService.start(applicationContext, id)
        } catch (e: Exception) {
            // 백그라운드 포그라운드서비스 시작 제한(Android 12+)에 걸리면 워커 안에서 직접 실행
            MacroRunner(applicationContext).run(macro)
        }
        return Result.success()
    }

    companion object {
        const val KEY_MACRO_ID = "macro_id"
    }
}
