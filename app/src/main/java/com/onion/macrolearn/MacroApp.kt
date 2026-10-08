package com.onion.macrolearn

import android.app.Application
import com.onion.macrolearn.ai.LayaConfig

class MacroApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // WorkManager 가 앱을 백그라운드에서 깨워도 저장된 Laya URL 을 쓰도록 여기서 로드
        LayaConfig.init(this)
    }
}
