package com.onion.macrolearn.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    // Android 13+ 알림 권한 요청 (실패 보고 알림에 필요)
                    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                    LaunchedEffect(Unit) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    var tab by remember { mutableIntStateOf(0) }
                    Column(Modifier.statusBarsPadding()) {
                        TabRow(selectedTabIndex = tab) {
                            Tab(tab == 0, { tab = 0 }, text = { Text("기록") })
                            Tab(tab == 1, { tab = 1 }, text = { Text("매크로") })
                        }
                        if (tab == 0) RecordScreen() else MacroListScreen()
                    }
                }
            }
        }
    }
}
