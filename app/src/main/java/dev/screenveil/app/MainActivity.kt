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
import androidx.compose.runtime.LaunchedEffect
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
import dev.screenveil.app.model.ModelProfile
import dev.screenveil.app.model.SpecializedModelCatalog
import dev.screenveil.app.policy.RedactionEffect
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var accessibilityEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        removeAbandonedFrameFiles()
        ModelInstaller.removeRetiredArtifacts(this)
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
        // Returning from Android Accessibility settings only refreshes permission state.
        // Protection must always be started with a separate, explicit tap in this app.
        accessibilityEnabled = ScreenProtectionAccessibilityService.isAccessibilityEnabled(this)
    }

    private fun beginProtection() {
        val profile = AppPreferences(this).getModelProfile()
        if (!ModelInstaller.hasProfileMarkers(this, profile)) {
            val missing = profile.requiredModels.filterNot { ModelInstaller.hasVerifiedMarker(this, it) }
                .joinToString { it.displayName }
            Toast.makeText(this, "Download and verify the selected profile models: $missing", Toast.LENGTH_LONG).show()
            return
        }

        accessibilityEnabled = ScreenProtectionAccessibilityService.isAccessibilityEnabled(this)
        if (!accessibilityEnabled) {
            // Do not persist an ON request before Android's Accessibility settings are opened.
            // The user must return here and press Start after granting the service permission.
            AppPreferences(this).setProtectionEnabled(false)
            openAccessibilitySettings()
            Toast.makeText(
                this,
                "Enable ScreenVeil in Accessibility settings, return to ScreenVeil, then tap Start protection. Enabling Accessibility alone does not start protection.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        AppPreferences(this).setProtectionEnabled(true)
        if (!ScreenProtectionAccessibilityService.requestStart()) {
            AppPreferences(this).setProtectionEnabled(false)
            Toast.makeText(this, "Android is reconnecting the accessibility service. Return to ScreenVeil and tap Start protection again.", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopProtection() {
        AppPreferences(this).setProtectionEnabled(false)
        ScreenProtectionAccessibilityService.requestStop()
        ProtectionStatus.update(
            ProtectionStatus.State(
                active = false,
                modelReady = ModelInstaller.hasProfileMarkers(this, AppPreferences(this).getModelProfile()),
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
    val protectionHealthy = protection.active && protection.modelReady &&
        protection.error == null && !protection.opaqueCoverActive
    var selectedProfile by remember { mutableStateOf(preferences.getModelProfile()) }
    var installedModelIds by remember {
        mutableStateOf(
            SpecializedModelCatalog.all
                .filter { ModelInstaller.hasVerifiedMarker(context, it) }.map { it.id }.toSet()
        )
    }
    val activeProfileInstalled = selectedProfile.selectable && selectedProfile.requiredModels.isNotEmpty() &&
        selectedProfile.requiredModels.all { it.id in installedModelIds }
    LaunchedEffect(protection.error, protection.modelReady, selectedProfile) {
        // The service performs a full SHA-256 re-hash immediately before loading. If it removes
        // a stale/corrupt marker on failure, refresh the UI so the user can download again.
        if (!protection.modelReady && protection.error != null) {
            installedModelIds = SpecializedModelCatalog.all
                .filter { ModelInstaller.hasVerifiedMarker(context, it) }.map { it.id }.toSet()
        }
    }
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

        Card(colors = CardDefaults.cardColors(containerColor = if (protectionHealthy) Color(0xFFE7F4EB) else Color(0xFFE9EEF6))) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(
                        imageVector = if (protection.active) Icons.Default.Security else Icons.Default.Lock,
                        contentDescription = null,
                        tint = if (protectionHealthy) Color(0xFF22643C) else MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            !protection.active -> "Protection is paused"
                            protection.opaqueCoverActive -> "App temporarily covered · safe fallback active"
                            !protection.modelReady -> "Protection not ready · selected model profile is loading/unavailable"
                            else -> "Protection active"
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(protection.message, style = MaterialTheme.typography.bodyMedium)
                if (protection.lastInferenceMs != null) {
                    Text(
                        "AI inference: ${protection.lastInferenceMs} ms · capture-to-overlay: ${protection.lastEndToEndMs ?: "—"} ms · ${protection.regionsDetected} media region(s) detected · ${protection.regionsRedacted} redacted",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (protection.error != null) {
                    Text(protection.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onStart,
                        enabled = !protection.active && activeProfileInstalled && !downloadRunning,
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
                        "ScreenVeil is enabled in Android Accessibility. Protection remains OFF until you explicitly tap Start protection here. If Android interrupts capture or model processing fails repeatedly, ScreenVeil pauses and removes its overlay. No screen-recording session is started."
                    } else {
                        "Enable ScreenVeil in Android Settings → Accessibility → Installed apps. Then return here and tap Start protection. Enabling Accessibility alone never starts monitoring."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "ScreenVeil requests screenshots of the active application window and reads only accessibility event metadata plus window IDs and bounds needed to place redaction masks. It does not inspect view nodes, read app text, tap controls, or perform gestures. Startup and technical failures never place a full-window cover; failures pause protection and clear the overlay.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SectionTitle("AI model profile")
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Choose the local inference pipeline", fontWeight = FontWeight.SemiBold)
                ModelProfile.entries.forEach { profile ->
                    FilterChip(
                        selected = selectedProfile == profile,
                        enabled = !protection.active && !downloadRunning && profile.selectable,
                        onClick = {
                            selectedProfile = profile
                            preferences.setModelProfile(profile)
                        },
                        label = { Text(profile.title) }
                    )
                    Text(profile.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (profile.availabilityNotice != null) {
                        Text(profile.availabilityNotice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                Divider()
                if (selectedProfile == ModelProfile.FAST_SPECIALIZED || selectedProfile == ModelProfile.BALANCED_SPECIALIZED) {
                    val selectedModels = selectedProfile.requiredModels
                    Text(
                        if (selectedProfile == ModelProfile.BALANCED_SPECIALIZED) "Balanced ensemble · no Gemma required" else "Fast specialist pipeline · no Gemma required",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text("${selectedModels.size} independently pretrained models, all executed locally after download:", style = MaterialTheme.typography.bodySmall)
                    selectedModels.forEach { model ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(model.displayName, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                                Text(if (model.id in installedModelIds) "Installed · hash checked on load" else "Not installed", color = if (model.id in installedModelIds) Color(0xFF22643C) else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                            }
                            Text("${model.runtime} · declared ${model.license} · ${(model.minimumBytes / 1_000_000)}–${(model.maximumBytes / 1_000_000)} MB bounds", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("SHA-256 pinned · ${model.repository}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    val model = GemmaModelCatalog.selected
                    Text(model.displayName, fontWeight = FontWeight.SemiBold)
                    Text("Google LiteRT Community · ${model.license} · approximately 2.6 GB", style = MaterialTheme.typography.bodySmall)
                    Text("Multimodal general-purpose screenshot analysis. Stored in private app storage; not bundled in the APK.", style = MaterialTheme.typography.bodyMedium)
                    Text("SHA-256 pinned · ${model.repository}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                val requiredModels = selectedProfile.requiredModels
                val missingModels = requiredModels.filter { it.id !in installedModelIds }
                if (!selectedProfile.selectable || requiredModels.isEmpty()) {
                    Text("This profile cannot be downloaded or started yet. No unverified model artifact will be installed.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                } else if (activeProfileInstalled) {
                    Text("All selected model files have verification markers; full SHA-256 is rechecked before loading.", color = Color(0xFF22643C), fontWeight = FontWeight.SemiBold)
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
                                    missingModels.forEachIndexed { index, model ->
                                        downloadLabel = "Downloading ${model.displayName} (${index + 1}/${missingModels.size})"
                                        ModelInstaller.download(context, model) { downloaded, total ->
                                            val fraction = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
                                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                downloadProgress = fraction
                                                downloadLabel = "${model.displayName}: ${downloaded / 1_000_000} / ${if (total > 0) total / 1_000_000 else "?"} MB"
                                            }
                                        }
                                    }
                                    installedModelIds = SpecializedModelCatalog.all
                                        .filter { ModelInstaller.hasVerifiedMarker(context, it) }.map { it.id }.toSet()
                                    downloadLabel = "All selected model artifacts passed SHA-256 verification."
                                    // Downloading models never starts protection. The user must explicitly
                    // press Start protection after installation has completed.
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
                    ) { Text("Download and verify ${missingModels.size} missing model(s)") }
                }
                if (downloadError != null) {
                    Text(downloadError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Network access is used only for model downloads initiated here. Downloads require HTTPS, approved hosts, bounded sizes and exact pinned SHA-256. Screen images are not uploaded; inference is local. No model is trained or fine-tuned. Balanced is about 60 MB; Fast about 38 MB; Gemma is a separate ~2.6 GB option. Hash verification confirms file identity, not model safety or accuracy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (selectedProfile == ModelProfile.FAST_SPECIALIZED || selectedProfile == ModelProfile.BALANCED_SPECIALIZED) {
                    Text(
                        if (selectedProfile == ModelProfile.BALANCED_SPECIALIZED) {
                            "Scope limitation: this profile has one person detector and two general NSFW classifiers, but NO approved clothing/swimwear classifier. The ViT scans nine overlapping screen tiles only when no person is detected. Non-explicit person boxes remain UNCERTAIN and are redacted only while 'Redact uncertain content' is enabled (enabled by default). This is a conservative privacy trade-off, not semantic swimwear detection. It cannot guarantee every swimsuit, every image/video frame or every suggestive scene. Thresholds are uncalibrated and not Galaxy S24 FE benchmark results."
                        } else {
                            "Scope limitation: the fast profile has a person detector and Yahoo OpenNSFW only; it has NO approved clothing/swimwear classifier and no screen-tile fallback. Non-explicit person boxes remain UNCERTAIN and are redacted only while 'Redact uncertain content' is enabled (enabled by default). It cannot guarantee swimwear, images without a detected person, cartoons or all suggestive scenes."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
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
                Text("• Internet access is used only when you explicitly download the models for the selected profile.")
                Text("• Android may refuse screenshots of FLAG_SECURE or otherwise protected content. ScreenVeil does not bypass that restriction.")
                Text("• Android rate-limits accessibility screenshots. Capture is event-triggered with periodic refresh, not a 30-FPS video recording. Fast video may be only partially inspected.", color = MaterialTheme.colorScheme.error)
                Text("• During window changes, scrolling, malformed model output or inference errors, ScreenVeil temporarily covers the active app window with an opaque layer until a valid analysis completes. This is intentionally conservative and can interrupt visibility while still allowing touch input.", color = MaterialTheme.colorScheme.error)
                Text("• Every model can miss content or classify it incorrectly. The fast profile does not classify all media types; Gemma may also return incorrect or delayed media boxes. This is an experimental application, not a guaranteed safety filter.", color = MaterialTheme.colorScheme.error)
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
