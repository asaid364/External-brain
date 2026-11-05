package app.liferecord.desktop

import app.liferecord.core.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.*

class GenerationCoordinatorTest {
    @Test fun expiredOrRestartedLeaseNeverAutomaticallyAuthorizesAnotherBillableRun() {
        val directory=Files.createTempDirectory("liferecord-generation-")
        val clock=Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"),ZoneOffset.UTC)
        try { WindowsDatabase(directory.resolve("owned.db")).use { database ->
            val store=RecordStore(database,clock)
            val session=store.beginNew()
            store.save(session,session.draft.copy(text="合成协调事实",local="2026-11-02 09:00",zone="UTC",offset="Z"),"create")
            val reports=ReportRepository(database)
            val hash=reports.input("2026-11-02").hash
            val first=GenerationCoordinator(database,reports,clock)
            val lease=first.claim("2026-11-02","run-one","phone",hash)
            assertThrows(IllegalStateException::class.java) { first.claim("2026-11-02","run-two","desktop",hash) }
            val later=GenerationCoordinator(database,reports,Clock.offset(clock,Duration.ofSeconds(181)))
            assertEquals("UNKNOWN",later.current("2026-11-02")!!.state)
            assertThrows(IllegalStateException::class.java) { later.claim("2026-11-02","run-two","desktop",hash) }
            val replacement=later.claim("2026-11-02","run-two","desktop",hash,true)
            assertNotEquals(lease.token,replacement.token)
            assertThrows(IllegalArgumentException::class.java) { later.finish("2026-11-02",lease.runId,lease.token) }
            assertEquals("FINISHED",later.finish("2026-11-02",replacement.runId,replacement.token).state)
        } } finally { directory.toFile().deleteRecursively() }
    }
}
