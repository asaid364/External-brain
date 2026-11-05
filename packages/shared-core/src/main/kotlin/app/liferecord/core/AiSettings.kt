package app.liferecord.core

import java.net.URI

interface AiConfigurationStorage {
    fun read(): AiConfiguration?
    fun write(value: AiConfiguration, replaceUnreadable: Boolean = false)
}

/** Configuration business rules shared by Android and desktop; storage owns platform encryption. */
class AiSettings(private val storage: AiConfigurationStorage) {
    fun load(): AiConfiguration = storage.read() ?: AiConfiguration()

    fun display(): AiConfiguration = load().copy(apiKey = "")

    @Synchronized
    fun save(configuration: AiConfiguration) {
        var unreadable = false
        val previous = try {
            storage.read()
        } catch (failure: IllegalStateException) {
            if (configuration.apiKey.isEmpty()) throw failure
            unreadable = true
            null
        }
        if (configuration.apiKey.isEmpty() && previous != null && configuration.endpoint.isNotBlank()) {
            require(endpointAuthority(previous.endpoint) == endpointAuthority(configuration.endpoint)) {
                "服务地址已更改，请重新填写 API Key"
            }
        }
        val key = if (configuration.apiKey.isEmpty()) previous?.apiKey.orEmpty() else configuration.apiKey
        storage.write(validateForStorage(configuration.copy(apiKey = key)), replaceUnreadable = unreadable)
    }

    companion object {
        fun validate(configuration: AiConfiguration): AiConfiguration {
            require(configuration.consent) { "请先明确同意将所选日期的记录发送给该 AI 服务" }
            return validateCredentials(configuration)
        }

        fun endpointAuthority(endpoint: String): String = ChatCompletionClient.normalizeEndpoint(endpoint).authority

        private fun validateForStorage(configuration: AiConfiguration): AiConfiguration {
            require(configuration.consent || !configuration.automatic) { "未同意发送记录时不能启用自动日报" }
            if (!configuration.consent && configuration.endpoint.isBlank() && configuration.model.isBlank() && configuration.apiKey.isBlank()) {
                return AiConfiguration()
            }
            return validateCredentials(configuration)
        }

        fun validateCredentials(configuration: AiConfiguration): AiConfiguration {
            val endpoint = ChatCompletionClient.normalizeEndpoint(configuration.endpoint).toString()
            require(configuration.apiKey.isNotBlank()) { "请填写 API Key" }
            require(!configuration.apiKey.contains('\r') && !configuration.apiKey.contains('\n')) { "API Key 格式无效" }
            require(configuration.apiKey.length <= 16_384) { "API Key 过长" }
            val model = configuration.model.trim()
            require(model.isNotEmpty()) { "请填写模型名称" }
            require(model.length <= 256 && !model.contains('\r') && !model.contains('\n')) { "模型名称格式无效" }
            return configuration.copy(endpoint = endpoint, model = model)
        }
    }
}
