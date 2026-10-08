package com.onion.macrolearn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.onion.macrolearn.ai.LayaClient
import com.onion.macrolearn.ai.LayaConfig
import com.onion.macrolearn.data.AppDatabase
import com.onion.macrolearn.data.Macro
import com.onion.macrolearn.data.SampleMacro
import com.onion.macrolearn.service.MacroRunnerService
import com.onion.macrolearn.util.RunLog
import com.onion.macrolearn.worker.MacroScheduler
import kotlinx.coroutines.launch

/** 저장된 매크로 목록: 즉시 실행, 매일 09:00 자동 실행 예약, 삭제 */
@Composable
fun MacroListScreen() {
    val ctx = LocalContext.current
    val dao = remember { AppDatabase.get(ctx).macroDao() }
    val macros by dao.observeAll().collectAsState(initial = emptyList())
    val connected by com.onion.macrolearn.service.RecorderState.connected.collectAsState()
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("저장된 매크로", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { scope.launch { dao.insert(SampleMacro.create()) } }) { Text("샘플 추가") }
        }
        if (!connected) Text("접근성 서비스가 꺼져 있으면 실행되지 않습니다.", color = MaterialTheme.colorScheme.error)
        LayaPanel()

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(macros, key = { it.id }) { macro ->
                MacroCard(
                    macro = macro,
                    onRun = { MacroRunnerService.start(ctx, macro.id) },
                    onSchedule = { on ->
                        scope.launch {
                            dao.setScheduled(macro.id, on)
                            if (on) MacroScheduler.schedule(ctx, macro.id) else MacroScheduler.cancel(ctx, macro.id)
                        }
                    },
                    onDelete = {
                        scope.launch {
                            MacroScheduler.cancel(ctx, macro.id)
                            dao.delete(macro)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun MacroCard(macro: Macro, onRun: () -> Unit, onSchedule: (Boolean) -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(macro.name, style = MaterialTheme.typography.titleMedium)
            Text("${macro.steps.size}개 스텝", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = macro.scheduled, onCheckedChange = onSchedule)
                Spacer(Modifier.width(8.dp))
                Text("매일 09:00 자동 실행", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRun) { Text("지금 실행") }
                OutlinedButton(onClick = onDelete) { Text("삭제") }
            }
        }
    }
}

/** Laya 서버 URL 설정, 연결 테스트, 최근 실행 로그 */
@Composable
private fun LayaPanel() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(LayaConfig.baseUrl()) }
    var status by remember { mutableStateOf(if (LayaConfig.isPlaceholder()) "Laya URL 이 아직 예시 값입니다. 서버 URL을 입력하세요." else "") }
    var showLog by remember { mutableStateOf(false) }
    val log by RunLog.lines.collectAsState()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Laya 서버", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(url, { url = it }, label = { Text("Space URL (https://….hf.space)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (!LayaConfig.save(ctx, url)) { status = "올바른 URL 이 아닙니다"; return@Button }
                    status = "테스트 중…"
                    scope.launch {
                        // 저장된 URL 로 새 클라이언트를 만들어 실제 호출을 확인한다
                        val err = LayaClient().ping()
                        status = if (err == null) "연결 성공 ✔ Laya 가 응답했습니다" else "연결 실패 ✘ $err"
                    }
                }) { Text("저장 후 연결 테스트") }
                TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "로그 닫기" else "실행 로그") }
            }
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
            if (showLog) {
                Text(
                    if (log.isEmpty()) "(아직 로그가 없습니다)" else log.takeLast(40).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { RunLog.clear() }) { Text("로그 지우기") }
            }
        }
    }
}
