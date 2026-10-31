package dev.ferry.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.ferry.android.screen.ControlService
import dev.ferry.core.FfiPeer
import dev.ferry.core.coreVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var scanning by mutableStateOf(false)
    private var pasting by mutableStateOf(false)
    private var toSend by mutableStateOf<List<Uri>?>(null)
    private var listenerOn by mutableStateOf(false)
    private var btAllowed by mutableStateOf(false)
    private var associated by mutableStateOf(false)

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) scanning = true else Ferry.toast(this, "Ferry needs the camera to scan the Mac's QR code")
    }
    private val askBluetooth = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        btAllowed = Ble.allowed(this)
        if (btAllowed) Ble.sync(this) else Ferry.toast(this, "Without Bluetooth, Ferry reaches your Mac only over Wi-Fi or USB")
    }
    private val associate = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        associated = Cdm.associated(this)
        if (associated) Ferry.toast(this, "Ferry can now start in the background when your Mac is near")
    }
    /** Messages / Calls: the switch turns on only once Android grants all of the feature's permissions. */
    private val askGrant = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val g = Grant.entries.firstOrNull { it.permissions.isNotEmpty() && r.keys.containsAll(it.permissions) } ?: return@registerForActivityResult
        if (r.values.all { it }) Grants.set(this, g, true)
        else Ferry.toast(this, "${g.short} needs all of those permissions (Settings → Apps → Ferry → Permissions)")
    }
    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) toSend = uris
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            handle(intent)
            if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        setContent {
            FerryTheme {
                if (scanning) {
                    BackHandler { scanning = false }
                    Box(Modifier.fillMaxSize()) {
                        QrScanner(
                            onTicket = { scanning = false; Ferry.pair(this@MainActivity, it) },
                            onError = { scanning = false; Ferry.toast(this@MainActivity, it) },
                            modifier = Modifier.fillMaxSize(),
                        )
                        Column(Modifier.align(Alignment.BottomCenter).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Point at the QR in Ferry on your Mac (Pair a device…)", color = MaterialTheme.colorScheme.onPrimary)
                            Button(onClick = { scanning = false }) { Text("Cancel") }
                        }
                    }
                } else {
                    Home(
                        onScan = {
                            if (granted(Manifest.permission.CAMERA)) scanning = true else askCamera.launch(Manifest.permission.CAMERA)
                        },
                        onPaste = { pasting = true },
                        onSend = { pickFiles.launch(arrayOf("*/*")) },
                        listenerOn = listenerOn,
                        onAsk = { askGrant.launch(it.permissions.toTypedArray()) },
                        bluetooth = BluetoothUi(
                            allowed = btAllowed,
                            canAssociate = Cdm.available(this) && !associated,
                            onAllow = { askBluetooth.launch(Ble.PERMISSIONS) },
                            onAssociate = {
                                Ferry.node.value?.let { node ->
                                    Cdm.associate(this, node.blePrefix()) { associate.launch(IntentSenderRequest.Builder(it).build()) }
                                }
                            },
                        ),
                    )
                }
                if (pasting) PasteDialog(onDismiss = { pasting = false }) { ticket ->
                    pasting = false
                    Ferry.pair(this, ticket)
                }
                toSend?.let { uris ->
                    PeerPicker(uris.size, onDismiss = { toSend = null }) { peer ->
                        toSend = null
                        send(peer, uris)
                    }
                }
                PairPromptDialog()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        Ferry.uiVisible++
        // Visible activity: allowed to start the foreground service (Android 12+ background rule).
        FerryService.start(this)
        Ferry.refreshPeers()
    }

    override fun onResume() {
        super.onResume()
        listenerOn = FerryListener.enabled(this) // may have changed in system settings
        btAllowed = Ble.allowed(this)
        associated = Cdm.associated(this)
        // Permissions revoked in Settings: show the switch off rather than on-but-silent.
        Grants.on.value.filter { it.permissions.isNotEmpty() && !Grants.allowed(this, it) }.forEach { Grants.set(this, it, false) }
        FerryService.instance?.let(Calls::sync)
    }

    override fun onStop() {
        Ferry.uiVisible--
        super.onStop()
    }

    /** Set by the notification's "Send clipboard": read once we have window focus. */
    private var clipPending = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && clipPending) {
            clipPending = false
            Clip.send(this, Clip.read(this))
            moveTaskToBack(true) // back to whatever the user was doing
        }
    }

    private fun handle(intent: Intent?) {
        if (intent?.getBooleanExtra(Clip.EXTRA_SEND, false) == true) {
            intent.removeExtra(Clip.EXTRA_SEND)
            clipPending = true
            return
        }
        if (intent?.action != Intent.ACTION_VIEW) return
        Tickets.extract(intent.dataString)?.let { Ferry.pair(this, it) }
    }

    private fun send(peer: FfiPeer, uris: List<Uri>) {
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) { Ferry.open(this@MainActivity, uris) }
            Ferry.send(this@MainActivity, peer, files)
        }
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

