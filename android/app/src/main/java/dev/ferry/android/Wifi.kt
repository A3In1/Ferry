package dev.ferry.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi

/** A Wi-Fi network shared from the Mac: Android's own "Save this network?" dialog asks; never connects silently. */
object Wifi {
    const val EXTRA_SSID = "dev.ferry.android.SSID"
    const val EXTRA_PASSWORD = "dev.ferry.android.PASSWORD"

    fun fromMac(ctx: Context, from: String, ssid: String, password: String) {
        if (ssid.isEmpty()) return
        if (Build.VERSION.SDK_INT < 30) return Ferry.toast(ctx, "$from shared Wi-Fi “$ssid”: adding it needs Android 11 or newer")
        Log.w(FILES_TAG, "wifi shared, open=${password.isEmpty()}") // never the password (or the SSID)
        val open = Intent(ctx, WifiActivity::class.java).putExtra(EXTRA_SSID, ssid).putExtra(EXTRA_PASSWORD, password)
        if (Ferry.uiVisible > 0) ctx.startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) else Notifs.wifi(ctx, from, ssid, open)
    }

    /** WPA2 if [password] is set, else open. Throws IllegalArgumentException for an invalid SSID/passphrase. */
    @RequiresApi(30)
    fun addIntent(ssid: String, password: String): Intent {
        val s = WifiNetworkSuggestion.Builder().setSsid(ssid).apply { if (password.isNotEmpty()) setWpa2Passphrase(password) }.build()
        return Intent(Settings.ACTION_WIFI_ADD_NETWORKS).putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(s))
    }
}

/** Launches the add-network dialog for result (Settings needs to know the calling app), then closes. */
class WifiActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifs.cancel(this, Notifs.ID_WIFI)
        if (savedInstanceState != null) return // recreated while the dialog is up: wait for its result
        val ssid = intent.getStringExtra(Wifi.EXTRA_SSID).orEmpty()
        try {
            if (Build.VERSION.SDK_INT < 30) error("needs Android 11")
            startActivityForResult(Wifi.addIntent(ssid, intent.getStringExtra(Wifi.EXTRA_PASSWORD).orEmpty()), 1)
        } catch (e: Exception) {
            Log.w(FILES_TAG, "can't offer the network: ${e.javaClass.simpleName}") // the message may echo the passphrase
            Ferry.toast(this, "Couldn't add Wi-Fi “$ssid”")
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        finish()
    }
}
