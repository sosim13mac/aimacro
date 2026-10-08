package com.onion.macrolearn.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.onion.macrolearn.service.RecorderState
import com.onion.macrolearn.util.Permissions
import kotlinx.coroutines.launch

/** 기록 시작 → (권한 요청) → 플로팅 STOP → 동적 값 확인 다이얼로그 → Room 저장 */
@Composable
fun RecordScreen() {
    val ctx = LocalContext.current
    val recording by RecorderState.recording.collectAsState()
    val reviewPending by RecorderState.reviewPending.collectAsState()
    val steps by RecorderState.steps.collectAsState()

    // 설정 화면에서 돌아올 때 권한 상태를 다시 읽는다
    var accessibilityOk by remember { mutableStateOf(Permissions.isAccessibilityEnabled(ctx)) }
    var overlayOk by remember { mutableStateOf(Permissions.canDrawOverlays(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        accessibilityOk = Permissions.isAccessibilityEnabled(ctx)
        overlayOk = Permissions.canDrawOverlays(ctx)
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("패턴 기록", style = MaterialTheme.typography.titleLarge)
        Text("기록을 시작한 뒤 대상 앱에서 평소처럼 한 번 눌러 보세요. 완료되면 플로팅 STOP 버튼을 누릅니다.")

        PermissionRow("접근성 서비스", accessibilityOk) { Permissions.openAccessibilitySettings(ctx) }
        PermissionRow("다른 앱 위에 표시", overlayOk) { Permissions.openOverlaySettings(ctx) }

        Button(
            enabled = accessibilityOk && overlayOk && !recording,
            onClick = { MacroRecorderService.instance?.startRecording() },
        ) { Text(if (recording) "기록 중… (STOP 버튼으로 종료)" else "기록 시작") }

        if (recording) Text("캡처된 스텝: ${steps.size}개")
    }

    if (reviewPending) ReviewDialog(ctx, steps)
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