@Composable
fun FerryTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme: ColorScheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun Home(onScan: () -> Unit, onPaste: () -> Unit, onSend: () -> Unit, listenerOn: Boolean, onAsk: (Grant) -> Unit, bluetooth: BluetoothUi) {
    val ctx = LocalContext.current
    val node by Ferry.node.collectAsStateWithLifecycle()
    val status by Ferry.status.collectAsStateWithLifecycle()
    val usb by Ferry.usbListening.collectAsStateWithLifecycle()
    val peers by Ferry.peers.collectAsStateWithLifecycle()
    val nearby by Ferry.nearby.collectAsStateWithLifecycle()
    val ble by Ferry.ble.collectAsStateWithLifecycle()
    val recent by Ferry.recent.collectAsStateWithLifecycle()

    Scaffold { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 20.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Ferry", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp))
            node?.let { Text("${it.deviceName()} · ${it.deviceId().take(8)}", style = MaterialTheme.typography.titleMedium) }
            Text(status + (if (usb) " · USB bridge listening" else "") + (if (ble != null) " · Connected over Bluetooth" else ""), style = MaterialTheme.typography.bodyMedium)

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onScan, enabled = node != null) { Text("Scan QR") }
                OutlinedButton(onClick = onPaste, enabled = node != null) { Text("Paste ticket") }
                OutlinedButton(onClick = onSend, enabled = node != null && peers.isNotEmpty()) { Text("Send files") }
                // The app has focus here, so Android lets it read the clipboard.
                OutlinedButton(onClick = { Clip.send(ctx, Clip.read(ctx)) }, enabled = node != null && peers.isNotEmpty()) {
                    Text("Send clipboard")
                }
            }

            Text("Paired Macs", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            if (peers.isEmpty()) {
                Text("None yet. On the Mac, choose Pair a device… in the Ferry menu, then tap Scan QR.")
            }
            peers.forEach { peer ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(peer.name, fontWeight = FontWeight.Medium)
                        Text(
                            when (peer.id) {
                                ble -> "Connected over Bluetooth"
                                in nearby -> "On this network"
                                else -> "Not seen nearby"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { Ferry.forget(ctx, peer) }) { Text("Forget") }
                }
            }

            if (Ble.supported && peers.isNotEmpty()) BluetoothSection(bluetooth)

            GrantsSection(listenerOn, onAsk)

            if (peers.isNotEmpty()) ScreenSection()

            if (recent.isNotEmpty()) {
                Text("Recent", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                recent.forEach { Text(it.text, style = MaterialTheme.typography.bodyMedium) }
            }

            Text(
                "Core ${coreVersion()}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
    }
}

class BluetoothUi(val allowed: Boolean, val canAssociate: Boolean, val onAllow: () -> Unit, val onAssociate: () -> Unit)

/** Bluetooth permission (API 31+, no location) and the CDM association for background starts. */
@Composable
private fun BluetoothSection(ui: BluetoothUi) {
    val node by Ferry.node.collectAsStateWithLifecycle()
    Text("Bluetooth", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Text(
        "Notices your Mac nearby and keeps a slow backup link when there's no shared Wi-Fi or cable. Ferry doesn't use your location.",
        style = MaterialTheme.typography.bodySmall,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!ui.allowed) OutlinedButton(onClick = ui.onAllow) { Text("Allow Bluetooth") }
        if (ui.canAssociate) OutlinedButton(onClick = ui.onAssociate, enabled = node != null) { Text("Allow Ferry in the background") }
    }
    if (ui.canAssociate) {
        Text(
            "Pick your Mac once so Android lets Ferry start by itself when the Mac is in Bluetooth range (needs Bluetooth and Location switched on).",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** The phone owner's switches for what paired Macs get / may do. All off until turned on here. */
@Composable
private fun GrantsSection(listenerOn: Boolean, onAsk: (Grant) -> Unit) {
    val ctx = LocalContext.current
    val on by Grants.on.collectAsStateWithLifecycle()
    Text("Share with your Mac", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Text(
        if (on.isEmpty()) "Nothing is shared. Files only." else "Sharing: " + on.sorted().joinToString { it.short },
        style = MaterialTheme.typography.bodySmall,
    )
    Grant.entries.forEach { g ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(g.label)
                if (g.why.isNotEmpty()) Text(g.why, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = g in on, onCheckedChange = { if (it && g.permissions.isNotEmpty()) onAsk(g) else Grants.set(ctx, g, it) })
        }
    }
    if ((Grant.NOTIFICATIONS in on || Grant.MEDIA in on) && !listenerOn) {
        Text("Notifications and media controls need notification access.", style = MaterialTheme.typography.bodySmall)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { FerryListener.openSettings(ctx) }) {
            Text(if (listenerOn) "Notification access: on" else "Allow notification access")
        }
        TextButton(onClick = { Grants.allOff(ctx) }, enabled = on.isNotEmpty()) { Text("Turn all off") }
    }
}

/** Mirroring needs no setup (Android asks each time); control needs the Accessibility service. */
@Composable
private fun ScreenSection() {
    val ctx = LocalContext.current
    val control by ControlService.instance.collectAsStateWithLifecycle()
    Text("Screen & control", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Text(
        "Your Mac can mirror this screen after you tap Start now here each time. Turn on Ferry control to also tap, scroll and type from the Mac.",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedButton(onClick = { ControlService.openSettings(ctx) }) {
        Text(if (control != null) "Ferry control: on" else "Turn on Ferry control")
    }
}

@Composable
private fun PasteDialog(onDismiss: () -> Unit, onPair: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val ticket = Tickets.extract(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste a pairing ticket") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("ferry://pair?…") },
                isError = text.isNotBlank() && ticket == null,
                minLines = 3,
            )
        },
        confirmButton = { TextButton(onClick = { ticket?.let(onPair) }, enabled = ticket != null) { Text("Pair") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Picks the Mac to send to; skips the question when there is exactly one. */
@Composable
fun PeerPicker(count: Int, onDismiss: () -> Unit, onPick: (FfiPeer) -> Unit) {
    val node by Ferry.node.collectAsStateWithLifecycle()
    val peers by Ferry.peers.collectAsStateWithLifecycle()
    if (node != null && peers.size == 1) {
        LaunchedEffect(Unit) { onPick(peers[0]) }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send $count file(s) to") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    node == null -> Text("Starting Ferry…")
                    peers.isEmpty() -> Text("No paired Mac yet. Open Ferry and pair one first.")
                    else -> peers.forEach { p -> TextButton(onClick = { onPick(p) }) { Text(p.name) } }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The 6-digit code check, for pairings started on either side. */
@Composable
private fun PairPromptDialog() {
    val prompt by Ferry.prompt.collectAsStateWithLifecycle()
    val p = prompt ?: return
    AlertDialog(
        onDismissRequest = {}, // an explicit answer only
        title = { Text("Pair with ${p.peerName}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Check that ${p.peerName} shows the same code:")
                SelectionContainer {
                    Text(
                        p.code.chunked(3).joinToString(" "),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { p.answer.complete(true) }) { Text("Match") } },
        dismissButton = { TextButton(onClick = { p.answer.complete(false) }) { Text("Doesn't match") } },
    )
}
