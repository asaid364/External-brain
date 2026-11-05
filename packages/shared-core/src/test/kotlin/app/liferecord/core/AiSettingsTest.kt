package app.liferecord.core

import org.junit.Assert.*
import org.junit.Test

class AiSettingsTest {
    private val original = AiConfiguration("https://synthetic.invalid/v1", "synthetic-key", "fixture", true)
    private class Storage(var value: AiConfiguration?) : AiConfigurationStorage {
        var unreadable = false
        var replace = false
        override fun read(): AiConfiguration? { check(!unreadable) { "synthetic unreadable ciphertext" }; return value }
        override fun write(value: AiConfiguration, replaceUnreadable: Boolean) {
            this.value = value
            replace = replaceUnreadable
            unreadable = false
        }
    }
    @Test fun blankReplacementRetainsKeyOnlyForSameAuthority() {
        val storage = Storage(original)
        val settings = AiSettings(storage)
        settings.save(original.copy(apiKey = "", model = "changed-model"))
        assertEquals("synthetic-key", storage.value!!.apiKey)
        assertEquals("changed-model", storage.value!!.model)
        assertThrows(IllegalArgumentException::class.java) {
            settings.save(original.copy(endpoint = "https://different.invalid/v1", apiKey = ""))
        }
        assertEquals("synthetic-key", storage.value!!.apiKey)
        assertEquals("https://synthetic.invalid/v1/chat/completions", storage.value!!.endpoint)
        assertEquals("", settings.display().apiKey)
    }
    @Test fun unreadableConfigurationNeedsValidatedExplicitReplacement() {
        val storage = Storage(original).apply { unreadable = true }
        val settings = AiSettings(storage)
        assertThrows(IllegalStateException::class.java) { settings.save(AiConfiguration()) }
        assertTrue(storage.unreadable)
        assertThrows(IllegalArgumentException::class.java) { settings.save(original.copy(endpoint = "http://synthetic.invalid", apiKey = "replacement")) }
        assertTrue(storage.unreadable)
        assertEquals(original, storage.value)
        settings.save(original.copy(apiKey = "replacement"))
        assertTrue(storage.replace)
        assertEquals("replacement", storage.value!!.apiKey)
    }
}
