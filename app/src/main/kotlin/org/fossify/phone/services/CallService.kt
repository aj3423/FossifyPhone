package org.fossify.phone.services

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log
import org.fossify.commons.extensions.canUseFullScreenIntent
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.PERMISSION_POST_NOTIFICATIONS
import org.fossify.phone.activities.CallActivity
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.isOutgoing
import org.fossify.phone.extensions.keyguardManager
import org.fossify.phone.extensions.powerManager
import org.fossify.phone.helpers.CallManager
import org.fossify.phone.helpers.CallNotificationManager
import org.fossify.phone.helpers.NoCall
import org.fossify.phone.models.Events
import org.greenrobot.eventbus.EventBus
import java.io.File
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.time.Instant
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.concurrent.thread

class CallService : InCallService() {
    private val callNotificationManager by lazy { CallNotificationManager(this) }

    private val callListener = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            if (state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING) {
                callNotificationManager.cancelNotification()
            } else {
                callNotificationManager.setupNotification()
            }
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        CallManager.onCallAdded(call)
        CallManager.inCallService = this
        call.registerCallback(callListener)

        // Incoming/Outgoing (locked): high priority (FSI)
        // Incoming (unlocked): if user opted in, low priority ➜ manual activity start, otherwise high priority (FSI)
        // Outgoing (unlocked): low priority ➜ manual activity start
        val isIncoming = !call.isOutgoing()
        if (isIncoming) {
            IncomingCallLogger.append(this, call)
        }

        val isDeviceLocked = !powerManager.isInteractive || keyguardManager.isDeviceLocked
        val lowPriority = when {
            isIncoming && isDeviceLocked -> false
            !isIncoming && isDeviceLocked -> false
            isIncoming && !isDeviceLocked -> config.alwaysShowFullscreen
            else -> true
        }

        callNotificationManager.setupNotification(lowPriority)
        if (
            lowPriority
            || !hasPermission(PERMISSION_POST_NOTIFICATIONS)
            || !canUseFullScreenIntent()
        ) {
            try {
                startActivity(CallActivity.getStartIntent(this))
            } catch (_: Exception) {
                // seems like startActivity can throw AndroidRuntimeException and
                // ActivityNotFoundException, not yet sure when and why, lets show a notification
                callNotificationManager.setupNotification()
            }
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callListener)
        val wasPrimaryCall = call == CallManager.getPrimaryCall()
        CallManager.onCallRemoved(call)
        if (CallManager.getPhoneState() == NoCall) {
            CallManager.inCallService = null
            callNotificationManager.cancelNotification()
        } else {
            callNotificationManager.setupNotification()
            if (wasPrimaryCall) {
                startActivity(CallActivity.getStartIntent(this))
            }
        }

        EventBus.getDefault().post(Events.RefreshCallLog)
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        if (audioState != null) {
            CallManager.onAudioStateChanged(audioState)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        callNotificationManager.cancelNotification()
    }
}

@Suppress("TooManyFunctions")
private object IncomingCallLogger {
    private const val TAG = "CallService"
    private const val CALL_LOG_FILENAME = "calls.log"
    private const val PREFS_NAME = "incoming_call_logger"
    private const val PREF_LOG_URI = "log_uri"
    private val downloadsRelativePath = "${Environment.DIRECTORY_DOWNLOADS}/"
    private const val MAX_LOG_DEPTH = 5
    private val callLogLock = Any()

    fun append(context: Context, call: Call) {
        // Avoid blocking the telecom callback with reflective logging work.
        thread(name = "incoming-call-logger") {
            try {
                val content = runCatching { buildCallLogEntry(call) }
                    .getOrElse { buildFallbackLogEntry(call, it) }
                val location = appendLog(context, content)
                Log.i(TAG, "Incoming call log appended to $location")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to append incoming call log", e)
            }
        }
    }

    private fun appendLog(context: Context, content: String): String {
        synchronized(callLogLock) {
            appendToPublicDownloads(context, content)?.let { return it }
            return appendToAppStorage(context, content)
        }
    }

