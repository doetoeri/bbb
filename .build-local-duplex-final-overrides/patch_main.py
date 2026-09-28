from pathlib import Path
import sys

target = Path(sys.argv[1])
s = target.read_text(encoding="utf-8")

if "import androidx.compose.material3.LinearProgressIndicator" not in s:
    s = s.replace(
        "import androidx.compose.material3.ExperimentalMaterial3Api\n",
        "import androidx.compose.material3.ExperimentalMaterial3Api\n"
        "import androidx.compose.material3.LinearProgressIndicator\n",
        1,
    )

if "import dev.localduplex.agent.model.ImportStage" not in s:
    s = s.replace(
        "import dev.localduplex.agent.conversation.TurnMode\n",
        "import dev.localduplex.agent.conversation.TurnMode\n"
        "import dev.localduplex.agent.model.ImportStage\n",
        1,
    )

anchor = '            Text(state.modelStatus.message, color = MaterialTheme.colorScheme.onSurfaceVariant)\n'
progress_block = '''            Text(state.modelStatus.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.modelStatus.progress?.let { p ->
                if (state.modelStatus.importing || p.stage == ImportStage.COMPLETE || p.stage == ImportStage.FAILED) {
                    if (p.totalBytes > 0 && p.stage != ImportStage.VERIFYING && p.stage != ImportStage.FAILED) {
                        LinearProgressIndicator(
                            progress = { p.fraction.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else if (state.modelStatus.importing && p.stage == ImportStage.VERIFYING) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        importProgressText(p),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    if (p.detail.isNotBlank()) {
                        Text(
                            p.detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
'''
if "state.modelStatus.progress?.let" not in s:
    if anchor not in s:
        raise SystemExit("ModelCard anchor not found")
    s = s.replace(anchor, progress_block, 1)

helpers = '''private fun importProgressText(p: dev.localduplex.agent.model.ImportProgress): String {
    val stage = when (p.stage) {
        ImportStage.COPYING -> "복사"
        ImportStage.EXTRACTING -> "압축 해제"
        ImportStage.VERIFYING -> "검증"
        ImportStage.COMPLETE -> "완료"
        ImportStage.FAILED -> "실패"
    }
    val percent = if (p.totalBytes > 0) ((p.fraction * 100).roundToInt()).toString() + "%" else "진행 중"
    val bytes = if (p.totalBytes > 0) {
        formatBytes(p.processedBytes) + " / " + formatBytes(p.totalBytes)
    } else {
        formatBytes(p.processedBytes)
    }
    val speed = if (p.bytesPerSecond > 0) " · " + formatBytes(p.bytesPerSecond) + "/s" else ""
    val eta = if (p.etaSeconds >= 0 && p.stage != ImportStage.COMPLETE) {
        " · 약 " + formatEta(p.etaSeconds) + " 남음"
    } else {
        ""
    }
    return stage + " " + percent + " · " + bytes + speed + eta
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "-"
    val mib = bytes / (1024.0 * 1024.0)
    return if (mib >= 1024.0) "%.2f GB".format(mib / 1024.0) else "%.1f MB".format(mib)
}

private fun formatEta(seconds: Long): String {
    if (seconds < 60) return seconds.toString() + "초"
    val minutes = seconds / 60
    val remain = seconds % 60
    return if (remain == 0L) minutes.toString() + "분" else minutes.toString() + "분 " + remain.toString() + "초"
}

'''
if "private fun importProgressText" not in s:
    marker = "private fun fmt(ms: Long): String"
    idx = s.find(marker)
    if idx < 0:
        raise SystemExit("fmt marker not found")
    s = s[:idx] + helpers + s[idx:]

target.write_text(s, encoding="utf-8")
