package dev.ferry.android

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.ferry.android.screen.ControlService
import dev.ferry.core.FfiPeer
import dev.ferry.core.coreVersion
import kotlinx.coroutines.launch

/** A dialog before Android asks (or before sending the user to Settings): [go] runs on [button]. */
class Explain(val title: String, val text: String, val button: String, val go: () -> Unit)

class MainActivity : ComponentActivity() {
    private var scanning by mutableStateOf(false)
    private var pasting by mutableStateOf(false)
    private var toSend by mutableStateOf<List<Uri>?>(null)
    private var listenerOn by mutableStateOf(false)
    private var dndAccess by mutableStateOf(false)
    private var notifsOn by mutableStateOf(true)
    private var btAllowed by mutableStateOf(false)
    private var associated by mutableStateOf(false)
    private var explain by mutableStateOf<Explain?>(null)
    /** Sent to Settings for this grant's access: switched on in onResume if it was given. */
    private var pending: Grant? = null
    private val asked: SharedPreferences by lazy { getSharedPreferences("asked", MODE_PRIVATE) }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { notifsOn = notificationsOn() }
    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) scanning = true else Ferry.toast(this, "Ferry needs the camera to scan the QR code. You can paste the pairing link instead.")
    }
    private val askBluetooth = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        btAllowed = Ble.allowed(this)
        if (btAllowed) Ble.sync(this) else Ferry.toast(this, "Bluetooth stays off. Ferry still reaches your Mac over Wi-Fi or USB.")
    }
    private val associate = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        associated = Cdm.associated(this)
        if (associated) Ferry.toast(this, "Ferry can now start by itself when your Mac is nearby")
    }
    /** Messages / Calls: the switch turns on only once Android grants all of the feature's permissions. */
    private val askGrant = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val g = Grant.entries.firstOrNull { it.permissions.isNotEmpty() && r.keys.containsAll(it.permissions) } ?: return@registerForActivityResult
        if (r.values.all { it }) Grants.set(this, g, true)
        else Ferry.toast(this, "${g.label} stays off. It needs every permission Android asked about.")
    }
    /** Files: the switch turns on only once All files access is granted. */
    private val askStorage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (PhoneFiles.accessGranted()) Grants.set(this, Grant.FILES, true)
        else Ferry.toast(this, "Files stays off. It needs All files access for Ferry.")
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
                request(listOf(Manifest.permission.POST_NOTIFICATIONS), "Notifications", quiet = true) {
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
        setContent {
            FerryTheme {
                if (scanning) {
                    BackHandler { scanning = false }
                    Scanner(onDone = { scanning = false }, onPaste = { scanning = false; pasting = true })
                } else {
                    Home(
                        HomeUi(
                            listenerOn = listenerOn,
                            dndAccess = dndAccess,
                            notifsOn = notifsOn,
                            bluetooth = BluetoothUi(
                                allowed = btAllowed,
                                canAssociate = Cdm.available(this),
                                associated = associated,
                                onAllow = { request(Ble.PERMISSIONS.toList(), "Nearby devices") { askBluetooth.launch(Ble.PERMISSIONS) } },
                                onAssociate = {
                                    Ferry.node.value?.let { node ->
                                        Cdm.associate(this, node.blePrefix()) { associate.launch(IntentSenderRequest.Builder(it).build()) }
                                    }
                                },
                            ),
                            onScan = {
                                if (granted(Manifest.permission.CAMERA)) scanning = true
                                else request(listOf(Manifest.permission.CAMERA), "Camera") { askCamera.launch(Manifest.permission.CAMERA) }
                            },
                            onPaste = { pasting = true },
                            onSend = { pickFiles.launch(arrayOf("*/*")) },
                            onGrant = { g, on -> if (on) turnOn(g) else Grants.set(this, g, false) },
                            onFix = ::turnOn,
                            onControl = ::explainControl,
                            onAllowNotifications = ::allowNotifications,
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
                        lifecycleScope.launch { Ferry.sendPicked(this@MainActivity, peer, uris) }
                    }
                }
                explain?.let { e ->
                    AlertDialog(
                        onDismissRequest = { explain = null },
                        title = { Text(e.title) },
                        text = { Text(e.text) },
                        confirmButton = { TextButton(onClick = { explain = null; e.go() }) { Text(e.button) } },
                        dismissButton = { TextButton(onClick = { explain = null }) { Text("Not now") } },
                    )
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
        dndAccess = getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
        notifsOn = notificationsOn()
        btAllowed = Ble.allowed(this)
        associated = Cdm.associated(this)
        // Permissions / All files access revoked in Settings: show the switch off rather than on-but-silent.
        Grants.on.value.filter { !Grants.allowed(this, it) }.forEach { Grants.set(this, it, false) }
        // Back from Settings: the switch turns on only if the access was really given.
        pending?.let { g ->
            pending = null
            when {
                ready(g) -> Grants.set(this, g, true)
                g in Grants -> Ferry.toast(this, "${g.label} still can't work without that access")
                else -> Ferry.toast(this, "${g.label} stays off. Ferry wasn't given the access it needs.")
            }
        }
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

    private fun notificationsOn() = NotificationManagerCompat.from(this).areNotificationsEnabled()

    /** Every runtime permission and special access [g] needs is in place. */
    private fun ready(g: Grant) = g.permissions.all(::granted) &&
        (g != Grant.FILES || PhoneFiles.accessGranted()) &&
        (g != Grant.NOTIFICATIONS && g != Grant.MEDIA || FerryListener.enabled(this)) &&
        (g != Grant.DND || getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted)

    /** A switch turned on: on right away if nothing is missing, else explain what Android will ask first. */
    private fun turnOn(g: Grant) {
        if (ready(g)) return Grants.set(this, g, true)
        if (g == Grant.FILES) PhoneFiles.unavailable()?.let { return Ferry.toast(this, it) }
        explain = when {
            !g.permissions.all(::granted) -> Explain("Turn on ${g.label}?", ASK_WHY.getValue(g), "Continue") {
                request(g.permissions, ASK_NAMES.getValue(g), then = g) { askGrant.launch(g.permissions.toTypedArray()) }
            }
            g == Grant.FILES -> Explain(
                "Allow All files access?",
                "Android will open All files access next. Turn it on for Ferry, then come back. Your Mac can browse and copy files only while Files is on here.",
                "Continue",
            ) { if (Build.VERSION.SDK_INT >= 30) settings { PhoneFiles.askAccess(this, askStorage::launch) } }
            g == Grant.DND -> Explain(
                "Allow Do Not Disturb access?",
                "Android will open Do Not Disturb access next. Allow it for Ferry, then come back, so your Mac can turn Do Not Disturb on and off here.",
                "Open settings",
            ) { pending = g; settings { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) } }
            else -> Explain(
                "Allow notification access?",
                "${g.label} needs notification access. Android will open it next: turn it on for Ferry, then come back. Ferry only sends what you turn on here.",
                "Open settings",
            ) { pending = g; settings { FerryListener.openSettings(this) } }
        }
    }

    /**
     * Asks Android for [perms] through [launch], unless it would refuse without asking (denied for good):
     * then explains how to allow [what] in Settings, and turns [then] on when the user comes back with it.
     */
    private fun request(perms: List<String>, what: String, then: Grant? = null, quiet: Boolean = false, launch: () -> Unit) {
        val missing = perms.filterNot(::granted)
        val before = asked.getStringSet("asked", null).orEmpty()
        if (missing.any { it in before && !shouldShowRequestPermissionRationale(it) }) {
            if (quiet) return
            explain = Explain(
                "Allow $what in Settings",
                "Android won't ask again. In Settings, open Permissions, allow $what for Ferry, then come back.",
                "Open settings",
            ) { pending = then; settings { startActivity(appSettings()) } }
            return
        }
        asked.edit { putStringSet("asked", before + missing) }
        launch()
    }

    private fun allowNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            request(listOf(Manifest.permission.POST_NOTIFICATIONS), "Notifications") { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
        } else {
            settings { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
        }
    }

    private fun explainControl(on: Boolean) {
        explain = if (on) {
            Explain("Turn off Ferry control?", "In Accessibility settings, open Ferry control and turn it off.", "Open settings") {
                settings { ControlService.openSettings(this) }
            }
        } else {
            Explain(
                "Turn on Ferry control?",
                "In Accessibility settings, find Ferry control (it may be under Installed apps or Downloaded apps) and turn it on. " +
                    "Your Mac can then tap, scroll and type here, only while it's connected to your screen, with a Stop sharing notification the whole time.",
                "Open settings",
            ) { settings { ControlService.openSettings(this) } }
        }
    }

    private fun appSettings() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())

    /** Opens a Settings screen; some OEM builds lack one, so fall back to Ferry's app page. */
    private fun settings(open: () -> Unit) {
        try {
            open()
        } catch (e: Exception) {
            Log.w(TAG, "settings screen unavailable", e)
            runCatching { startActivity(appSettings()) }.onFailure { Ferry.toast(this, "Couldn't open Settings. Open Settings → Apps → Ferry yourself.") }
        }
    }

    private companion object {
        val ASK_WHY = mapOf(
            Grant.MESSAGES to "Android will ask to let Ferry read and send SMS and read your contacts. " +
                "Ferry uses them only to show your texts on your Mac, with names, and to send the replies you type there.",
            Grant.CALLS to "Android will ask to let Ferry see calls, read your call history, answer calls and read your contacts. " +
                "Ferry uses them only to show who's calling on your Mac, answer or decline from there, and list recent calls.",
        )
        /** As Android's Settings → Permissions names them. */
        val ASK_NAMES = mapOf(Grant.MESSAGES to "SMS and Contacts", Grant.CALLS to "Phone, Call logs and Contacts")
    }
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

class BluetoothUi(
    val allowed: Boolean, val canAssociate: Boolean, val associated: Boolean,
    val onAllow: () -> Unit, val onAssociate: () -> Unit,
)

class HomeUi(
    val listenerOn: Boolean, val dndAccess: Boolean, val notifsOn: Boolean, val bluetooth: BluetoothUi,
    val onScan: () -> Unit, val onPaste: () -> Unit, val onSend: () -> Unit,
    val onGrant: (Grant, Boolean) -> Unit, val onFix: (Grant) -> Unit, val onControl: (Boolean) -> Unit,
    val onAllowNotifications: () -> Unit,
)

/** Main screen sections, in order, with the grants each holds. */
private val BASICS = listOf(Grant.NOTIFICATIONS, Grant.MEDIA, Grant.CLIPBOARD, Grant.BATTERY, Grant.RING, Grant.LINKS, Grant.DND)
private val MESSAGES_AND_CALLS = listOf(Grant.MESSAGES, Grant.CALLS)

@Composable
private fun Home(ui: HomeUi) {
    val ctx = LocalContext.current
    val node by Ferry.node.collectAsStateWithLifecycle()
    val status by Ferry.status.collectAsStateWithLifecycle()
    val peers by Ferry.peers.collectAsStateWithLifecycle()
    val nearby by Ferry.nearby.collectAsStateWithLifecycle()
    val usb by Ferry.usb.collectAsStateWithLifecycle()
    val ble by Ferry.ble.collectAsStateWithLifecycle()
    val recent by Ferry.recent.collectAsStateWithLifecycle()
    val pairing by Ferry.pairing.collectAsStateWithLifecycle()
    val on by Grants.on.collectAsStateWithLifecycle()
    var forgetting by remember { mutableStateOf<FfiPeer?>(null) }
    val links = { p: FfiPeer -> Ferry.links(p.id, nearby, usb, ble) }
    val running = node != null

    Scaffold { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            StatusHeader(status, running, peers, links, node?.deviceName())

            if (!ui.notifsOn) Notice(
                "Notifications are off",
                "You won't see pairing requests, received files or Find my phone until you allow them.",
                "Allow notifications", ui.onAllowNotifications,
            )

            if (peers.isEmpty()) {
                PairCard(running, pairing, ui.onScan, ui.onPaste)
            } else {
                Section(if (peers.size == 1) "Your Mac" else "Your Macs") {
                    peers.forEach { p ->
                        val via = links(p)
                        Item(p.name, if (via.isEmpty()) "Not connected right now" else "Connected over ${joinAnd(via)}") {
                            TextButton(onClick = { forgetting = p }, modifier = Modifier.semantics { contentDescription = "Forget ${p.name}" }) { Text("Forget") }
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = ui.onSend, enabled = running) { Text("Send files") }
                    // The app has focus here, so Android lets it read the clipboard.
                    OutlinedButton(onClick = { Clip.send(ctx, Clip.read(ctx)) }, enabled = running) { Text("Send clipboard") }
                    TextButton(onClick = ui.onScan, enabled = running && !pairing) { Text("Pair another Mac") }
                }
            }

            if (recent.isNotEmpty()) Section("Recent activity") {
                recent.forEach { r ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(r.text, style = MaterialTheme.typography.bodyMedium)
                        r.progress?.takeIf { it < 1f }?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            }

            // Before pairing, only the pairing card matters; the switches appear once there's a Mac (or one is still on).
            if (peers.isNotEmpty() || on.isNotEmpty()) {
                Section("Basics", "Everyday things your Mac can show or do. Everything starts off: turn on only what you want.") {
                    BASICS.forEach { g -> GrantItem(g, g in on, ui.onGrant) }
                    if ((Grant.NOTIFICATIONS in on || Grant.MEDIA in on) && !ui.listenerOn) {
                        Fix("Notification access is off, so notifications and media controls can't reach your Mac.") {
                            ui.onFix(if (Grant.NOTIFICATIONS in on) Grant.NOTIFICATIONS else Grant.MEDIA)
                        }
                    }
                    if (Grant.DND in on && !ui.dndAccess) {
                        Fix("Do Not Disturb access is off, so your Mac can't change Do Not Disturb here.") { ui.onFix(Grant.DND) }
                    }
                }
                Section("Messages & calls", "Your texts and calls on your Mac. Android asks for permission when you turn these on.") {
                    MESSAGES_AND_CALLS.forEach { g -> GrantItem(g, g in on, ui.onGrant) }
                }
                ScreenSection(ui.onControl)
                Section("Files", "Files your Mac sends always land in Downloads/Ferry. Turn this on to use Browse Files on your Mac.") {
                    GrantItem(Grant.FILES, Grant.FILES in on, ui.onGrant, unavailable = PhoneFiles.unavailable())
                }
                ConnectionSection(ui.bluetooth, usb = Ferry.usbListening.collectAsStateWithLifecycle().value, running)
                TextButton(onClick = { Grants.allOff(ctx) }, enabled = on.isNotEmpty()) { Text("Turn off all sharing") }
            }

            Text(
                "Ferry ${versionName(ctx)} · core ${coreVersion()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }

    forgetting?.let { p ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("Forget ${p.name}?") },
            text = { Text("This phone stops sharing with ${p.name}, and it can't connect any more. To use them together again, pair from your Mac.") },
            confirmButton = { TextButton(onClick = { forgetting = null; Ferry.forget(ctx, p) }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Cancel") } },
        )
    }
}

private fun versionName(ctx: android.content.Context) =
    runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()

/** Status, the connected Mac(s) and how: the text says it all, the color only echoes it. */
@Composable
private fun StatusHeader(status: String, running: Boolean, peers: List<FfiPeer>, links: (FfiPeer) -> List<String>, phone: String?) {
    val ctx = LocalContext.current
    val linked = peers.filter { links(it).isNotEmpty() }
    val failed = status.startsWith(FerryService.FAILED)
    val stopped = status == FerryService.STOPPED
    val (title, detail) = when {
        failed -> "Ferry couldn't start" to status.removePrefix(FerryService.FAILED)
        stopped -> "Ferry is stopped" to "Your Mac can't reach this phone until you start Ferry again."
        !running -> "Starting Ferry…" to "This takes a moment."
        peers.isEmpty() -> "No Mac paired yet" to "Pair once, and Ferry connects by itself from then on."
        linked.isNotEmpty() -> "Connected to ${joinAnd(linked.map { it.name })}" to "Over ${joinAnd(linked.flatMap(links).distinct())}"
        else -> "Not connected" to "Ferry connects when your Mac is on the same Wi-Fi, plugged in with USB, or in Bluetooth range."
    }
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = when {
        failed -> scheme.errorContainer to scheme.onErrorContainer
        linked.isNotEmpty() && running -> scheme.primaryContainer to scheme.onPrimaryContainer
        else -> scheme.surfaceContainerHigh to scheme.onSurface
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Ferry", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
        Card(colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Text(detail, style = MaterialTheme.typography.bodyMedium)
                }
                phone?.let { Text("This phone: $it", style = MaterialTheme.typography.bodySmall) }
                if (failed || stopped) {
                    Button(onClick = { FerryService.start(ctx) }, modifier = Modifier.padding(top = 8.dp)) { Text("Start Ferry") }
                }
            }
        }
    }
}

/** First run: one clear next step. */
@Composable
private fun PairCard(running: Boolean, pairing: Boolean, onScan: () -> Unit, onPaste: () -> Unit) {
    Section("Pair with your Mac") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(
                "On your Mac, open Ferry: click its icon in the menu bar.",
                "Choose Pair Your Phone… to show a QR code.",
                "Tap Scan QR code below and point the camera at it.",
            ).forEachIndexed { i, step ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${i + 1}.", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    Text(step, style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (pairing) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Pairing with your Mac…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Button(onClick = onScan, enabled = running && !pairing, modifier = Modifier.fillMaxWidth()) {
                Text(if (running) "Scan QR code" else "Starting Ferry…")
            }
            TextButton(onClick = onPaste, enabled = running && !pairing, modifier = Modifier.fillMaxWidth()) { Text("Paste pairing link instead") }
            Text(
                "Phone plugged into your Mac? Choose Pair over USB… in the Ferry menu instead. It needs USB debugging turned on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Mirroring needs no setup (Android asks each time); control needs the Accessibility service. */
@Composable
private fun ScreenSection(onControl: (Boolean) -> Unit) {
    val control by ControlService.instance.collectAsStateWithLifecycle()
    val on = control != null
    Section("Screen & control", "Choose Mirror Screen on your Mac to see this phone's screen. Android asks you to confirm each time, and you can stop it from the notification.") {
        Item(
            "Ferry control",
            if (on) "On: your Mac can tap, scroll and type here while it's connected to your screen" else "Off: turn on to tap, scroll and type from your Mac, or use Share Cursor",
            Modifier.toggleable(on, role = Role.Switch) { onControl(on) },
        ) { Switch(checked = on, onCheckedChange = null) }
    }
}

@Composable
private fun ConnectionSection(ui: BluetoothUi, usb: Boolean, running: Boolean) {
    Section("Connection", "Ferry finds your Mac on the same Wi-Fi, over a USB cable, or over Bluetooth when neither is around.") {
        Item("USB cable", if (usb) "Ready. Plug into your Mac to connect (needs USB debugging)." else "Not available right now")
        if (Ble.supported) {
            Item(
                "Bluetooth",
                if (ui.allowed) "On. A slow backup link when there's no shared Wi-Fi or cable." else "Off. Lets Ferry notice your Mac nearby and keep a slow backup link. Ferry doesn't use your location.",
            ) { if (!ui.allowed) OutlinedButton(onClick = ui.onAllow) { Text("Allow") } }
            if (ui.canAssociate) Item(
                "Start in the background",
                if (ui.associated) "On. Android can start Ferry when your Mac comes into Bluetooth range."
                else "Pick your Mac once, so Android can start Ferry when it comes into range. Needs Bluetooth and Location on.",
            ) { if (!ui.associated) OutlinedButton(onClick = ui.onAssociate, enabled = running && ui.allowed) { Text("Set up") } }
        }
    }
}

@Composable
private fun Section(title: String, about: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        about?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp), content = content)
        }
    }
}

@Composable
private fun Item(title: String, detail: String?, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = detail?.let { { Text(it) } },
        trailingContent = trailing,
        modifier = modifier,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** The whole row toggles (one touch target, read as a switch); [unavailable] explains a disabled one. */
@Composable
private fun GrantItem(g: Grant, checked: Boolean, onChange: (Grant, Boolean) -> Unit, unavailable: String? = null) {
    val enabled = unavailable == null
    Item(g.label, unavailable ?: g.why, Modifier.toggleable(checked, enabled = enabled, role = Role.Switch) { onChange(g, it) }) {
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A switch that is on but missing access: says what's wrong and how to fix it. */
@Composable
private fun Fix(text: String, onFix: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onFix) { Text("Allow access") }
    }
}

@Composable
private fun Notice(title: String, text: String, button: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onClick) { Text(button) }
        }
    }
}

/** Full-screen camera with the how-to on a readable surface (not text over the live image). */
@Composable
private fun Scanner(onDone: () -> Unit, onPaste: () -> Unit) {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        QrScanner(
            onTicket = { onDone(); Ferry.pair(ctx, it) },
            onError = { onDone(); Ferry.toast(ctx, it) },
            modifier = Modifier.fillMaxSize(),
        )
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp).padding(bottom = 24.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Scan the QR code on your Mac", style = MaterialTheme.typography.titleMedium)
                Text("In Ferry on your Mac, choose Pair Your Phone… (or Pair Another Phone…) and point the camera at the code.", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onDone) { Text("Cancel") }
                    TextButton(onClick = onPaste) { Text("Paste link instead") }
                }
            }
        }
    }
}

