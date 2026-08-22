package com.net.lldpsniffer.webhook

import com.net.lldpsniffer.model.CopyFieldId
import com.net.lldpsniffer.model.CopyFieldsConfig
import com.net.lldpsniffer.model.CopyFormat
import com.net.lldpsniffer.model.MergedSwitchportRecord
import com.net.lldpsniffer.model.displayTitle
import com.net.lldpsniffer.model.rawValueFor
import com.net.lldpsniffer.model.toCopyText
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Substitutes `{{placeholder}}` tokens in a user-supplied JSON template with values from a
 * capture record, then validates the result is well-formed JSON before it's ever sent.
 */
object WebhookTemplateEngine {

    private val PLACEHOLDER_REGEX = Regex("\\{\\{\\s*([a-z0-9_]+)\\s*\\}\\}")

    sealed class RenderResult {
        data class Success(val json: JSONObject) : RenderResult()
        data class Failure(val message: String) : RenderResult()
    }

    fun render(
        template: String,
        record: MergedSwitchportRecord,
        copyConfig: CopyFieldsConfig,
        deviceName: String
    ): RenderResult {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val substituted = PLACEHOLDER_REGEX.replace(template) { match ->
            val key = match.groupValues[1]
            quoteInner(resolvePlaceholder(key, record, copyConfig, dateFormat, deviceName))
        }

        return try {
            val parsed = JSONTokener(substituted).nextValue()
            if (parsed !is JSONObject) {
                RenderResult.Failure("Template must render to a JSON object")
            } else {
                RenderResult.Success(parsed)
            }
        } catch (e: JSONException) {
            RenderResult.Failure("Invalid JSON after substitution: ${e.message}")
        }
    }

    private fun resolvePlaceholder(
        key: String,
        record: MergedSwitchportRecord,
        copyConfig: CopyFieldsConfig,
        dateFormat: SimpleDateFormat,
        deviceName: String
    ): String {
        CopyFieldId.entries.firstOrNull { it.templateKey() == key }?.let {
            return record.rawValueFor(it, dateFormat) ?: ""
        }
        return when (key) {
            "title" -> record.displayTitle()
            "summary_basic" -> record.toCopyText(copyConfig, CopyFormat.BASIC)
            "summary_markdown" -> record.toCopyText(copyConfig, CopyFormat.MARKDOWN)
            "summary_json" -> record.toCopyText(copyConfig, CopyFormat.JSON)
            "device_name" -> deviceName
            else -> ""
        }
    }

    /**
     * JSONObject.quote() wraps its input in double quotes for safe embedding as a JSON string
     * literal; templates provide their own surrounding quotes, so this strips those back off,
     * leaving only the escaped inner content.
     */
    private fun quoteInner(value: String): String {
        val quoted = JSONObject.quote(value)
        return quoted.substring(1, quoted.length - 1)
    }
}
