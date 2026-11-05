package app.liferecord

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.liferecord.core.AiConfiguration
import app.liferecord.core.AiConfigurationStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Preserves the v1 AtomicFile/AES-GCM/AndroidKeyStore format and alias. */
class AndroidAiStorage(context: Context) : AiConfigurationStorage {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "ai-configuration.enc"))

    override fun read(): AiConfiguration? {
        if (!file.baseFile.exists()) return null
        return try {
            DataInputStream(file.openRead()).use { input ->
                require(input.readInt() == VERSION)
                val ivSize = input.readInt()
                require(ivSize in 12..32)
                val iv = ByteArray(ivSize).also(input::readFully)
                val cipherSize = input.readInt()
                require(cipherSize in 17..MAX_FILE_BYTES)
                val encrypted = ByteArray(cipherSize).also(input::readFully)
                require(input.read() == -1)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
                decode(cipher.doFinal(encrypted).toString(Charsets.UTF_8))
            }
        } catch (_: Exception) {
            throw IllegalStateException("AI 配置无法安全读取，请重新填写 API Key")
        }
    }

    override fun write(value: AiConfiguration, replaceUnreadable: Boolean) {
        try {
            if (replaceUnreadable) discardInvalidKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val encrypted = cipher.doFinal(encode(value).toByteArray(Charsets.UTF_8))
            var pending: java.io.FileOutputStream? = null
            try {
                val stream = file.startWrite()
                pending = stream
                val data = DataOutputStream(stream)
                data.writeInt(VERSION)
                data.writeInt(cipher.iv.size)
                data.write(cipher.iv)
                data.writeInt(encrypted.size)
                data.write(encrypted)
                data.flush()
                file.finishWrite(stream)
                pending = null
            } finally {
                pending?.let(file::failWrite)
            }
        } catch (_: Exception) {
            throw IllegalStateException("AI 配置未能安全保存，原配置未更改")
        }
    }

    private fun discardInvalidKey() {
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        } catch (_: Exception) {
            throw IllegalStateException("AI 配置密钥无法重建，请稍后重试")
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encode(value: AiConfiguration) = buildJsonObject {
        put("endpoint", value.endpoint)
        put("apiKey", value.apiKey)
        put("model", value.model)
        put("consent", value.consent)
        put("automatic", value.automatic)
    }.toString()

    private fun decode(value: String): AiConfiguration {
        val json = JSON.parseToJsonElement(value) as? JsonObject ?: throw IllegalArgumentException("object required")
        require(json.keys == FIELDS)
        return AiConfiguration(
            endpoint = json.requiredString("endpoint"),
            apiKey = json.requiredString("apiKey"),
            model = json.requiredString("model"),
            consent = json.requiredBoolean("consent"),
            automatic = json.requiredBoolean("automatic")
        )
    }

    private fun JsonObject.requiredString(key: String): String {
        val value = get(key) as? JsonPrimitive ?: throw IllegalArgumentException("missing $key")
        require(value.isString)
        return value.content
    }

    private fun JsonObject.requiredBoolean(key: String): Boolean {
        val value = get(key) as? JsonPrimitive ?: throw IllegalArgumentException("missing $key")
        require(!value.isString)
        return value.boolean
    }

    private companion object {
        val JSON = Json { isLenient = false; ignoreUnknownKeys = false; explicitNulls = true }
        val FIELDS = setOf("endpoint", "apiKey", "model", "consent", "automatic")
        const val KEY_ALIAS = "life-record-ai-configuration-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION = 1
        const val MAX_FILE_BYTES = 64 * 1024
    }
}