    private fun appendToPublicDownloads(context: Context, content: String): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appendToPublicDownloadsWithMediaStore(context, content)
        } else {
            appendToLegacyPublicDownloads(content)
        }
    }

    private fun appendToPublicDownloadsWithMediaStore(context: Context, content: String): String? {
        getSavedDownloadUri(context)?.let { savedUri ->
            appendToUri(context, savedUri, content)?.let { return it }
            clearSavedDownloadUri(context)
        }

        val targetUri = findExistingDownloadUri(context) ?: createDownloadUri(context) ?: return null
        saveDownloadUri(context, targetUri)
        return appendToUri(context, targetUri, content)
    }

    private fun appendToUri(context: Context, uri: Uri, content: String): String? {
        return try {
            val outputStream = context.contentResolver.openOutputStream(uri, "wa") ?: return null
            outputStream.bufferedWriter().use { writer ->
                writer.append(content)
            }
            uri.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun findExistingDownloadUri(context: Context): Uri? {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf(CALL_LOG_FILENAME, "$downloadsRelativePath%")
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} ASC, ${MediaStore.MediaColumns._ID} ASC"

        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }

            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
            return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
        }

        return null
    }

    private fun createDownloadUri(context: Context): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, CALL_LOG_FILENAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, downloadsRelativePath)
        }
        return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    }

    private fun getSavedDownloadUri(context: Context): Uri? {
        val uriString = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_LOG_URI, null)
            ?: return null
        return Uri.parse(uriString)
    }

    private fun saveDownloadUri(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_LOG_URI, uri.toString())
            .apply()
    }

    private fun clearSavedDownloadUri(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(PREF_LOG_URI)
            .apply()
    }

    @Suppress("DEPRECATION")
    private fun appendToLegacyPublicDownloads(content: String): String? {
        return try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }

            val file = File(downloadsDir, CALL_LOG_FILENAME)
            file.appendText(content)
            file.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    private fun appendToAppStorage(context: Context, content: String): String {
        val logDir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(logDir, CALL_LOG_FILENAME)
        file.appendText(content)
        return file.absolutePath
    }

    private fun buildCallLogEntry(call: Call): String {
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val sb = StringBuilder()

        sb.appendLine("========== onCallAdded ${Instant.now()} ==========")
        appendValue(sb, "callSummary", call.toString(), 0, visited)
        appendValue(sb, "number", runCatching { call.details.handle?.schemeSpecificPart }.getOrNull(), 0, visited)
        appendValue(sb, "callDetailsSummary", runCatching { call.details.toString() }.getOrNull(), 0, visited)
        appendValue(sb, "call", call, 0, visited)
        sb.appendLine()
        sb.appendLine()

        return sb.toString()
    }

    private fun buildFallbackLogEntry(call: Call, error: Throwable): String {
        return buildString {
            appendLine("========== onCallAdded ${Instant.now()} ==========")
            appendLine("callSummary=\"${escapeForLog(call.toString())}\"")
            appendLine("logError=\"${escapeForLog(error::class.java.simpleName)}: ${escapeForLog(error.message.orEmpty())}\"")
            appendLine()
            appendLine()
        }
    }

    private fun appendValue(
        sb: StringBuilder,
        name: String,
        value: Any?,
        depth: Int,
        visited: MutableSet<Any>,
    ) {
        val indent = "  ".repeat(depth)

        when {
            value == null -> sb.append(indent).append(name).append("=null").appendLine()
            isSimpleValue(value) -> {
                sb.append(indent).append(name).append("=").append(formatSimpleValue(value)).appendLine()
            }

            value is Bundle -> appendBundle(sb, name, value, depth, visited)
            value is Map<*, *> -> appendMap(sb, name, value, depth, visited)
            value is Iterable<*> -> appendIterable(sb, name, value, depth, visited)
            value.javaClass.isArray -> appendArray(sb, name, value, depth, visited)
            depth >= MAX_LOG_DEPTH -> {
                sb.append(indent)
                    .append(name)
                    .append("=")
                    .append(value.javaClass.name)
                    .append("(")
                    .append(escapeForLog(value.toString()))
                    .append(")")
                    .appendLine()
            }

            !visited.add(value) -> {
                sb.append(indent)
                    .append(name)
                    .append("=<already logged ")
                    .append(value.javaClass.name)
                    .append(">")
                    .appendLine()
            }

            else -> appendObject(sb, name, value, depth, visited)
        }
    }

    private fun appendBundle(
        sb: StringBuilder,
        name: String,
        bundle: Bundle,
        depth: Int,
        visited: MutableSet<Any>,
    ) {
        val indent = "  ".repeat(depth)
        if (!visited.add(bundle)) {
            sb.append(indent).append(name).append("=<already logged android.os.Bundle>").appendLine()
            return
        }

        sb.append(indent).append(name).append(" (Bundle)").appendLine()
        val keys = bundle.keySet().toList().sorted()
        if (keys.isEmpty()) {
            sb.append(indent).append("  ").append("<empty>").appendLine()
            return
        }

        keys.forEach { key ->
            val child = try {
                bundle.get(key)
            } catch (e: Exception) {
                "<error: ${e::class.java.simpleName}: ${e.message.orEmpty()}>"
            }
            appendValue(sb, "[$key]", child, depth + 1, visited)
        }
    }

    private fun appendMap(
        sb: StringBuilder,
        name: String,
        map: Map<*, *>,
        depth: Int,
        visited: MutableSet<Any>,
    ) {
        val indent = "  ".repeat(depth)
        if (!visited.add(map)) {
            sb.append(indent).append(name).append("=<already logged ").append(map.javaClass.name).append(">").appendLine()
            return
        }

        sb.append(indent).append(name).append(" (").append(map.javaClass.name).append(")").appendLine()
        if (map.isEmpty()) {
            sb.append(indent).append("  ").append("<empty>").appendLine()
            return
        }

        map.entries
            .sortedBy { it.key?.toString().orEmpty() }
            .forEach { entry ->
                appendValue(sb, "[${entry.key}]", entry.value, depth + 1, visited)
            }
    }

    private fun appendIterable(
        sb: StringBuilder,
        name: String,
        values: Iterable<*>,
        depth: Int,
        visited: MutableSet<Any>,
    ) {
        val indent = "  ".repeat(depth)
        if (!visited.add(values)) {
            sb.append(indent).append(name).append("=<already logged ").append(values.javaClass.name).append(">").appendLine()
            return
        }

        sb.append(indent).append(name).append(" (").append(values.javaClass.name).append(")").appendLine()
        var index = 0
        values.forEach { item ->
            appendValue(sb, "[$index]", item, depth + 1, visited)
            index++
        }
        if (index == 0) {
            sb.append(indent).append("  ").append("<empty>").appendLine()
        }
    }

    private fun appendArray(
        sb: StringBuilder,
        name: String,
        arrayValue: Any,
        depth: Int,
        visited: MutableSet<Any>,
    ) {
        val indent = "  ".repeat(depth)
        if (!visited.add(arrayValue)) {
            sb.append(indent).append(name).append("=<already logged ").append(arrayValue.javaClass.name).append(">").appendLine()
            return
        }

        val size = ReflectArray.getLength(arrayValue)
        sb.append(indent).append(name).append(" (").append(arrayValue.javaClass.name).append(")").appendLine()
        if (size == 0) {
            sb.append(indent).append("  ").append("<empty>").appendLine()
            return
        }

        for (index in 0 until size) {
            appendValue(sb, "[$index]", ReflectArray.get(arrayValue, index), depth + 1, visited)
        }
    }

    private fun appendObject(
        sb: StringBuilder,
        name: String,
        value: Any,
        depth: Int,
        visited: MutableSet<Any>,
    ) {
        val indent = "  ".repeat(depth)
        val methods = value.javaClass.methods
            .filter(::shouldLogMethod)
            .sortedBy { it.name }

        sb.append(indent)
            .append(name)
            .append(" (")
            .append(value.javaClass.name)
            .append(")")
            .appendLine()
        sb.append(indent)
            .append("  ")
            .append("toString=")
            .append(escapeForLog(value.toString()))
            .appendLine()

        if (methods.isEmpty()) {
            return
        }

        methods.forEach { method ->
            val childName = methodNameToAttribute(method)
            val childValue = try {
                method.invoke(value)
            } catch (e: InvocationTargetException) {
                "<error: ${e.targetException::class.java.simpleName}: ${e.targetException.message.orEmpty()}>"
            } catch (e: Exception) {
                "<error: ${e::class.java.simpleName}: ${e.message.orEmpty()}>"
            }
            appendValue(sb, childName, childValue, depth + 1, visited)
        }
    }

    private fun shouldLogMethod(method: Method): Boolean {
        if (method.parameterCount != 0) {
            return false
        }
        if (!Modifier.isPublic(method.modifiers) || Modifier.isStatic(method.modifiers)) {
            return false
        }
        if (method.isSynthetic || method.name == "getClass") {
            return false
        }

        return method.name.startsWith("get") || method.name.startsWith("is")
    }

    private fun methodNameToAttribute(method: Method): String {
        val rawName = when {
            method.name.startsWith("get") -> method.name.removePrefix("get")
            method.name.startsWith("is") -> method.name.removePrefix("is")
            else -> method.name
        }
        return rawName.replaceFirstChar { it.lowercase() }
    }

    private fun isSimpleValue(value: Any): Boolean {
        return value is CharSequence ||
            value is Number ||
            value is Boolean ||
            value is Enum<*> ||
            value is Uri
    }

    private fun formatSimpleValue(value: Any): String {
        return when (value) {
            is CharSequence -> "\"${escapeForLog(value.toString())}\""
            is Uri -> "\"${escapeForLog(value.toString())}\""
            else -> escapeForLog(value.toString())
        }
    }

    private fun escapeForLog(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
    }
}
