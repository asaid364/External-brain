package app.liferecord.desktop

import com.sun.jna.platform.win32.WinCrypt
import com.sun.jna.platform.win32.Crypt32Util
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.tls.HeldCertificate

interface SecretProtector {
    fun protect(cleartext: ByteArray): ByteArray
    fun unprotect(ciphertext: ByteArray): ByteArray
}

class WindowsDpapiProtector : SecretProtector {
    override fun protect(cleartext: ByteArray): ByteArray = dpapi("加密") {
        Crypt32Util.cryptProtectData(cleartext, null, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, "LifeRecord", null)
    }

    override fun unprotect(ciphertext: ByteArray): ByteArray = dpapi("解密") {
        Crypt32Util.cryptUnprotectData(ciphertext, null, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null)
    }

    private inline fun dpapi(operation: String, block: () -> ByteArray): ByteArray = try {
        block()
    } catch (failure: Throwable) {
        throw IllegalStateException("Windows 无法安全${operation}本机凭据", failure)
    }
}
/** DPAPI ciphertext is replaced atomically; a failed protect/write never damages the prior value. */
class AtomicSecretFile(
    private val path: Path,
    private val protector: SecretProtector = WindowsDpapiProtector(),
) {
    fun read(): ByteArray? {
        if (!Files.exists(path)) return null
        val encrypted = try { Files.readAllBytes(path) }
        catch (failure: Throwable) { throw IllegalStateException("无法读取本机安全配置", failure) }
        return protector.unprotect(encrypted)
    }

    fun write(cleartext: ByteArray) {
        val encrypted = protector.protect(cleartext)
        Files.createDirectories(path.toAbsolutePath().parent)
        val temporary = Files.createTempFile(path.toAbsolutePath().parent, path.fileName.toString(), ".tmp")
        try {
            Files.write(temporary, encrypted, StandardOpenOption.TRUNCATE_EXISTING)
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (failure: Throwable) {
            throw IllegalStateException("无法安全保存本机配置，原配置未被替换", failure)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

data class WindowsServerIdentity(
    val serverId: String,
    val stableHostName: String,
    val certificate: HeldCertificate,
    val fingerprintSha256: String,
)

data class StoredPair(
    val pairId: String,
    val token: String,
    val clientId: String,
    val deviceName: String,
    val spaceId: String,
    val peerDatabaseId: String?,
    val peerSendConsent: Boolean,
    val peerReceiveConsent: Boolean,
    val revoked: Boolean,
)

/** Certificate/private key and pairing credentials are one current-user DPAPI-protected document. */
class WindowsSecrets(
    root: Path,
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
    protector: SecretProtector = WindowsDpapiProtector(),
) {
    private val file = AtomicSecretFile(root.resolve("sync-credentials.dpapi"), protector)
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = true }
    private val lock = Any()

    fun identity(): WindowsServerIdentity = synchronized(lock) {
        val document = readDocument()
        document.identity?.let(::materialize) ?: createIdentity().also { created ->
            writeDocument(document.copy(identity = created))
        }.let(::materialize)
    }

    fun pair(): StoredPair? = synchronized(lock) { readDocument().pair?.takeUnless { it.revoked } }

    fun savePair(pair: StoredPair) = synchronized(lock) {
        val document = readDocument()
        writeDocument(document.copy(pair = pair))
    }

    fun revokePair(pairId: String) = synchronized(lock) {
        val document = readDocument()
        val pair = document.pair ?: return@synchronized
        if (MessageDigest.isEqual(pair.pairId.toByteArray(), pairId.toByteArray())) {
            writeDocument(document.copy(pair = pair.copy(revoked = true, token = "")))
        }
    }

    private fun createIdentity(): IdentityDocument {
        val serverId = ByteArray(10).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val host = "life-$serverId.local"
        val now = clock.millis()
        val certificate = HeldCertificate.Builder()
            .commonName(host)
            .addSubjectAlternativeName(host)
            .ecdsa256()
            .validityInterval(now - Duration.ofHours(24).toMillis(), now + Duration.ofDays(1095).toMillis())
            .build()
        return IdentityDocument(serverId, certificate.certificatePem(), certificate.privateKeyPkcs8Pem())
    }

    private fun materialize(value: IdentityDocument): WindowsServerIdentity {
        check(Regex("[a-z0-9-]{1,48}").matches(value.serverId)) {
            "同步服务器身份不是有效的稳定 DNS 名称；凭据未修改，必须人工重新配对"
        }
        val certificate = try { HeldCertificate.decode(value.certificatePem + value.privateKeyPem) }
        catch (failure: Throwable) { throw IllegalStateException("同步服务器证书无法安全读取，必须人工重新配对", failure) }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.certificate.encoded)
            .joinToString(":") { "%02X".format(it) }
        return WindowsServerIdentity(value.serverId, "life-${value.serverId}.local", certificate, fingerprint)
    }

    fun newBearerToken(): String = randomToken(32)
    fun newPairId(): String = UUID.randomUUID().toString()
    fun newSpaceId(): String = UUID.randomUUID().toString()

    private fun randomToken(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }

    private fun readDocument(): SecretsDocument {
        val bytes = file.read() ?: return SecretsDocument()
        return try { decode(String(bytes, StandardCharsets.UTF_8)) }
        catch (failure: Throwable) { throw IllegalStateException("本机同步凭据不可读；不会用明文或新凭据覆盖", failure) }
    }

    private fun writeDocument(document: SecretsDocument) = file.write(encode(document).toByteArray(StandardCharsets.UTF_8))

    private data class IdentityDocument(val serverId: String, val certificatePem: String, val privateKeyPem: String)
    private data class SecretsDocument(val identity: IdentityDocument? = null, val pair: StoredPair? = null)

    private fun encode(value: SecretsDocument): String = buildJsonObject {
        put("version", 1)
        value.identity?.let { identity -> put("identity", buildJsonObject {
            put("serverId", identity.serverId); put("certificatePem", identity.certificatePem); put("privateKeyPem", identity.privateKeyPem)
        }) }
        value.pair?.let { pair -> put("pair", buildJsonObject {
            put("pairId", pair.pairId); put("token", pair.token); put("clientId", pair.clientId); put("deviceName", pair.deviceName)
            put("spaceId", pair.spaceId); pair.peerDatabaseId?.let { put("peerDatabaseId", it) }
            put("peerSendConsent", pair.peerSendConsent); put("peerReceiveConsent", pair.peerReceiveConsent); put("revoked", pair.revoked)
        }) }
    }.toString()

    private fun decode(text: String): SecretsDocument {
        val root = json.parseToJsonElement(text) as? JsonObject ?: error("invalid secret document")
        require(root.getValue("version").jsonPrimitive.content == "1")
        val identity = (root["identity"] as? JsonObject)?.let {
            IdentityDocument(it.required("serverId"), it.required("certificatePem"), it.required("privateKeyPem"))
        }
        val pair = (root["pair"] as? JsonObject)?.let {
            StoredPair(
                pairId = it.required("pairId"), token = it.required("token"), clientId = it.required("clientId"),
                deviceName = it.required("deviceName"), spaceId = it.required("spaceId"),
                peerDatabaseId = it["peerDatabaseId"]?.jsonPrimitive?.contentOrNull,
                peerSendConsent = it["peerSendConsent"]?.jsonPrimitive?.boolean ?: false,
                peerReceiveConsent = it["peerReceiveConsent"]?.jsonPrimitive?.boolean ?: false,
                revoked = it["revoked"]?.jsonPrimitive?.boolean ?: false,
            )
        }
        return SecretsDocument(identity, pair)
    }

    private fun JsonObject.required(name: String): String = getValue(name).jsonPrimitive.content
}
