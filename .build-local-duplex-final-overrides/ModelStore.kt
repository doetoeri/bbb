package dev.localduplex.agent.model

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.math.max


enum class ModelKind { ASR, LLM, TTS }

enum class ImportStage {
    COPYING,
    EXTRACTING,
    VERIFYING,
    COMPLETE,
    FAILED
}

data class ImportProgress(
    val kind: ModelKind,
    val stage: ImportStage,
    val fraction: Float = 0f,
    val processedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val bytesPerSecond: Long = 0L,
    val etaSeconds: Long = -1L,
    val detail: String = ""
)

data class ModelStatus(
    val asrReady: Boolean = false,
    val llmReady: Boolean = false,
    val ttsReady: Boolean = false,
    val importing: Boolean = false,
    val message: String = "모델팩을 준비해 주세요",
    val progress: ImportProgress? = null
) {
    val allReady: Boolean get() = asrReady && llmReady && ttsReady
}

class ModelStore(private val context: Context) {
    private val root = File(context.filesDir, "models").apply { mkdirs() }
    val asrDir = File(root, "asr").apply { mkdirs() }
    val ttsDir = File(root, "tts").apply { mkdirs() }
    val llmDir = File(root, "llm").apply { mkdirs() }
    val llmFile: File get() = File(llmDir, "model.gguf")

    private val _status = MutableStateFlow(checkStatus())
    val status: StateFlow<ModelStatus> = _status.asStateFlow()

