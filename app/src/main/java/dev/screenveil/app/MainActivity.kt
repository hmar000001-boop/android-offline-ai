package dev.screenveil.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.screenveil.app.capture.ProtectionStatus
import dev.screenveil.app.capture.ScreenProtectionAccessibilityService
import dev.screenveil.app.model.GemmaModelCatalog
import dev.screenveil.app.model.ModelInstaller
import dev.screenveil.app.policy.RedactionEffect
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var accessibilityEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        removeAbandonedFrameFiles()
        accessibilityEnabled = ScreenProtectionAccessibilityService.isAccessibilityEnabled(this)

        setContent {
            val colors = lightColorScheme(
                primary = Color(0xFF315A92),
                secondary = Color(0xFF48627D),
                background = Color(0xFFF4F6FA),
                surface = Color.White
            )
            MaterialTheme(colorScheme = colors) {
                ScreenVeilHome(
                    accessibilityEnabled = accessibilityEnabled,
                    onStart = { beginProtection() },
                    onStop = { stopProtection() },
                    onOpenAccessibilitySettings = { openAccessibilitySettings() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accessibilityEnabled = ScreenProtectionAccessibilityService.isAccessibilityEnabled(this)
        if (accessibilityEnabled && AppPreferences(this).isProtectionEnabled()) {
            // The service also starts itself from onServiceConnected. This covers the case where
            // the user returns from Settings after enabling ScreenVeil.
            ScreenProtectionAccessibilityService.requestStart()
        }
    }

    private fun beginProtection() {
        if (!ModelInstaller.isVerifiedInstalled(this)) {
            Toast.makeText(this, "Download and verify Gemma 4 before starting protection.", Toast.LENGTH_LONG).show()
            return
        }

        AppPreferences(this).setProtectionEnabled(true)
        accessibilityEnabled = ScreenProtectionAccessibilityService.isAccessibilityEnabled(this)
        if (!accessibilityEnabled) {
            openAccessibilitySettings()
            Toast.makeText(
                this,
                "Enable ScreenVeil under Installed apps in Accessibility settings. Protection starts automatically after it is enabled.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (!ScreenProtectionAccessibilityService.requestStart()) {
            Toast.makeText(this, "Android is reconnecting the accessibility service. Return to ScreenVeil in a moment.", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopProtection() {
        AppPreferences(this).setProtectionEnabled(false)
        ScreenProtectionAccessibilityService.requestStop()
        ProtectionStatus.update(
            ProtectionStatus.State(
                active = false,
                modelReady = ModelInstaller.isVerifiedInstalled(this),
                message = "Protection paused · Accessibility remains enabled"
            )
        )
    }

    private fun openAccessibilitySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }.onFailure {
            Toast.makeText(this, "Could not open Android Accessibility settings: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun removeAbandonedFrameFiles() {
        cacheDir.listFiles()
            ?.filter { it.isFile && (it.name.startsWith("screen-frame-") || it.name.startsWith("screenveil-frame-")) }
            ?.forEach { runCatching { it.delete() } }
    }
}

@Composable
private fun ScreenVeilHome(
    accessibilityEnabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    val context = LocalContext.current
    val preferences = remember { AppPreferences(context) }
    val protection by ProtectionStatus.state.collectAsState()
    var modelInstalled by remember { mutableStateOf(ModelInstaller.isVerifiedInstalled(context)) }
    var downloadRunning by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadLabel by remember { mutableStateOf("") }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(preferences.getSettings()) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Default.VisibilityOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(12.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("ScreenVeil", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Offline live-screen protection", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = if (protection.active) Color(0xFFE7F4EB) else Color(0xFFE9EEF6))) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(
                        imageVector = if (protection.active) Icons.Default.Security else Icons.Default.Lock,
                        contentDescription = null,
                        tint = if (protection.active) Color(0xFF22643C) else MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (protection.active) "Protection active / starting" else "Protection is paused", fontWeight = FontWeight.SemiBold)
                }
                Text(protection.message, style = MaterialTheme.typography.bodyMedium)
                if (protection.lastInferenceMs != null) {
                    Text(
                        "Last analysis: ${protection.lastInferenceMs} ms · ${protection.regionsDetected} media region(s) detected · ${protection.regionsRedacted} redacted",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (protection.error != null) {
                    Text(protection.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onStart,
                        enabled = !protection.active && modelInstalled && !downloadRunning,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (accessibilityEnabled) "Start protection" else "Enable protection") }
                    OutlinedButton(
                        onClick = onStop,
                        enabled = protection.active || preferences.isProtectionEnabled(),
                        modifier = Modifier.weight(1f)
                    ) { Text("Stop") }
                }
                OutlinedButton(onClick = onOpenAccessibilitySettings, modifier = Modifier.fillMaxWidth()) {
                    Text(if (accessibilityEnabled) "Accessibility settings · Enabled" else "Enable once in Accessibility settings")
                }
            }
        }

        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("One-time Android setup", fontWeight = FontWeight.SemiBold)
                Text(
                    if (accessibilityEnabled) {
                        "ScreenVeil is enabled in Android Accessibility. When protection is on, it checks the active app window automatically after screen changes. No screen-sharing or screen-recording session is started."
                    } else {
                        "Enable ScreenVeil once in Android Settings → Accessibility → Installed apps. Android requires this manual approval. Afterward, when the verified model is installed and protection is on, ScreenVeil checks the active app window automatically."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "ScreenVeil requests screenshots of the active application window and reads only accessibility event metadata plus window IDs and bounds needed to place redaction masks. It does not inspect view nodes, read app text, tap controls, or perform gestures.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SectionTitle("On-device AI model")
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(GemmaModelCatalog.selected.displayName, fontWeight = FontWeight.SemiBold)
                Text("Google LiteRT Community · Apache-2.0 · multimodal image input", style = MaterialTheme.typography.bodySmall)
                Text("Approx. 2.6 GB. The model is stored in private app storage and is never bundled in the APK.", style = MaterialTheme.typography.bodyMedium)
                Text("Source: ${GemmaModelCatalog.selected.repository}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Immutable model revision and SHA-256 checked before loading", style = MaterialTheme.typography.labelSmall, color = Color(0xFF22643C))
                if (modelInstalled) {
                    Text("Installed and verified", color = Color(0xFF22643C), fontWeight = FontWeight.SemiBold)
                } else if (downloadRunning) {
                    CircularProgressIndicator(progress = { downloadProgress })
                    Text(downloadLabel, style = MaterialTheme.typography.bodySmall)
                } else {
                    Button(
                        onClick = {
                            downloadRunning = true
                            downloadProgress = 0f
                            downloadError = null
                            scope.launch {
                                try {
                                    val file = ModelInstaller.download(context) { downloaded, total ->
                                        val fraction = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            downloadProgress = fraction
                                            downloadLabel = "Downloaded ${downloaded / 1_000_000_000.0} / ${if (total > 0) String.format(Locale.US, "%.2f GB", total / 1_000_000_000.0) else "unknown"}"
                                        }
                                    }
                                    modelInstalled = true
                                    downloadLabel = "Verified and installed: ${file.name}"
                                    if (accessibilityEnabled && preferences.isProtectionEnabled()) {
                                        ScreenProtectionAccessibilityService.requestStart()
                                    }
                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    downloadError = error.message ?: error.javaClass.simpleName
                                } finally {
                                    downloadRunning = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Download and verify Gemma 4") }
                }
                if (downloadError != null) {
                    Text(downloadError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Only Gemma 4 is approved in this build. The model is downloaded only after you press the button. Detection runs offline after installation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SectionTitle("Redaction settings")
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Redaction appearance", fontWeight = FontWeight.SemiBold)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RedactionEffect.entries.forEach { effect ->
                        FilterChip(
                            selected = settings.effect == effect,
                            onClick = { settings = settings.copy(effect = effect).also(preferences::saveSettings) },
                            label = { Text(effect.label) }
                        )
                    }
                }
                Divider()
                Text("Effect strength: ${settings.intensity}", fontWeight = FontWeight.Medium)
                Slider(
                    value = settings.intensity.toFloat(),
                    onValueChange = { value -> settings = settings.copy(intensity = value.toInt()).also(preferences::saveSettings) },
                    valueRange = 4f..32f,
                    steps = 6
                )
                Divider()
                Text("Screenshot check interval: ${settings.frameIntervalMs / 1000.0}s (minimum 0.75s)", fontWeight = FontWeight.Medium)
                Slider(
                    value = settings.frameIntervalMs.toFloat(),
                    onValueChange = { value -> settings = settings.copy(frameIntervalMs = value.toLong()).also(preferences::saveSettings) },
                    valueRange = 750f..5_000f,
                    steps = 7
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Redact uncertain media", fontWeight = FontWeight.Medium)
                        Text("Conservative mode reduces some missed detections but may increase false positives.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = settings.redactUncertain,
                        onCheckedChange = { value -> settings = settings.copy(redactUncertain = value).also(preferences::saveSettings) }
                    )
                }
            }
        }

        SectionTitle("Protection policy")
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PolicyLine("All swimwear", "Always redact, including ordinary beach and sports swimwear")
                PolicyLine("Suggestive", "Redact suggestive imagery, sexualized poses and revealing presentations")
                PolicyLine("Explicit", "Redact explicit nudity and sexual activity")
                PolicyLine("Ordinary content", "Allow ordinary clothing and non-suggestive media")
            }
        }

        SectionTitle("Privacy and limitations")
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("• Screenshots are requested through Android AccessibilityService only after you explicitly enable ScreenVeil in Accessibility settings.")
                Text("• No MediaProjection session, screen-recording dialog, foreground capture notification or Display over other apps permission is used.")
                Text("• Screen pixels are analyzed locally. No screenshots are uploaded or stored as a gallery; short-lived model input files are kept in private cache and deleted after inference.")
                Text("• Internet access is used only when you explicitly download Gemma 4.")
                Text("• Android may refuse screenshots of FLAG_SECURE or otherwise protected content. ScreenVeil does not bypass that restriction.")
                Text("• Android rate-limits accessibility screenshots. Capture is event-triggered with periodic refresh, not a 30-FPS video recording. Fast video may be only partially inspected.", color = MaterialTheme.colorScheme.error)
                Text("• Gemma-generated media boxes are estimates. The model can miss content, classify it incorrectly or produce delayed redaction. This is an experimental application, not a guaranteed safety filter.", color = MaterialTheme.colorScheme.error)
                Text("• Accessibility access is powerful. ScreenVeil uses events only to schedule screenshots and does not read window text, inspect view nodes, tap, or control other apps.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

@Composable
private fun PolicyLine(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
