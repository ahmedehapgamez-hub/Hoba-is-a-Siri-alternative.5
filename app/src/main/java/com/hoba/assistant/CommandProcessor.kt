package com.hoba.assistant

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import java.net.URLEncoder
import java.util.Locale

class CommandProcessor(private val context: Context) {

    private var flashlightOn = false
    private val wakeWords = listOf("hoba", "hooba", "hobba", "huba", "hover", "hola")

    fun extractCommand(candidates: List<String>): String? {
        for (raw in candidates) {
            val lower = raw.lowercase(Locale.US).trim()
            for (wake in wakeWords) {
                if (lower == wake) return ""
                if (lower.startsWith("$wake ")) {
                    return lower.removePrefix("$wake ").trim()
                }
                if (lower.startsWith("$wake,")) {
                    return lower.removePrefix("$wake,").trim()
                }
            }
        }
        return null
    }

    fun execute(command: String): String {
        if (command.isBlank()) {
            return "Yes, I am listening. How can I help?"
        }

        return when {
            command.contains("alarm") -> handleAlarm(command)
            command.contains("whatsapp") -> handleWhatsApp(command)
            command.contains("flashlight") || command.contains("torch") -> handleFlashlight(command)
            command.contains("wi-fi") || command.contains("wifi") -> openSettings(Settings.ACTION_WIFI_SETTINGS, "Opening Wi-Fi settings.")
            command.contains("bluetooth") -> openSettings(Settings.ACTION_BLUETOOTH_SETTINGS, "Opening Bluetooth settings.")
            command.contains("display") || command.contains("brightness") -> openSettings(Settings.ACTION_DISPLAY_SETTINGS, "Opening display settings.")
            command.contains("sound") || command.contains("volume") -> openSettings(Settings.ACTION_SOUND_SETTINGS, "Opening sound settings.")
            command.startsWith("search google for ") || command.startsWith("search for ") -> {
                val query = command.removePrefix("search google for ").removePrefix("search for ").trim()
                openUrl("https://www.google.com/search?q=${URLEncoder.encode(query, "UTF-8")}")
                "Searching Google for $query."
            }
            command.startsWith("open ") -> {
                val target = command.removePrefix("open ").trim()
                if (target.contains(".") && !target.contains(" ")) {
                    val url = if (target.startsWith("http")) target else "https://$target"
                    openUrl(url)
                    "Opening $target."
                } else {
                    openAppByName(target)
                }
            }
            else -> {
                openUrl("https://www.google.com/search?q=${URLEncoder.encode(command, "UTF-8")}")
                "Searching Google for $command."
            }
        }
    }

    private fun handleAlarm(command: String): String {
        val regex = Regex("(\d{1,2})(?::(\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?", RegexOption.IGNORE_CASE)
        val match = regex.find(command) ?: return "Please specify a time, like set alarm for 7:30 AM."
        var hour = match.groupValues[1].toIntOrNull() ?: return "Could not read alarm hour."
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        val period = match.groupValues[3].lowercase(Locale.US)

        if (period.startsWith("p") && hour < 12) hour += 12
        if (period.startsWith("a") && hour == 12) hour = 0

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, "Hoba Alarm")
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "Setting alarm for %02d:%02d.".format(hour, minute)
        } catch (e: Exception) {
            "Unable to open the clock app."
        }
    }

    private fun handleWhatsApp(command: String): String {
        // Pattern: send whatsapp to <name> saying <message>
        val regex = Regex("(?:send\s+)?whatsapp\s+(?:message\s+)?to\s+(.+?)\s+(?:saying|that|message)\s+(.+)", RegexOption.IGNORE_CASE)
        val match = regex.find(command)
        if (match == null) {
            return openAppByName("whatsapp")
        }
        val contactName = match.groupValues[1].trim()
        val message = match.groupValues[2].trim()
        val phone = findPhoneNumber(contactName)

        return try {
            if (phone != null) {
                val cleanPhone = phone.replace("[^+\d]".toRegex(), "")
                val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone&text=${URLEncoder.encode(message, "UTF-8")}")
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.whatsapp")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Opening WhatsApp chat with $contactName."
            } else {
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    setPackage("com.whatsapp")
                    putExtra(Intent.EXTRA_TEXT, message)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(sendIntent)
                "Contact $contactName not found, opened WhatsApp with your message."
            }
        } catch (e: Exception) {
            "WhatsApp is not installed on this device."
        }
    }

    private fun findPhoneNumber(name: String): String? {
        return try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(0) ?: continue
                    val number = cursor.getString(1) ?: continue
                    if (displayName.lowercase(Locale.US).contains(name.lowercase(Locale.US))) {
                        return number
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun handleFlashlight(command: String): String {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return "No camera flash found."
            flashlightOn = when {
                command.contains("off") -> false
                command.contains("on") -> true
                else -> !flashlightOn
            }
            cameraManager.setTorchMode(cameraId, flashlightOn)
            if (flashlightOn) "Flashlight turned on." else "Flashlight turned off."
        } catch (e: Exception) {
            "Could not control flashlight."
        }
    }

    private fun openSettings(action: String, reply: String): String {
        return try {
            val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
            reply
        } catch (e: Exception) {
            "Could not open settings."
        }
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun openAppByName(appName: String): String {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = pm.queryIntentActivities(mainIntent, 0)
        val query = appName.lowercase(Locale.US)
        val matched = apps.firstOrNull {
            val label = it.loadLabel(pm).toString().lowercase(Locale.US)
            val pkg = it.activityInfo.packageName.lowercase(Locale.US)
            label.contains(query) || pkg.contains(query)
        }
        return if (matched != null) {
            val launchIntent = pm.getLaunchIntentForPackage(matched.activityInfo.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                "Opening ${matched.loadLabel(pm)}."
            } else {
                "Cannot launch $appName."
            }
        } else {
            "App $appName not found."
        }
    }
}