    suspend fun import(kind: ModelKind, uri: Uri) = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val total = querySize(uri)
        publishProgress(kind, ImportStage.COPYING, 0L, total, 0L, -1L, name)
        runCatching {
            when (kind) {
                ModelKind.LLM -> importGguf(uri, total)
                ModelKind.ASR, ModelKind.TTS -> importArchiveOrFile(kind, uri, total)
            }
            publishProgress(kind, ImportStage.VERIFYING, max(total, 0L), total, 0L, -1L, "필수 파일 검사")
            val missing = if (kind == ModelKind.LLM) emptyList() else missing(kind)
            require(missing.isEmpty()) { "필수 파일 누락: ${missing.joinToString()}" }
            val checked = checkStatus()
            _status.value = checked.copy(
                importing = false,
                message = if (checked.allReady) "모든 모델 준비 완료" else "${kind.name} 모델 가져오기 완료",
                progress = ImportProgress(
                    kind = kind,
                    stage = ImportStage.COMPLETE,
                    fraction = 1f,
                    processedBytes = max(total, 0L),
                    totalBytes = total,
                    detail = name
                )
            )
        }.onFailure { e ->
            val checked = checkStatus()
            _status.value = checked.copy(
                importing = false,
                message = "가져오기 실패: ${e.message ?: e.javaClass.simpleName}",
                progress = ImportProgress(kind, ImportStage.FAILED, detail = e.message ?: e.javaClass.simpleName)
            )
        }
    }

    fun refresh() {
        val previous = _status.value.progress?.takeIf { it.stage == ImportStage.COMPLETE }
        _status.value = checkStatus().copy(progress = previous)
    }

    private fun displayName(uri: Uri): String {
        var name = "model"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0) ?: name
        }
        return name
    }

    private fun querySize(uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L }
            .getOrDefault(-1L)
    }

    private fun importGguf(uri: Uri, total: Long) {
        val name = displayName(uri)
        require(name.lowercase().endsWith(".gguf")) { "LLM은 GGUF 파일이어야 합니다" }
        val tmp = File(llmDir, "model.gguf.tmp")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "파일을 열 수 없습니다" }
            copyWithProgress(
                kind = ModelKind.LLM,
                stage = ImportStage.COPYING,
                input = input,
                outputFile = tmp,
                total = total,
                detail = name
            )
        }
        require(tmp.length() > 8L * 1024 * 1024) { "GGUF 파일이 너무 작습니다" }
        if (llmFile.exists()) llmFile.delete()
        require(tmp.renameTo(llmFile)) { "GGUF 저장에 실패했습니다" }
    }

    private fun importArchiveOrFile(kind: ModelKind, uri: Uri, total: Long) {
        val originalName = displayName(uri)
        val name = originalName.lowercase()
        when {
            name.endsWith(".tar.bz2") || name.endsWith(".tbz2") -> extractTarBz2(kind, uri, total, originalName)
            name.endsWith(".zip") -> extractZip(kind, uri, total, originalName)
            else -> copySingleModelFile(kind, uri, originalName, total)
        }
    }

    private fun targetDir(kind: ModelKind) = when (kind) {
        ModelKind.ASR -> asrDir
        ModelKind.TTS -> ttsDir
        ModelKind.LLM -> llmDir
    }

    private fun allowed(kind: ModelKind): Set<String> = when (kind) {
        ModelKind.ASR -> ASR_REQUIRED
        ModelKind.TTS -> TTS_REQUIRED
        ModelKind.LLM -> emptySet()
    }

    private fun extractTarBz2(kind: ModelKind, uri: Uri, total: Long, name: String) {
        context.contentResolver.openInputStream(uri).use { raw ->
            requireNotNull(raw) { "archive open failed" }
            val progress = ProgressInputStream(raw, total) { processed, speed, eta ->
                publishProgress(kind, ImportStage.EXTRACTING, processed, total, speed, eta, name)
            }
            TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(progress), true)).use { tar ->
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    if (!entry.isFile) continue
                    val base = File(entry.name).name
                    if (base !in allowed(kind)) continue
                    writeEntry(targetDir(kind), base, tar)
                }
            }
            progress.finish()
        }
    }

    private fun extractZip(kind: ModelKind, uri: Uri, total: Long, name: String) {
        context.contentResolver.openInputStream(uri).use { raw ->
            requireNotNull(raw) { "archive open failed" }
            val progress = ProgressInputStream(raw, total) { processed, speed, eta ->
                publishProgress(kind, ImportStage.EXTRACTING, processed, total, speed, eta, name)
            }
            ZipInputStream(BufferedInputStream(progress)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val base = File(entry.name).name
                    if (base !in allowed(kind)) continue
                    writeEntry(targetDir(kind), base, zip)
                }
            }
            progress.finish()
        }
    }

    private fun copySingleModelFile(kind: ModelKind, uri: Uri, name: String, total: Long) {
        val base = File(name).name
        require(base in allowed(kind)) { "이 파일명은 ${kind.name} 모델 파일로 인식되지 않습니다: $base" }
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input)
            val dst = File(targetDir(kind), "$base.tmp")
            copyWithProgress(kind, ImportStage.COPYING, input, dst, total, base)
            val final = File(targetDir(kind), base)
            if (final.exists()) final.delete()
            require(dst.renameTo(final)) { "저장 실패: $base" }
        }
    }

    private fun copyWithProgress(
        kind: ModelKind,
        stage: ImportStage,
        input: InputStream,
        outputFile: File,
        total: Long,
        detail: String
    ) {
        val started = SystemClock.elapsedRealtime()
        var lastUpdate = started
        var processed = 0L
        val buffer = ByteArray(1024 * 1024)
        FileOutputStream(outputFile).use { output ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                output.write(buffer, 0, n)
                processed += n
                val now = SystemClock.elapsedRealtime()
                if (now - lastUpdate >= 120 || (total > 0 && processed >= total)) {
                    val elapsedMs = max(1L, now - started)
                    val speed = processed * 1000L / elapsedMs
                    val eta = if (total > 0 && speed > 0) ((total - processed).coerceAtLeast(0L) / speed) else -1L
                    publishProgress(kind, stage, processed, total, speed, eta, detail)
                    lastUpdate = now
                }
            }
        }
        val elapsedMs = max(1L, SystemClock.elapsedRealtime() - started)
        val speed = processed * 1000L / elapsedMs
        publishProgress(kind, stage, processed, total, speed, 0L, detail)
    }

    private fun writeEntry(dir: File, base: String, input: InputStream) {
        require(base.isNotBlank() && '/' !in base && '\\' !in base)
        val tmp = File(dir, "$base.tmp")
        FileOutputStream(tmp).use { output -> input.copyTo(output, 1024 * 1024) }
        val dst = File(dir, base)
        if (dst.exists()) dst.delete()
        require(tmp.renameTo(dst)) { "저장 실패: $base" }
    }

    private fun publishProgress(
        kind: ModelKind,
        stage: ImportStage,
        processed: Long,
        total: Long,
        speed: Long,
        eta: Long,
        detail: String
    ) {
        val fraction = if (total > 0) (processed.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f) else 0f
        val checked = checkStatus()
        _status.value = checked.copy(
            importing = stage != ImportStage.COMPLETE && stage != ImportStage.FAILED,
            message = when (stage) {
                ImportStage.COPYING -> "${kind.name} 모델 복사 중…"
                ImportStage.EXTRACTING -> "${kind.name} 압축 해제 중…"
                ImportStage.VERIFYING -> "${kind.name} 필수 파일 검증 중…"
                ImportStage.COMPLETE -> "${kind.name} 모델 가져오기 완료"
                ImportStage.FAILED -> "${kind.name} 가져오기 실패"
            },
            progress = ImportProgress(kind, stage, fraction, processed, total, speed, eta, detail)
        )
    }

    private fun missing(kind: ModelKind): List<String> = allowed(kind).filterNot { File(targetDir(kind), it).isFile }

    private fun checkStatus(): ModelStatus {
        val asr = ASR_REQUIRED.all { File(asrDir, it).isFile }
        val tts = TTS_REQUIRED.all { File(ttsDir, it).isFile }
        val llm = llmFile.isFile && llmFile.length() > 8L * 1024 * 1024
        return ModelStatus(asr, llm, tts, false, when {
            asr && llm && tts -> "모든 모델 준비 완료"
            else -> "ASR ${if (asr) "✓" else "○"} · LLM ${if (llm) "✓" else "○"} · TTS ${if (tts) "✓" else "○"}"
        })
    }

    private class ProgressInputStream(
        input: InputStream,
        private val total: Long,
        private val onProgress: (processed: Long, speed: Long, eta: Long) -> Unit
    ) : FilterInputStream(input) {
        private val started = SystemClock.elapsedRealtime()
        private var lastUpdate = started
        private var processed = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) advance(1)
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) advance(n)
            return n
        }

        private fun advance(n: Int) {
            processed += n
            val now = SystemClock.elapsedRealtime()
            if (now - lastUpdate >= 120 || (total > 0 && processed >= total)) emit(now)
        }

        fun finish() = emit(SystemClock.elapsedRealtime())

        private fun emit(now: Long) {
            val elapsedMs = max(1L, now - started)
            val speed = processed * 1000L / elapsedMs
            val eta = if (total > 0 && speed > 0) ((total - processed).coerceAtLeast(0L) / speed) else -1L
            onProgress(processed, speed, eta)
            lastUpdate = now
        }
    }

    companion object {
        val ASR_REQUIRED = setOf(
            "encoder-epoch-99-avg-1.int8.onnx",
            "decoder-epoch-99-avg-1.onnx",
            "joiner-epoch-99-avg-1.int8.onnx",
            "tokens.txt"
        )
        val TTS_REQUIRED = setOf(
            "duration_predictor.int8.onnx",
            "text_encoder.int8.onnx",
            "vector_estimator.int8.onnx",
            "vocoder.int8.onnx",
            "tts.json",
            "unicode_indexer.bin",
            "voice.bin"
        )
    }
}
