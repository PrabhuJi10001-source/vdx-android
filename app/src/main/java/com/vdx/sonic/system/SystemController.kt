package com.vdx.sonic.system

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SystemController — Louie parity for phone/system actions that can use
 * Android APIs directly (offline-capable where possible).
 */
class SystemController(private val context: Context) {

    companion object {
        private const val TAG = "VDX-System"
    }

    fun execute(name: String, params: Map<String, String> = emptyMap()): String {
        return try {
            when (name.lowercase()) {
                "battery" -> batteryStatus()
                "datetime", "time", "date" -> dateTimeStatus()
                "wifi_on" -> setWifi(true)
                "wifi_off" -> setWifi(false)
                "wifi_toggle" -> toggleWifi()
                "bt_on" -> openBluetoothSettings("Turn Bluetooth on from settings")
                "bt_off" -> openBluetoothSettings("Turn Bluetooth off from settings")
                "bt_settings" -> openBluetoothSettings("Opened Bluetooth settings")
                "data_settings" -> openSettings(Settings.ACTION_DATA_ROAMING_SETTINGS, "Opened mobile data settings")
                "flashlight_on", "flashlight_off", "flashlight_toggle" ->
                    openSettings(Settings.ACTION_SETTINGS, "Use quick settings for flashlight (OEM restricted)")
                "alarm" -> setAlarm(params)
                "ringer_silent" -> setRinger(AudioManager.RINGER_MODE_SILENT)
                "ringer_vibrate" -> setRinger(AudioManager.RINGER_MODE_VIBRATE)
                "ringer_normal" -> setRinger(AudioManager.RINGER_MODE_NORMAL)
                "screenshot" -> openSettings(Settings.ACTION_SETTINGS, "Use power+volume for screenshot, or system screenshot gesture")
                "dial" -> dial(params["number"] ?: params["contact"] ?: "")
                "sms_compose" -> smsCompose(params["number"] ?: "", params["message"] ?: "")
                "email_compose" -> emailCompose(params["contact"] ?: "", params["message"] ?: "", params["subject"] ?: "")
                "web_search" -> webSearch(params["query"] ?: "")
                "play_store_search" -> playStoreSearch(params["query"] ?: "")
                "open_url" -> openUrl(params["url"] ?: "")
                "contact_search" -> contactLookup(params["contact"] ?: params["name"] ?: "")
                "wifi_settings" -> openSettings(Settings.ACTION_WIFI_SETTINGS, "Opened Wi-Fi settings")
                "app_settings" -> openSettings(Settings.ACTION_APPLICATION_SETTINGS, "Opened app settings")
                else -> "Unknown system action: $name"
            }
        } catch (e: Exception) {
            Log.e(TAG, "system action failed: $name", e)
            "System action failed: ${e.message}"
        }
    }

    private fun batteryStatus(): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return "Battery $pct%${if (charging) ", charging" else ""}"
    }

    private fun dateTimeStatus(): String {
        val fmt = SimpleDateFormat("EEEE, MMM d h:mm a", Locale.getDefault())
        return fmt.format(Date())
    }

    @Suppress("DEPRECATION")
    private fun setWifi(enabled: Boolean): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            openSettings(Settings.ACTION_WIFI_SETTINGS, "Open Wi-Fi settings to turn Wi-Fi ${if (enabled) "on" else "off"}")
        } else {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifi.isWifiEnabled = enabled
            "Wi-Fi ${if (enabled) "on" else "off"}"
        }
    }

    @Suppress("DEPRECATION")
    private fun toggleWifi(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            openSettings(Settings.ACTION_WIFI_SETTINGS, "Opened Wi-Fi settings")
        } else {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val next = !wifi.isWifiEnabled
            wifi.isWifiEnabled = next
            "Wi-Fi ${if (next) "on" else "off"}"
        }
    }

    private fun openBluetoothSettings(msg: String): String {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return msg
    }

    private fun openSettings(action: String, msg: String): String {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return msg
    }

    private fun setAlarm(params: Map<String, String>): String {
        val hour = params["hour"]?.toIntOrNull()
        val minute = params["minute"]?.toIntOrNull() ?: 0
        val message = params["label"] ?: "VDX alarm"
        if (hour == null) {
            context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Opened alarms"
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "Alarm set for $hour:${minute.toString().padStart(2, '0')}"
    }

    private fun setRinger(mode: Int): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.ringerMode = mode
        return when (mode) {
            AudioManager.RINGER_MODE_SILENT -> "Silent mode on"
            AudioManager.RINGER_MODE_VIBRATE -> "Vibrate mode on"
            else -> "Ringer normal"
        }
    }

    private fun dial(numberOrName: String): String {
        if (numberOrName.isBlank()) return "No number to dial"
        val number = if (numberOrName.matches(Regex("^[\\d+\\-\\s()]+$"))) {
            numberOrName
        } else {
            contactLookup(numberOrName).let { result ->
                Regex("""\+?[\d\-\s()]{7,}""").find(result)?.value ?: ""
            }
        }
        if (number.isBlank()) {
            // Open dialer with name for a11y follow-up
            val intent = Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return "Open dialer for $numberOrName"
        }
        val uri = Uri.parse("tel:${number.filter { it.isDigit() || it == '+' }}")
        val canCall = ContextCompat.checkSelfPermission(context, android.Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        val intent = if (canCall) {
            Intent(Intent.ACTION_CALL, uri)
        } else {
            Intent(Intent.ACTION_DIAL, uri)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return if (canCall) "Calling $number" else "Dialing $number"
    }

    private fun smsCompose(number: String, message: String): String {
        val uri = Uri.parse("smsto:${number.ifBlank { "" }}")
        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "SMS composer opened"
    }

    private fun emailCompose(to: String, body: String, subject: String): String {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "Email composer opened for $to"
    }

    private fun webSearch(query: String): String {
        val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra("query", query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            openUrl("https://www.google.com/search?q=${Uri.encode(query)}")
        }
        return "Searching for $query"
    }

    private fun playStoreSearch(query: String): String {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=${Uri.encode(query)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(market)
        } catch (_: Exception) {
            openUrl("https://play.google.com/store/search?q=${Uri.encode(query)}")
        }
        return "Play Store search: $query"
    }

    private fun openUrl(url: String): String {
        if (url.isBlank()) return "No URL"
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Opened $url"
    }

    fun contactLookup(name: String): String {
        if (name.isBlank()) return "No contact name"
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return "Contacts permission needed for $name"
        }
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val n = it.getString(0)
                val num = it.getString(1)
                return "$n: $num"
            }
        }
        return "No contact found for $name"
    }
}
