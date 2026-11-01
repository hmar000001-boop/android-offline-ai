package dev.screenveil.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
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
import androidx.core.content.ContextCompat
import dev.screenveil.app.capture.ProtectionStatus
import dev.screenveil.app.capture.ScreenProtectionService
import dev.screenveil.app.model.GemmaModelCatalog
import dev.screenveil.app.model.ModelInstaller
import dev.screenveil.app.policy.ModerationSettings
import dev.screenveil.app.policy.RedactionEffect
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var captureLauncher: ActivityResultLauncher<Intent>
    private lateinit var overlaySettingsLauncher: ActivityResultLauncher<Intent>
    private lateinit var notificationLauncher: ActivityResultLauncher<String>
    private var startPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cacheDir.listFiles()?.filter { it.isFile && it.name.startsWith("screen-frame-") }?.forEach { it.delete() }
        captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                try {
                    ContextCompat.startForegroundService(
                        this,
                        ScreenProtectionService.startIntent(this, result.resultCode, data)
                    )
                } catch (error: Exception) {
                    Toast.makeText(this, "Could not start protection: ${error.message}", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "Screen capture was not authorized. Protection remains off.", Toast.LENGTH_LONG).show()
            }
        }
        overlaySettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) continueStartFlow()
            else Toast.makeText(this, "Overlay permission is required to display redaction masks.", Toast.LENGTH_LONG).show()
        }
        notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Android permits a mediaProjection foreground service without POST_NOTIFICATIONS,
            // but granting it makes the persistent stop control easier to access.
            launchCaptureConsent()
        }

        setContent {
            val colors = lightColorScheme(
                primary = Color(0xFF315A92),
                secondary = Color(0xFF48627D),
                background = Color(0xFFF4F6FA),
                surface = Color.White
            )
            MaterialTheme(colorScheme = colors) {
                ScreenVeilHome(
                    onStart = { beginProtection() },
                    onStop = { stopProtection() }
                )
            }
        }
    }

    private fun beginProtection() {
        if (!ModelInstaller.isVerifiedInstalled(this)) {
            Toast.makeText(this, "Install and verify Gemma 4 before starting protection.", Toast.LENGTH_LONG).show()
            return
        }
        startPending = true
        continueStartFlow()
    }

    private fun continueStartFlow() {
        if (!startPending) return
        if (!Settings.canDrawOverlays(this)) {
            overlaySettingsLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchCaptureConsent()
    }

    private fun launchCaptureConsent() {
        startPending = false
        val manager = getSystemService(MediaProjectionManager::class.java)
        captureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun stopProtection() {
        runCatching { startService(ScreenProtectionService.stopIntent(this)) }
    }
}

@Composable
private fun ScreenVeilHome(onStart: () -> Unit, onStop: () -> Unit) {
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
                    Text(if (protection.active) "Protection session" else "Protection is off", fontWeight = FontWeight.SemiBold)
                }
                Text(protection.message, style = MaterialTheme.typography.bodyMedium)
                if (protection.lastInferenceMs != null) {
                    Text("Last analysis: ${protection.lastInferenceMs} ms · ${protection.regionsRedacted} redacted region(s)", style = MaterialTheme.typography.bodySmall)
                }
                if (protection.error != null) Text(protection.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onStart, enabled = !protection.active && modelInstalled && !downloadRunning, modifier = Modifier.weight(1f)) {
                        Text("Start protection")
                    }
                    OutlinedButton(onClick = onStop, enabled = protection.active, modifier = Modifier.weight(1f)) {
                        Text("Stop")
                    }
                }
            }
        }

        SectionTitle("On-device AI model")
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(GemmaModelCatalog.selected.displayName, fontWeight = FontWeight.SemiBold)
                Text("Google LiteRT Community · Apache-2.0 · multimodal image input", style = MaterialTheme.typography.bodySmall)
                Text("Approx. 2.6 GB. The model is stored in private app storage and is never bundled in the APK.", style = MaterialTheme.typography.bodyMedium)
                Text("Source: ${GemmaModelCatalog.selected.repository}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("SHA-256 checked before loading", style = MaterialTheme.typography.labelSmall, color = Color(0xFF22643C))
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
                if (downloadError != null) Text(downloadError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text(
                    "Trusted model catalogue · Gemma 4 is the only approved model in this build. Additional models will appear only after their publisher, license, runtime compatibility and pinned checksum have been reviewed.",
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
                Text("Analysis interval: ${settings.frameIntervalMs / 1000.0}s", fontWeight = FontWeight.Medium)
                Slider(
                    value = settings.frameIntervalMs.toFloat(),
                    onValueChange = { value -> settings = settings.copy(frameIntervalMs = value.toLong()).also(preferences::saveSettings) },
                    valueRange = 1_000f..5_000f,
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
                Text("• Screen frames are processed locally. No screenshots are uploaded or retained as a gallery.")
                Text("• Internet access is used only when you explicitly download the model.")
                Text("• Android may exclude protected FLAG_SECURE content from screen capture.")
                Text("• Select “Entire screen” in Android's capture dialog to monitor other applications.")
                Text("• Gemma-generated media boxes are estimates. This experimental application can miss content, classify it incorrectly, or show blur with a delay. It is not a guaranteed safety filter.", color = MaterialTheme.colorScheme.error)
                Text("• A running foreground notification and overlay permission are required. Stop protection before uninstalling or changing capture permissions.")
                Text("• Redaction uses one overlay window per media region. Android may block taps directly inside a redacted region; areas outside those rectangles remain unobstructed.", style = MaterialTheme.typography.bodySmall)
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
