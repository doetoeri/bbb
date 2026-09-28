package dev.localduplex.agent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.localduplex.agent.conversation.AgentPhase
import dev.localduplex.agent.conversation.ConversationMessage
import dev.localduplex.agent.conversation.TurnMode
import dev.localduplex.agent.model.ImportStage
import dev.localduplex.agent.model.ModelKind
import dev.localduplex.agent.runtime.AgentUiState
import dev.localduplex.agent.runtime.VoiceAgentRuntime
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { AgentApp() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val runtime = remember { VoiceAgentRuntime(context.applicationContext) }
    val state by runtime.state.collectAsState()
    val scope = rememberCoroutineScope()
    var pendingStart by remember { mutableStateOf(false) }
    var importKind by remember { mutableStateOf(ModelKind.ASR) }
    var endpoint by remember { mutableFloatStateOf(620f) }
    var barge by remember { mutableFloatStateOf(120f) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok && pendingStart) scope.launch { runtime.start() }
        pendingStart = false
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        scope.launch { runtime.models.import(importKind, uri) }
    }

    DisposableEffect(Unit) { onDispose { runtime.close() } }

    Scaffold { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Text("Local Duplex", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("ì™„ì „ ë¡œì»¬ Â· Full-Duplex í•œêµ­ì–´ ìŒì„± AI", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            item { ModelCard(state) { kind -> importKind = kind; picker.launch(arrayOf("*/*")) } }
            item { ModeCard(state.mode, state.running, endpoint, barge,
                onMode = runtime::setMode,
                onEndpoint = { endpoint = it; runtime.setCustomTiming(it.toLong(), barge.toLong()) },
                onBarge = { barge = it; runtime.setCustomTiming(endpoint.toLong(), it.toLong()) }
            ) }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            if (state.running) scope.launch { runtime.stop() }
                            else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                scope.launch { runtime.start() }
                            } else {
                                pendingStart = true
                                permission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        enabled = state.modelStatus.allReady && !state.modelStatus.importing,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (state.running) "ëŒ€í™” ì¢…ë£Œ" else "ëŒ€í™” ì‹œì‘") }
                    OutlinedButton(
                        onClick = { runtime.requestLatencyProbe() },
                        enabled = state.running,
                        modifier = Modifier.weight(1f)
                    ) { Text("ì§€ì—° ì¸¡ì •") }
                }
            }

            item { LiveCard(state) }

            if (state.messages.isNotEmpty()) {
                item { Text("ëŒ€í™”", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
                items(state.messages, key = { it.id }) { MessageBubble(it) }
            }

            item { DiagnosticsCard(state) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = { scope.launch { runtime.clearConversation() } }, enabled = !state.running) {
                        Text("ëŒ€í™” ê¸°ë¡ ì´ˆê¸°í™”")
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ModelCard(state: AgentUiState, onImport: (ModelKind) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("ëª¨ë¸íŒ©", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(state.modelStatus.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Text(importProgressText(p), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    if (p.detail.isNotBlank()) {
                        Text(p.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModelButton("ASR ${if (state.modelStatus.asrReady) "âœ“" else "â†“"}", state.modelStatus.importing) { onImport(ModelKind.ASR) }
                ModelButton("LLM ${if (state.modelStatus.llmReady) "âœ“" else "â†“"}", state.modelStatus.importing) { onImport(ModelKind.LLM) }
                ModelButton("TTS ${if (state.modelStatus.ttsReady) "âœ“" else "â†“"}", state.modelStatus.importing) { onImport(ModelKind.TTS) }
            }
            Text("ASR/TTSë‹” ê³µì‹  .tar.bz2 ë˜ëŠ” .zip, LLMì€ GGUFë¥¼ ì„ íƒí•©ë‹ˆë‹¤. ì•±ì—ëŠ” INTERNETê¶Œí•œì´ ì—†ìŠµë‹ˆë‹¤.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ModelButton(text: String, busy: Boolean, action: () -> Unit) {
    OutlinedButton(onClick = action, enabled = !busy) { Text(text) }
}

@Composable
private fun ModeCard(
    mode: TurnMode,
    running: Boolean,
    endpoint: Float,
    barge: Float,
    onMode: (TurnMode) -> Unit,
    onEndpoint: (Float) -> Unit,
    onBarge: (Float) -> Unit
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("ëŒ€í™” ì„±í–¥", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TurnMode.entries.forEach { m ->
                    val selected = m == mode
                    Button(
                        onClick = { onMode(m) },
                        enabled = !running,
                        colors = if (selected) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 11.dp, vertical = 7.dp)
                    ) { Text(m.title, style = MaterialTheme.typography.labelMedium) yô(€€€€€€€€€€€€€€€ô(€€€€€€€€€€€ô(€€€€€€€€€€€¥˜€¡µ½‘”€ôôQÕÉ¹5½‘”¹UMQ=4¤ì(€€€€€€€€€€€€€€€Q•áĞ ‹®Âs¶fPƒ²Š#®0€‘í•¹‘Á½¥¹Ğ¹É½Õ¹‘Q½%¹Ğ ¥ôµÌˆ°ÍÑå±”€ô5…Ñ•É¥…±Q¡•µ”¹ÑåÁ½É…Á¡ä¹‰½‘åMµ…±°¤(€€€€€€€€€€€€€€€M±¥‘•È¡Ù…±Õ”€ô•¹‘Á½¥¹Ğ°½¹Y…±Õ•¡…¹”€ô½¹¹‘Á½¥¹Ğ°Ù…±Õ•I…¹”€ô€ÌÔÁ˜¸¸ÄÈÀÁ˜°•¹…‰±•€ô€…ÉÕ¹¹¥¹œ¤(€€€€€€€€€€€€€€€Q•áĞ ‰	…É”µ¥¸€‘í‰…É”¹É½Õ¹‘Q½%¹Ğ ¥ôµÌˆ°ÍÑå±”€ô5…Ñ•É¥…±Q¡•µ”¹ÑåÁ½É…Á¡ä¹‰½‘åMµ…±°¤(€€€€€€€€€€€€€€€M±¥‘•È¡Ù…±Õ”€ô‰…É”°½¹Y…±Õ•¡…¹”€ô½¹	…É”°Ù…±Õ•I…¹”€ô€àÁ˜¸¸ÌÀÁ˜°•¹…‰±•€ô€…ÉÕ¹¹¥¹œ¤(€€€€€€€€€€€ô(€€€€€€€ô(€€€ô)ô()½µÁ½Í…‰±”)ÁÉ¥Ù…Ñ”™Õ¸1¥Ù•…É¡ÍÑ…Ñ”è•¹ÑU¥MÑ…Ñ”¤ì(€€€Ù…°Á¡…Í•½±½È€ôİ¡•¸€¡ÍÑ…Ñ”¹Á¡…Í”¤ì(€€€€€€€•¹ÑA¡…Í”¹UMI}MA-%9€´ø5…Ñ•É¥…±Q¡•µ”¹½±½ÉM¡•µ”¹ÁÉ¥µ…Éå½¹Ñ…¥¹•È(€€€€€€€•¹ÑA¡…Í”¹Q!%9-%9°•¹ÑA¡…Í”¹AI%Q%9€´ø5…Ñ•É¥…±Q¡•µ”¹½±½ÉM¡•µ”¹Ñ•ÉÑ¥…Éå½¹Ñ…¥¹•È(€€€€€€€•¹ÑA¡…Í”¹MA-%9°•¹ÑA¡…Í”¹%9QIIUAQ%9€´ø5…Ñ•É¥…±Q¡•µ”¹½±½ÉM¡•µ”¹Í•½¹‘…Éå½¹Ñ…¥¹•È(€€€€€€€•¹ÑA¡…Í”¹II=H€´ø5…Ñ•É¥…±Q¡•µ”¹½±½ÉM¡•µ”¹•ÉÉ½É½¹Ñ…¥¹•È(€€€€€€€•±Í”€´ø5…Ñ•É¥…±Q¡•µ”¹½±½ÉM¡•µ”¹ÍÕÉ™…•½¹Ñ…¥¹•É!¥ (€€€ô(€€€…É¡½±½ÉÌ€ô…É‘•™…Õ±ÑÌ¹…É‘½±½ÉÌ¡½¹Ñ…¥¹•É½±½È€ôÁ¡…Í•½±½È¤¤ì(€€€€€€€½±Õµ¸¡5½‘¥™¥•È¹Á…‘‘¥¹œ ÄØ¹‘À¤°Ù•ÉÑ¥…±ÉÉ…¹•µ•¹Ğ€ôÉÉ…¹•µ•¹Ğ¹ÍÁ…•‘	ä à¹‘À¤¤ì(€€€€€€€€€€€I½Ü¡Ù•ÉÑ¥…±±¥¹µ•¹Ğ€ô±¥¹µ•¹Ğ¹•¹Ñ•ÉY•ÉÑ¥…±±ä¤ì(€€€€€€€€€€€€€€€Q•áĞ¡Á¡…Í•1…‰•°¡ÍÑ…Ñ”¹Á¡…Í”¤°ÍÑå±”€ô5…Ñ•É¥…±Q¡•µ”¹ÑåÁ½É…Á¡ä¹Ñ¥Ñ±•5•‘¥Õ´°™½¹Ñ]•¥¡Ğ€ô½¹Ñ]•¥¡Ğ¹	½±¤(€€€€€€€€€€€€€€€¥˜€¡ÍÑ…Ñ”¹ÁÉ•‘¥Ñ¥Ù•Ñ¥Ù”¤ì(€€€€€€€€€€€€€€€€€€€MÁ…•È¡5½‘¥™¥•È¹İ¥‘Ñ  à¹‘À¤¤ìQ•áĞ ‹²b#²â„ƒ²Êc®š°ƒ²’Dˆ°ÍÑå±”€ô5…Ñ•É¥…±Q¡•µ”¹ÑåÁ½É…Á¡ä¹±…‰•±Mµ…±°¤(€€€€€€€€€€€€€€€ô(€€€€€€€€€€€ô(€€€€€€€€€€€¥˜€¡ÍÑ…Ñ”¹±½…‘¥¹Q•áĞ¹¥Í9½Ñ	±…¹¬ ¤¤Q•áĞ¡ÍÑ…Ñ”¹±½…‘¥¹Q•áĞ¤(€€€€€€€€€€€¥˜€¡ÍÑ…Ñ”¹Á…ÉÑ¥…°¹¥Í9½Ñ	±…¹¬ ¤¤ì(€€€€€€€€€€€€€€€Q•áĞ ‹®
`ˆ°ÍÑå±”€ô5…Ñ•É¥…±Q¡•µ”¹ÑåÁ½É…Á¡ä¹±…‰•±5•‘¥Õ´¤(€€€€€€€€€€€€€€€Q•áĞ¡ÍÑ…Ñ”¹Á…ÉÑ¥…°°ÍÑå±”€ô5…Ñ•É¥…±Q¡•µ”¹ÑåÁ½É…Á¡ä¹Ñ¥Ñ±•1…É”¤(€€€€€€€€€€€ô(€€€€€€€€€€€¥˜€¡ÍÑ…Ñ”¹…ÍÍ¥ÍÑ…¹ÑÉ…™Ğ¹¥Í9½Ñ	±…¹¬ ¤¤ì(€€€€€€€€€€€€€€€Q•áĞ ‰$ˆ°ÍÑå±”€ô5…Ñ•É¥…±Q¡•µ”¹ÑåÁ½É…Á¡ä¹±…‰•±5•‘¥Õ´¤(€€€€€€€€€€€€€€€Q•áĞ¡ÍÑ…Ñ”¹…ÍÍ¥ÍÑ…¹ÑÉ…™Ğ°ÍÑå±”€ô5…Ñ•É¥…±Q¡•µ”¹ÑåÁ½É…Á¡ä¹‰½‘å1…É”¤(€€€€€€€€€€€ô(€€€€€€€€€€€ÍÑ…Ñ”¹±…ÍÑÉÉ½Èü¹±•ĞìQ•áĞ¡¥Ğ°½±½È€ô5…Ñ•É¥…±Q¡•µ”¹½±½ÉM¡•µ”¹•ÉÉ½È¤ô(€€€€€€€€€€€Ù…°À€ôÍÑ…Ñ”¹ÁÉ½‰…‰¥±¥Ñ¥•Ì(€€€€€€€€€€€Q•áĞ (€€€€€€€€€€€€€€€€‹®C¶V €”¸É˜ƒ
Üƒ²Š#®0€”¸É˜ƒ
Üƒ®*sªâÀ€”¸É˜ƒ
Üƒ®/²z”ª¶ €”¸É˜ƒ
Ü'ªÂs²z€”¸É˜ˆ¹™½Éµ…Ğ (€€€€€€€€€€€€€€€€€€€À¹ÕÍ•ÉMÁ•…­¥¹œ°À¹ÕÍ•É½¹”°À¹‰…É•%¸°À¹‰…­¡…¹¹•°°À¹…ÍÍ¥ÍÑ…¹Ñ%¹Ñ•ÉÉÕÁĞ(€€€€€€€€€€€€€€€€¤°(€€€€€€€€€€€€€€€ÍÑå±”€ô5…Ñ•É¥…±Q¡•µ”¹ÑåÁ½É…Á¡ä¹±…‰•±Mµ…±°°(€€€€€€€€€€€€€€€™½¹Ñ…µ¥±ä€ô½¹Ñ…µ¥±ä¹5½¹½ÍÁ…”(€€€€€€€€€€€€¤(€€€€€€€ô(€€€ô)ô()½µÁ½Í…‰±”)ÁÉ¥Ù…Ñ”™Õ¸5•ÍÍ…•	Õ‰‰±”¡µ•ÍÍ…”è½¹Ù•ÉÍ…Ñ¥½¹5•ÍÍ…”¤ì(€€€Ù…°ÕÍ•È€ôµ•ÍÍ…”¹ÍÁ•…­•È€ôô½¹Ù•ÉÍ…Ñ¥½¹5•ÍÍ…”¹MÁ•…­•È¹UMH(€€€I½Ü¡5½‘¥™¥•È¹™¥±±5…á]¥‘Ñ  ¤°¡½É¥é½¹Ñ…±ÉÉ…¹•µ•¹Ğ€ô¥˜€¡ÕÍ•È¤ÉÉ…¹•µ•¹Ğ¹¹•±Í”ÉÉ…¹•µ•¹Ğ¹MÑ…ÉĞ¤ì(€€€€€€€	½à (€€€€€€€€€€€5½‘¥™¥•È(€€€€€€€€€€€€€€€€¹™¥±±5…á]¥‘Ñ  À¸àá˜¤(€€€€€€€€€€€€€€€€¹‰…­É½Õ¹ (€€€€€€€€€€€€€€€€€€€¥˜€¡ÕÍ•È¤5…Ñ•É¥…±Q¡•µ”¹½±½ÉM¡•µ”¹ÁÉ¥µ…Éå½¹Ñ…¥¹•È•±Í”5…Ñ•É¥…±Q¡•µ”¹½±½ÉM¡•µ”¹ÍÕÉ™…•½¹Ñ…¥¹•É!¥ °(€€€€€€€€€€€€€€€€€€€I½Õ¹‘•‘½É¹•ÉM¡…Á” Äà¹‘À¤(€€€€€€€€€€€€€€€€¤(€€€€€€€€€€€€€€€€¹Á…‘‘¥¹œ ÄÌ¹‘À¤(€€€€€€€€¤ì(€€€€€€€€€€€½±Õµ¸ì(€€€€€€€€€€€€€€€Q•áĞ¡¥˜€¡ÕÍ•È¤€‹®
`ˆ•±Í”€‰$ˆ°ÍÑå±”€ô5…Ñ•É¥…±Q¡•µ”¹ÑåÁ½É…Á¡ä¹±…‰•±Mµ…±°°™½¹Ñ]•¥¡Ğ€ô½¹Ñ]•¥¡Ğ¹	½±¤(€€€€€€€€€€€€€€€Q•áĞ¡µ•ÍÍ…”¹Ñ•áĞ¤(€€€€€€€€€€€€€€€¥˜€¡µ•ÍÍ…”¹¥¹Ñ•ÉÉÕÁÑ•¤Q•áĞ ‹²’G®.£®R ˆ°ÍÑå±”€ô5…Ñ•É¥…±Q¡•µ”¹ÑåÁ½É…Á¡ä¹±…‰•±Mµ…±°°½±½È€ô5…Ñ•É¥…±Q¡•µ”¹½±½ÉM¡•µ”¹½¹MÕÉ™…•Y…É¥…¹Ğ¤(€€€€€€€€€€€ô(€€€€€€€ô(€€€ô)ô()½µÁ½Í…‰±”)ÁÉ¥Ù…Ñ”™Õ¸¥…¹½ÍÑ¥Í…É¡ÍÑ…Ñ”è•¹ÑU¥MÑ…Ñ”¤ì(€€€Ù…°„€ôÍÑ…Ñ”¹…Õ‘¥¼(€€€Ù…°°€ôÍÑ…Ñ”¹±…Ñ•¹ä(€€€…É¡½±½ÉÌ€ô…É‘•™…Õ±ÑÌ¹…É‘½±½ÉÌ¡½¹Ñ…¥¹•É½±½È€ô5…Ñ•É¥…±Q¡•µ”¹½±½ÉM¡•µ”¹ÍÕÉ™…•½¹Ñ…¥¹•É1½Ü¤¤ì(€€€€€€€½±Õµ¸¡5½‘¥™¥•È¹Á…‘‘¥¹œ ÄØ¹‘À¤°Ù•ÉÑ¥…±ÉÉ…¹•µ•¹Ğ€ôÉÉ…¹•µ•¹Ğ¹ÍÁ…•‘	ä Ô¹‘À¤¤ì(€€€€€€€€€€€Q•áĞ ‹².“².sªÂƒ²®. ˆ°ÍÑå±”€ô5…Ñ•É¥…±Q¡•µ”¹ÑåÁ½É…Á¡ä¹Ñ¥Ñ±•5•‘¥Õ´°™½¹Ñ]•¥¡Ğ€ô½¹Ñ]•¥¡Ğ¹M•µ¥	½±¤(€€€€€€€€€€€Q•áĞ ˆ‘í„¹…Á¥ôƒ
Ü€‘í„¹Í…µÁ±•I…Ñ•ô!èƒ
Ü‰ÕÉÍĞ€‘í„¹™É…µ•ÍA•É	ÕÉÍÑôƒ
Ü©¥ÑÑ•È€‘ìˆ”¸Í˜ˆ¹™½Éµ…Ğ¡„¹…±±‰…­)¥ÑÑ•É5Ì¥ôµÌˆ°™½¹Ñ…µ¥±ä€ô½¹Ñ…µ¥±ä¹5½¹½ÍÁ…”°ÍÑå±”€ô5…Ñ•É¥…±Q¡•µ”¹ÑåÁ½É…Á¡ä¹‰½‘åMµ…±°¤(€€€€€€€€€€€Q•áĞ ‰€‘í¥˜€¡ÍÑ…Ñ”¹•™™•ÑÌ¹…•¹…‰±•¤€‰=8ˆ•±Í”€‰=‰ôƒ
Ü9L€‘í¥˜€¡ÍÑ…Ñ”¹•™™•ÑÌ¹¹Í¹…‰±•¤€‰=8ˆ•±Í”€‰=‰ôƒ
Üµ¥Œ€‘ìˆ”¸Å˜ˆ¹™½Éµ…Ğ¡„¹¥¹ÁÕÑIµÍˆ¥ô‘	Lˆ°™½¹Ñ…µ¥±ä€ô½¹Ñ…µ¥±ä¹5½¹½ÍÁ…”°ÍÑå±”€ô5…Ñ•É¥…±Q¡•µ”¹ÑåÁ½É…Á¡ä¹‰½‘åMµ…±°¤(€€€€€€€€€€€Q•áĞ ‰MH€‘í™µĞ¡°¹…ÍÉA…ÉÑ¥…±5Ì¥ôƒ
Ü114€‘í™µĞ¡°¹±±µ¥ÉÍÑQ½­•¹5Ì¥ôƒ
ÜQQL€‘í™µĞ¡°¹ÑÑÍ¥ÉÍÑÕ‘¥½5Ì¥ôƒ
ÜÉ€‘í™µĞ¡°¹•¹‘Q½¥ÉÍÑÕ‘¥½5Ì¥ôˆ°™½¹Ñ…µ¥±ä€ô½¹Ñ…µ¥±ä¹5½¹½ÍÁ…”°ÍÑå±”€ô5…Ñ•É¥…±Q¡•µ”¹ÑåÁ½É…Á¡ä¹‰½‘åMµ…±°¤(€€€€€€€€€€€Ù…°ÉĞ€ô¥˜€¡„¹É½Õ¹‘QÉ¥Á1…Ñ•¹å5Ì€øô€À¤€ˆ‘ìˆ”¸Å˜ˆ¹™½Éµ…Ğ¡„¹É½Õ¹‘QÉ¥Á1…Ñ•¹å5Ì¥ôµÌ€¼Èô‘ìˆ”¸É˜ˆ¹™½Éµ…Ğ¡„¹É½Õ¹‘QÉ¥Á½ÉÉ•±…Ñ¥½¸¥ôˆ•±Í”€ˆ‘í„¹±…Ñ•¹åMÑ…Ñ•ô€¼Èô‘ìˆ”¸É˜ˆ¹™½Éµ…Ğ¡„¹É½Õ¹‘QÉ¥Á½ÉÉ•±…Ñ¥½¸¥ôˆ(€€€€€€€€€€€Q•áĞ ‰…½ÕÍÑ¥Œ€‘ÉĞƒÜáÉÕ¹Ì€‘í„¹¥¹ÁÕÑaÉÕ¹Íô¼‘í„¹½ÕÑÁÕÑaÉÕ¹Íôˆ°™½¹Ñ…µ¥±ä€ô½¹Ñ…µ¥±ä¹5½¹½ÍÁ…”°ÍÑå±”€ô5…Ñ•É¥…±Q¡•µ”¹ÑåÁ½É…Á¡ä¹‰½‘åMµ…±°¤(€€€€€€€ô(€€€ô)ô()ÁÉ¥Ù…Ñ”™Õ¸Á¡…Í•1…‰•°¡Àè•¹ÑA¡…Í”¤€ôİ¡•¸€¡À¤ì(€€€•¹ÑA¡…Í”¹MQ=AA€´ø€‹²‚W² ˆ(€€€•¹ÑA¡…Í”¹1=%9€´ø€‹²’®æƒ²’Dˆ(€€€•¹ÑA¡…Í”¹1%MQ9%9€´ø€‹“N»Š”+²’Dˆ(€€€•¹ÑA¡…Í”¹UMI}MA-%9€´ø€‹®ª£²vƒ®N£ªÎ€ƒ²zc²ZÓ²fPˆ(€€€•¹ÑA¡…Í”¹AI%Q%9€´ø€‹‹¤ìŒ ë¡„ì„ ì˜ˆì¸¡ ì¤‘"
    AgentPhase.THINKING -> "ìƒì° ì¤‘"
    AgentPhase.SPEAKING -> "ë§í•˜ëŠ” ì¤‘"
    AgentPhase.YIELDING -> "ë°œì–¸ê¶Œ ë„ê¸°ëŠ” ì¤‘"
    AgentPhase.INTERRUPTING -> "ëŒ€í™”ì— ê°œì¥ ì¤‘"
    AgentPhase.ERROR -> "ì˜¤ë¥˜"
}

private fun importProgressText(p: dev.localduplex.agent.model.ImportProgress): String {
    val stage = when (p.stage) {
        ImportStage.COPYING -> "ë³µì‚¬"
        ImportStage.EXTRACTING -> "ì••ì¶• í•´ì œ"
        ImportStage.VERIFYING -> "ê²€ì¦"
        ImportStage.COMPLETE -> "ì™„ë£Œ"
        ImportStage.FAILED -> "ì‹¤íŒ¨"
    }
    val percent = if (p.totalBytes > 0) "${(p.fraction * 100).roundToInt()}%" else "ì§€í–‰ì¤‘"
    val bytes = if (p.totalBytes > 0) "${formatBytes(p.processedBytes)} / ${formatBytes(p.totalBytes)}" else formatBytes(p.processedBytes)
    val speed = if (p.bytesPerSecond > 0) " Â· ${formatBytes(p.bytesPerSecond)}/s" else ""
    val eta = if (p.etaSeconds >= 0 && p.stage != ImportStage.COMPLETE) " Â· ì•½ ${formatEta(p.etaSeconds)} ë‚¨ìŒ" else ""
    return "$stage $percent Â· $bytes$speed$eta"
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "-"
    val mib = bytes / (1024.0 * 1024.0)
    return if (mib >= 1024.0) "%.2f GB".format(mib / 1024.0) else "%.1f MB".format(mib)
}

private fun formatEta(seconds: Long): String {
    if (seconds < 60) return "${seconds}ì´ˆ"
    val minutes = seconds / 60
    val remain = seconds % 60
    return if (remain == 0L) "${minutes}ë¶„" else "${minutes}ë¶„ ${remain}ì´ˆ"
}

private fun fmt(ms: Long): String = if (ms < 0) "-" else "${ms}ms"
