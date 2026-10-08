package com.onion.macrolearn.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.onion.macrolearn.data.AppDatabase
import com.onion.macrolearn.util.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 매크로 실행 동안 프로세스를 유지하는 포그라운드 서비스. 실제 실행은 [MacroRunner] 가 담당. */
class MacroRunnerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val macroId = intent?.getLongExtra(EXTRA_MACRO_ID, -1L) ?: -1L
        ServiceCompat.startForeground(
            this, Notifier.RUN_NOTIFICATION_ID, Notifier.progress(this, "매크로를 실행하고 있습니다"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        scope.launch {
            try {
                AppDatabase.get(applicationContext).macroDao().getById(macroId)
                    ?.let { MacroRunner(applicationContext).run(it) }
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_MACRO_ID = "macro_id"

        /** 포그라운드 서비스로 매크로 실행 시작. 백그라운드 제한 시 예외가 발생할 수 있다. */
        fun start(context: Context, macroId: Long) {
            val intent = Intent(context, MacroRunnerService::class.java).putExtra(EXTRA_MACRO_ID, macroId)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
