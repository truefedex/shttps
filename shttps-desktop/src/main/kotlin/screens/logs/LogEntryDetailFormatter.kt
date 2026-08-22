package com.phlox.simpleserver.screens.logs

import androidx.compose.runtime.Composable
import com.phlox.server.utils.MultiMap
import com.phlox.simpleserver.shttps_desktop.generated.resources.Res
import com.phlox.simpleserver.shttps_desktop.generated.resources.log_client
import com.phlox.simpleserver.shttps_desktop.generated.resources.log_connection_id
import com.phlox.simpleserver.shttps_desktop.generated.resources.log_duration
import com.phlox.simpleserver.shttps_desktop.generated.resources.log_duration_ms
import com.phlox.simpleserver.shttps_desktop.generated.resources.log_ended
import com.phlox.simpleserver.shttps_desktop.generated.resources.log_host
import com.phlox.simpleserver.shttps_desktop.generated.resources.log_request
import com.phlox.simpleserver.shttps_desktop.generated.resources.log_request_body
import com.phlox.simpleserver.shttps_desktop.generated.resources.log_response
import com.phlox.simpleserver.shttps_desktop.generated.resources.log_response_body
import com.phlox.simpleserver.shttps_desktop.generated.resources.log_started
import com.phlox.simpleserver.shttps_desktop.generated.resources.log_timing
import com.phlox.simpleserver.utils.ServerLogsCollector
import org.jetbrains.compose.resources.stringResource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntryDetailLabels(
    val timing: String,
    val started: String,
    val ended: String,
    val duration: String,
    val durationMs: (Long) -> String,
    val client: String,
    val host: String,
    val connectionId: String,
    val request: String,
    val requestBody: String,
    val response: String,
    val responseBody: String,
)

@Composable
fun rememberLogEntryDetailLabels(): LogEntryDetailLabels {
    val durationMsTemplate = stringResource(Res.string.log_duration_ms)
    return LogEntryDetailLabels(
        timing = stringResource(Res.string.log_timing),
        started = stringResource(Res.string.log_started),
        ended = stringResource(Res.string.log_ended),
        duration = stringResource(Res.string.log_duration),
        durationMs = { ms -> durationMsTemplate.replace("%1\$d", ms.toString()) },
        client = stringResource(Res.string.log_client),
        host = stringResource(Res.string.log_host),
        connectionId = stringResource(Res.string.log_connection_id),
        request = stringResource(Res.string.log_request),
        requestBody = stringResource(Res.string.log_request_body),
        response = stringResource(Res.string.log_response),
        responseBody = stringResource(Res.string.log_response_body),
    )
}

/**
 * Same structure as Android [com.phlox.simpleserver.activity.logs.LogEntryDetailFormatter]:
 * timing, client, merged Request (line + headers + optional body), merged Response.
 */
object LogEntryDetailFormatter {

    fun format(entry: ServerLogsCollector.LogEntry, labels: LogEntryDetailLabels): String {
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        return buildString(512) {
            appendLine(labels.timing)
            append(labels.started).appendLine(df.format(Date(entry.startTimeMillis)))
            append(labels.ended).appendLine(df.format(Date(entry.endTimeMillis)))
            append(labels.duration).appendLine(labels.durationMs(entry.endTimeMillis - entry.startTimeMillis))
            appendLine()

            appendLine(labels.client)
            append(labels.host).appendLine(entry.hostAddress ?: "")
            append(labels.connectionId).appendLine(entry.connectionId.toString())
            appendLine()

            appendLine(labels.request)
            var linePath = entry.rawPathAndQuery
            if (linePath.isNullOrEmpty()) {
                linePath = entry.path ?: ""
            }
            append(entry.method ?: "").append(' ').appendLine(linePath)
            appendHeaderLines(entry.requestHeaders)
            appendLine()

            if (!entry.requestBodyText.isNullOrEmpty()) {
                appendLine(labels.requestBody)
                appendLine(entry.requestBodyText)
                appendLine()
            } else if (entry.requestBodyNote != null) {
                appendLine(labels.requestBody)
                append('[').append(entry.requestBodyNote).appendLine(']')
                appendLine()
            }

            appendLine(labels.response)
            append(entry.responseCode).append(' ').appendLine(entry.responsePhrase ?: "")
            appendHeaderLines(entry.responseHeaders)

            if (!entry.responseBodyText.isNullOrEmpty()) {
                appendLine()
                appendLine(labels.responseBody)
                appendLine(entry.responseBodyText)
            } else if (entry.responseBodyNote != null) {
                appendLine()
                appendLine(labels.responseBody)
                append('[').append(entry.responseBodyNote).appendLine(']')
            }
        }
    }

    private fun StringBuilder.appendHeaderLines(headers: MultiMap<String, String>?) {
        if (headers == null || headers.isEmpty) return
        val keys = headers.keys().sortedWith(String.CASE_INSENSITIVE_ORDER)
        for (key in keys) {
            for (value in headers.getAll(key)) {
                append(key).append(": ").appendLine(value)
            }
        }
    }
}
