package com.net.lldpsniffer.webhook

import com.net.lldpsniffer.model.CopyFieldsConfig
import com.net.lldpsniffer.model.MergedSwitchportRecord
import com.net.lldpsniffer.model.WebhookConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object WebhookSender {

    data class Result(
        val success: Boolean,
        val httpCode: Int? = null,
        val errorMessage: String? = null
    )

    suspend fun send(
        config: WebhookConfig,
        record: MergedSwitchportRecord,
        copyConfig: CopyFieldsConfig
    ): Result = withContext(Dispatchers.IO) {
        if (config.url.isBlank()) {
            return@withContext Result(success = false, errorMessage = "Webhook URL is empty")
        }

        val template = if (config.useCustomTemplate) config.template else WebhookConfig.DEFAULT_DISCORD_TEMPLATE
        val rendered = WebhookTemplateEngine.render(template, record, copyConfig, config.deviceName)
        val payload = when (rendered) {
            is WebhookTemplateEngine.RenderResult.Failure -> {
                return@withContext Result(success = false, errorMessage = rendered.message)
            }
            is WebhookTemplateEngine.RenderResult.Success -> rendered.json
        }

        // Discord-style "username" override: only inject if the device name is set and the
        // template doesn't already define its own username key, so a custom template that
        // already references {{device_name}} (or intentionally omits it) isn't clobbered.
        if (config.deviceName.isNotBlank() && !payload.has("username")) {
            payload.put("username", config.deviceName)
        }

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(config.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Content-Type", "application/json")
                if (config.authHeaderName.isNotBlank() && config.authHeaderValue.isNotBlank()) {
                    setRequestProperty(config.authHeaderName, config.authHeaderValue)
                }
            }

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
            }

            val code = connection.responseCode
            if (code in 200..299) {
                Result(success = true, httpCode = code)
            } else {
                Result(success = false, httpCode = code, errorMessage = "HTTP $code")
            }
        } catch (e: Exception) {
            Result(success = false, errorMessage = e.message ?: e.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }
}
