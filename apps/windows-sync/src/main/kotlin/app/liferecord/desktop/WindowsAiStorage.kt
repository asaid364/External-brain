package app.liferecord.desktop

import app.liferecord.core.AiConfiguration
import app.liferecord.core.AiConfigurationStorage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Independent Windows AI configuration. It is never read from or exported to a paired device. */
class WindowsAiStorage(
    private val path: Path,
    protector: SecretProtector = WindowsDpapiProtector(),
) : AiConfigurationStorage {
    private val file = AtomicSecretFile(path, protector)
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = true }

    constructor(dataDirectory: Path) : this(dataDirectory.resolve("ai-configuration.dpapi"), WindowsDpapiProtector())

    @Synchronized
    override fun read(): AiConfiguration? {
        val cleartext = file.read() ?: return null
        return try {
            val root = json.parseToJsonElement(String(cleartext, StandardCharsets.UTF_8)) as JsonObject
            require(root.getValue("version").jsonPrimitive.content == "1")
            AiConfiguration(
                endpoint = root.required("endpoint"),
                apiKey = root.required("apiKey"),
                model = root.required("model"),
                consent = root.getValue("consent").jsonPrimitive.boolean,
                automatic = root.getValue("automatic").jsonPrimitive.boolean,
            )
        } catch (failure: Throwable) {
            throw IllegalStateException("AI 安全配置不可读；请重新填写 API Key 后再替换", failure)
        }
    }

    @Synchronized
    override fun write(value: AiConfiguration, replaceUnreadable: Boolean) {
        if (Files.exists(path) && !replaceUnreadable) read()
        val encoded = buildJsonObject {
            put("version", 1)
            put("endpoint", value.endpoint)
            put("apiKey", value.apiKey)
            put("model", value.model)
            put("consent", value.consent)
            put("automatic", value.automatic)
        }.toString().toByteArray(StandardCharsets.UTF_8)
        file.write(encoded)
    }

    private fun JsonObject.required(name: String): String = getValue(name).jsonPrimitive.content
}
