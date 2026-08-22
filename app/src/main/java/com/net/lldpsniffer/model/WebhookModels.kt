package com.net.lldpsniffer.model

data class WebhookConfig(
    val enabled: Boolean = false,
    val url: String = "",
    val deviceName: String = "",
    val authHeaderName: String = "",
    val authHeaderValue: String = "",
    val useCustomTemplate: Boolean = false,
    val template: String = DEFAULT_DISCORD_TEMPLATE
) {
    companion object {
        const val DEFAULT_DISCORD_TEMPLATE = """{"content": "{{summary_markdown}}"}"""
    }
}