@Composable
private fun PasteDialog(onDismiss: () -> Unit, onPair: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val ticket = Tickets.extract(text)
    val bad = text.isNotBlank() && ticket == null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste a pairing link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("On your Mac, choose Pair Your Phone… and click Copy Pairing Link. Get the link to this phone, for example by emailing it to yourself, then paste it here.")
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Pairing link") },
                    placeholder = { Text("ferry://pair?…") },
                    isError = bad,
                    supportingText = if (bad) ({ Text("That isn't a Ferry pairing link. It starts with ferry://pair?") }) else null,
                    minLines = 3,
                )
            }
        },
        confirmButton = { TextButton(onClick = { ticket?.let(onPair) }, enabled = ticket != null) { Text("Pair") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Picks the Mac to send to; skips the question when there is exactly one. */
@Composable
fun PeerPicker(files: Int, onDismiss: () -> Unit, onPick: (FfiPeer) -> Unit) {
    val node by Ferry.node.collectAsStateWithLifecycle()
    val peers by Ferry.peers.collectAsStateWithLifecycle()
    if (node != null && peers.size == 1) {
        LaunchedEffect(Unit) { onPick(peers[0]) }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (peers.isEmpty()) "Send ${count(files, "file")}" else "Send ${count(files, "file")} to") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    node == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Starting Ferry…")
                    }
                    peers.isEmpty() -> Text("No Mac is paired yet. Open Ferry and scan the QR code on your Mac first.")
                    else -> peers.forEach { p -> TextButton(onClick = { onPick(p) }, modifier = Modifier.fillMaxWidth()) { Text(p.name) } }
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
                        // Digit by digit for TalkBack, not "one hundred twenty-three…".
                        modifier = Modifier.semantics { contentDescription = p.code.toList().joinToString(" ") },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { p.answer.complete(true) }) { Text("Match") } },
        dismissButton = { TextButton(onClick = { p.answer.complete(false) }) { Text("Doesn't match") } },
    )
}
