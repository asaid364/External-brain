package app.liferecord

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
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

/** The authenticated Windows peer. Secrets are encrypted with a sync-only AndroidKeyStore key. */
data class AndroidSyncPeer(
    val serverId: String,
    val displayName: String,
    val stableHost: String,
    val port: Int,
    val certificateSha256: String,
    val pairId: String,
    val bearerToken: String,
    val spaceId: String,
    val peerDatabaseId: String,
)

class AndroidSyncStorage(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, FILE_NAME))

    fun read(): AndroidSyncPeer? {
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
            throw IllegalStateException("同步配对凭据无法安全读取，请解除本机配对后重新核验")
        }
    }

    fun write(value: AndroidSyncPeer) {
        validate(value)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val encrypted = cipher.doFinal(encode(value).toByteArray(Charsets.UTF_8))
            var pending: java.io.FileOutputStream? = null
            try {
                val stream = file.startWrite()
                pending = stream
                DataOutputStream(stream).use { output ->
                    output.writeInt(VERSION)
                    output.writeInt(cipher.iv.size)
                    output.write(cipher.iv)
                    output.writeInt(encrypted.size)
                    output.write(encrypted)
                    output.flush()
                    file.finishWrite(stream)
                    pending = null
                }
            } finally {
                pending?.let(file::failWrite)
            }
        } catch (_: Exception) {
            throw IllegalStateException("同步配对凭据未能安全保存，原凭据未更改")
        }
    }

    /** Local unpairing revokes use of the token and removes only this app's sync key. */
    fun clear() {
        if (file.baseFile.exists() && !file.baseFile.delete()) {
            throw IllegalStateException("无法移除本机同步配对凭据")
        }
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        } catch (_: Exception) {
            throw IllegalStateException("无法移除本机同步配对密钥")
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

    private fun encode(value: AndroidSyncPeer) = buildJsonObject {
        put("serverId", value.serverId)
        put("displayName", value.displayName)
        put("stableHost", value.stableHost)
        put("port", value.port)
        put("certificateSha256", value.certificateSha256.lowercase())
        put("pairId", value.pairId)
        put("bearerToken", value.bearerToken)
        put("spaceId", value.spaceId)
        put("peerDatabaseId", value.peerDatabaseId)
    }.toString()

    private fun decode(encoded: String): AndroidSyncPeer {
        val value = JSON.parseToJsonElement(encoded) as? JsonObject ?: error("object required")
        require(value.keys == FIELDS)
        return AndroidSyncPeer(
            serverId = value.string("serverId"),
            displayName = value.string("displayName"),
            stableHost = value.string("stableHost"),
            port = value.number("port"),
            certificateSha256 = value.string("certificateSha256"),
            pairId = value.string("pairId"),
            bearerToken = value.string("bearerToken"),
            spaceId = value.string("spaceId"),
            peerDatabaseId = value.string("peerDatabaseId"),
        ).also(::validate)
    }

    private fun validate(value: AndroidSyncPeer) {
        require(value.serverId.isNotBlank() && value.serverId.all { it.isLetterOrDigit() || it == '-' })
        require(value.stableHost == "life-${value.serverId}.local")
        require(value.port in 1..65535)
        require(FINGERPRINT.matches(value.certificateSha256))
        require(value.pairId.isNotBlank() && value.bearerToken.isNotBlank())
        require(value.spaceId.isNotBlank() && value.peerDatabaseId.isNotBlank())
    }

    private fun JsonObject.string(key: String): String =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("missing $key")

    private fun JsonObject.number(key: String): Int {
        val primitive = get(key) as? JsonPrimitive ?: error("missing $key")
        require(!primitive.isString)
        return primitive.content.toInt()
    }

    private companion object {
        val JSON = Json { isLenient = false; ignoreUnknownKeys = false; explicitNulls = true }
        val FIELDS = setOf("serverId", "displayName", "stableHost", "port", "certificateSha256", "pairId", "bearerToken", "spaceId", "peerDatabaseId")
        val FINGERPRINT = Regex("[0-9a-fA-F]{64}")
        const val KEY_ALIAS = "life-record-sync-peer-v1"
        const val FILE_NAME = "sync-peer.enc"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION = 1
        const val MAX_FILE_BYTES = 64 * 1024
    }
}
