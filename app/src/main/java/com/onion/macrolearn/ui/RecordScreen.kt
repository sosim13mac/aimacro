package com.onion.macrolearn.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.onion.macrolearn.ai.LayaGeneralizer
import com.onion.macrolearn.data.AppDatabase
import com.onion.macrolearn.data.Macro
import com.onion.macrolearn.data.MacroStep
import com.onion.macrolearn.service.MacroRecorderService
import com.onion.macrolearn.service.RecordPhase
import com.onion.macrolearn.service.RecorderState
import com.onion.macrolearn.util.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 설치된 실행 가능 앱 (선택 목록용) */
private data class AppItem(val label: String, val pkg: String)

private fun loadLaunchableApps(ctx: Context): List<AppItem> {
    val pm = ctx.packageManager
    return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .map { AppItem(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
        .filter { it.pkg != ctx.packageName }
        .distinctBy { it.pkg }
        .sortedBy { it.label }
}

/**
 * 앱 선택 → "앱 열고 녹화 준비" → 대상 앱 위에 플로팅 [녹화 시작] → [STOP]
 * → 동적 값 확인 다이얼로그 → Room 저장.
 * 앱을 먼저 고르고 시작 버튼을 눌러야 기록되므로, 홈 버튼/앱 전환 같은 준비 동작은 녹화되지 않는다.
 */
@Composable
fun RecordScreen() {
    val ctx = LocalContext.current
    val phase by RecorderState.phase.collectAsState()
    val reviewPending by RecorderState.reviewPending.collectAsState()
    val steps by RecorderState.steps.collectAsState()
    var selected by remember { mutableStateOf<AppItem?>(null) }
    var picking by remember { mutableStateOf(false) }

    // 설정 화면에서 돌아올 때 권한 상태를 다시 읽는다
    var accessibilityOk by remember { mutableStateOf(Permissions.isAccessibilityEnabled(ctx)) }
    var overlayOk by remember { mutableStateOf(Permissions.canDrawOverlays(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        accessibilityOk = Permissions.isAccessibilityEnabled(ctx)
        overlayOk = Permissions.canDrawOverlays(ctx)
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("패턴 기록", style = MaterialTheme.typography.titleLarge)
        Text("1) 기록할 앱을 고르고 2) '앱 열고 녹화 준비'를 누르면 앱이 열리고 플로팅 [● 녹화 시작] 버튼이 뜹니다. " +
            "3) 시작 버튼을 누른 뒤 평소처럼 한 번 누르고 [■ STOP]으로 끝냅니다.")

        PermissionRow("접근성 서비스", accessibilityOk) { Permissions.openAccessibilitySettings(ctx) }
        PermissionRow("다른 앱 위에 표시", overlayOk) { Permissions.openOverlaySettings(ctx) }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("대상 앱: ${selected?.label ?: "선택 안 됨"}", Modifier.weight(1f))
            OutlinedButton(enabled = phase == RecordPhase.IDLE, onClick = { picking = true }) { Text("앱 선택") }
        }

        Button(
            enabled = accessibilityOk && overlayOk && selected != null && phase == RecordPhase.IDLE,
            onClick = {
                val ok = MacroRecorderService.instance?.prepareRecording(selected!!.pkg) == true
                if (!ok) Toast.makeText(ctx, "앱을 열 수 없거나 권한이 부족합니다", Toast.LENGTH_SHORT).show()
            },
        ) { Text("앱 열고 녹화 준비") }

        when (phase) {
            RecordPhase.READY -> Text("대상 앱에서 [● 녹화 시작]을 눌러 주세요. (✕: 취소)")
            RecordPhase.RECORDING -> Text("기록 중… 캡처된 스텝: ${steps.size}개 (STOP 버튼으로 종료)")
            RecordPhase.IDLE -> Unit
        }
    }

    if (picking) AppPickerDialog(ctx, onPick = { selected = it; picking = false }, onDismiss = { picking = false })
    if (reviewPending) ReviewDialog(ctx, steps)
}

/** 설치된 앱 목록에서 하나를 고르는 다이얼로그 (이름 검색 지원) */
@Composable
private fun AppPickerDialog(ctx: Context, onPick: (AppItem) -> Unit, onDismiss: () -> Unit) {
    var apps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { loadLaunchableApps(ctx) } }
    val filtered = remember(apps, query) { apps.filter { it.label.contains(query, ignoreCase = true) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("앱 선택") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, label = { Text("검색 (예: CU)") }, singleLine = true)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(filtered, key = { it.pkg }) { app ->
                        Text(
                            app.label,
                            Modifier.fillMaxWidth().clickable { onPick(app) }.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onRequest: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("$label: ${if (granted) "허용됨" else "필요"}", Modifier.weight(1f))
        if (!granted) OutlinedButton(onClick = onRequest) { Text("설정 열기") }
    }
}

/** "동적 값이 있나요?" — 날짜처럼 매일 바뀌는 스텝을 체크하고 이름을 정해 저장한다. */
@Composable
private fun ReviewDialog(ctx: Context, steps: List<MacroStep>) {
    val scope = rememberCoroutineScope()
    val generalizer = remember { LayaGeneralizer() }
    var name by remember { mutableStateOf("출석체크") }
    var saving by remember { mutableStateOf(false) }
    // 1~31 숫자 텍스트는 날짜일 가능성이 높아 기본 체크
    val dynamic = remember { mutableStateListOf<Int>().apply { addAll(generalizer.suggestDynamic(steps)) } }

    AlertDialog(
        onDismissRequest = { if (!saving) RecorderState.consume() },
        title = { Text("동적 값이 있나요?") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("매크로 이름") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                Text("매일 바뀌는 값(예: 오늘 날짜)이 있는 스텝을 체크하세요.", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    itemsIndexed(steps) { i, s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = i in dynamic,
                                onCheckedChange = { if (it) dynamic.add(i) else dynamic.remove(i) },
                            )
                            Text("${i + 1}. ${s.text ?: s.viewId ?: s.bounds}")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && name.isNotBlank(), onClick = {
                saving = true
                scope.launch {
                    val general = generalizer.generalize(steps, dynamic.toSet())
                    AppDatabase.get(ctx).macroDao().insert(
                        Macro(
                            name = name.trim(), steps = general,
                            targetPackage = RecorderState.targetPackage.value,
                        )
                    )
                    RecorderState.consume()
                }
            }) { Text(if (saving) "저장 중…" else "저장") }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = { RecorderState.consume() }) { Text("취소") }
        },
    )
}
