package app.liferecord

import android.net.ConnectivityManager
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.liferecord.core.*
import java.net.InetAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in device HTTPS acceptance, not a host/Robolectric substitute or a UI test.
 * Required instrumentation arguments (never commit their real values):
 * goalSmokeHost=<private numeric IPv4>, goalSmokePort=<port>,
 * goalSmokeServerId=<id from life-ID.local>, goalSmokePin=<full 64-hex SHA256>,
 * goalSmokeCode=<fresh one-time pairing code>, goalSmokeNetworkHandle=<selected LAN Network handle>,
 * goalSmokeConsent=ISOLATED_SYNTHETIC_DATA_ONLY.
 *
 * Start a Windows smoke instance with a new UUID data directory on the explicitly selected
 * private interface. Verify its hostname and full pin on screen, then supply those values.
 * Select an authorized device and ONLY connectedSyncSmokeAndroidTest with this class argument;
 * never connectedDebugAndroidTest. Missing arguments explicitly skip the host test suite.
 * The consent argument is the operator's Android-side permission to merge ALL synthetic data
 * in both directions, including records, drafts, reports, goals, confirmations, reviews and
 * mirrored deletions. The host must also be isolated; no production peer/storage is used here.
 *
 * During WAIT_HOST_APPROVAL approve the device named SYNTH-GOAL-<fixture UUID> on Windows.
 * During WAIT_HOST_CONSENT confirm the expanded sync scope on Windows (no counts prerequisite).
 * During WAIT_HOST_SECOND_CONFIRMATION open that fixture's goal on Windows, check 1/2 = 50%
 * and its explicitly simulated review, then confirm ONLY the SECOND milestone as a synthetic
 * user declaration. The test pulls that independent host edit, then retracts the first
 * milestone on Android and pushes the resulting 1/2 = 50% back. Do not edit the goal definition.
 * Status bundles contain only phase, fixture UUID and request count; never pins/codes/tokens.
 * Both operator waits are bounded. Failure is not silently converted to a skipped test.
 */
@RunWith(AndroidJUnit4::class)
class GoalSyncIntegrationTest {
    private val clock = Clock.fixed(Instant.parse("2027-01-22T04:00:00Z"), ZoneOffset.UTC)
    private fun uuid() = UUID.randomUUID().toString()

    @Test fun pinnedDeviceGoalReviewTransfersAckAndReopens() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val required = listOf("goalSmokeHost", "goalSmokePort", "goalSmokeServerId", "goalSmokePin",
            "goalSmokeCode", "goalSmokeNetworkHandle", "goalSmokeConsent")
        assumeTrue("Opt-in Android HTTPS scenario: provide every goalSmoke runner argument; no host fallback",
            required.all { !args.getString(it).isNullOrBlank() })
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".syncsmoke")) { "Only the isolated .syncsmoke target is authorized" }
        check(args.getString("goalSmokeConsent") == "ISOLATED_SYNTHETIC_DATA_ONLY") {
            "Explicit permission for isolated synthetic bilateral merge is required"
        }
        val fixtureId = uuid()
        val name = "goal-sync-$fixtureId.db"
        fun phase(value: String, requests: Int = 0) {
            instrumentation.sendStatus(2, Bundle().apply {
                putString("goalSmokePhase", value)
                putString("goalSmokeFixture", fixtureId)
                putInt("goalSmokeSimulatedRequests", requests)
            })
        }
        // Numeric IPv4 only: stable TLS hostname is derived independently from verified serverId.
        val host = requireNotNull(args.getString("goalSmokeHost"))
        val octets = host.split('.')
        require(octets.size == 4 && octets.all { it.toIntOrNull() in 0..255 }) { "Use a private numeric IPv4" }
        val address = InetAddress.getByAddress(octets.map { it.toInt().toByte() }.toByteArray())
        val serverId = requireNotNull(args.getString("goalSmokeServerId"))
        require(serverId.matches(Regex("[a-z0-9][a-z0-9-]{0,62}"))) { "Invalid stable server identity" }
        val selectedHandle = requireNotNull(args.getString("goalSmokeNetworkHandle")).toLong()
        val network = AndroidLanNetworks.available(context).singleOrNull { it.networkHandle == selectedHandle }
            ?: error("The explicitly selected Wi-Fi/Ethernet Network is no longer available")
        AndroidLanNetworks.requireSelected(context, network)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        check(connectivity.getLinkProperties(network)?.routes?.any { it.matches(address) } == true) {
            "Selected Network has no route to the verified private host"
        }
        val route = AndroidPeerRoute(serverId, "life-$serverId.local", address,
            requireNotNull(args.getString("goalSmokePort")).toInt(),
            requireNotNull(args.getString("goalSmokePin")), network)
        // AndroidSyncClient enforces this route's socketFactory, exact-host DNS, pin and hostname.
        val client = AndroidSyncClient(context)
        var database = AndroidDatabase(context, name)
        var engine: GoalAnalysisEngine? = null
        var activeKey: GoalReviewKey? = null
        try {
            var repository = SyncRepository(database)
            var exchange = SyncExchange(database)
            val request = PairingRequest(clientId = repository.identity().actorEpoch,
                deviceName = "SYNTH-GOAL-$fixtureId", code = requireNotNull(args.getString("goalSmokeCode")))
            phase("WAIT_HOST_APPROVAL")
            val peer = withTimeout(180_000) {
                var approved: AndroidSyncPeer? = null
                while (approved == null) {
                    when (val result = client.pair(route, request)) {
                        is PairingResult.Pending -> delay(2_000)
                        is PairingResult.Approved -> approved = result.peer
                        is PairingResult.Rejected -> error("Host rejected isolated pairing")
                        is PairingResult.TransportError -> error("Pinned Android pairing transport failed")
                    }
                }
                requireNotNull(approved)
            }
            exchange.bindSpace(peer.spaceId)
            val privateStatus = client.status(route, peer, exchange, repository)
            assertNull(privateStatus.counts)
            assertNull(privateStatus.entityCount)
            assertNull(privateStatus.purgeCount)
            assertNull(client.remoteCounts)
            // This runner's explicit synthetic-data permission supplies Android-side consent.
            exchange.setConsent(peer.pairId, true, true)
            phase("WAIT_HOST_CONSENT")
            withTimeout(180_000) {
                while (true) {
                    val status = client.status(route, peer, exchange, repository)
                    if (status.sendConsent && status.receiveConsent) break
                    assertNull(status.counts)
                    delay(2_000)
                }
            }
            var goals = GoalRepository(database, clock)
            val opened = goals.openEditor()
            val first = GoalMilestone(uuid(), "交付可运行原型")
            val second = GoalMilestone(uuid(), "用独立数据验证结果")
            val goal = GoalSpec(opened.goalId, "SYNTH-GOAL-$fixtureId", "合成：交付并独立验证小工具",
                "2027-01-01", "2027-01-21", "UTC", 7, listOf(first, second), false)
            goals.save(opened, goal, uuid())
            val records = RecordStore(database, clock)
            fun add(text: String, local: String): String {
                val session = records.beginNew()
                return records.save(session, session.draft.copy(text = text, local = local, zone = "UTC", offset = "Z"), uuid())
            }
            val source = add("合成：可运行原型已交付", "2027-01-15 09:00")
            add("合成：独立验证被测试环境阻塞", "2027-01-17 09:00")
            val key = GoalReviewKey(goal.id, "2027-01-15", "2027-01-21")
            activeKey = key
            val input = goals.snapshot(key)
            val firstRef = input.evidence.single { it.dependency.ref.entityKey == source }.dependency.ref
            val blockedRef = input.evidence.single { it.dependency.ref.kind == GoalEvidenceKind.RECORD &&
                it.dependency.ref.entityKey != source }.dependency.ref
            val result = GeneratedGoalReview(GoalDirection.ADVANCING,
                GoalEvidenceClaim("模拟AI：原型交付推进了目标，独立验证尚有阻碍", listOf(firstRef.id, blockedRef.id)),
                listOf(GoalEvidenceClaim("模拟AI：测试环境阻塞独立验证", listOf(blockedRef.id))),
                listOf(GoalEvidenceClaim("模拟AI：可运行原型已交付", listOf(firstRef.id))), emptyList(),
                listOf(GoalEvidenceClaim("模拟AI：已有正式交付事实支持这一步进展", listOf(firstRef.id))),
                listOf(GoalMilestoneSuggestion(first.id, "模拟AI：核对原型后可由用户确认", listOf(firstRef.id))),
                listOf(GoalEvidenceClaim("模拟AI参考建议：可先核对测试环境阻碍", listOf(blockedRef.id))),
                listOf("其余日期缺少记录，行动未知；现有记录未说明独立验证成果"))
            val sends = AtomicInteger()
            val transport = ChatTransport {
                sends.incrementAndGet()
                ChatResponse(200, emptyMap(), buildJsonObject {
                    put("choices", buildJsonArray { add(buildJsonObject {
                        put("finish_reason", "stop")
                        put("message", buildJsonObject { put("content", GoalCodec.encodeResult(result).toString()) })
                    }) })
                }.toString())
            }
            val config = AiConfiguration("https://synthetic.invalid/v1", "synthetic-not-a-real-key", "simulated-goal-ai", false, false)
            goals.grantAnalysis(goal.id, GoalCodec.specHash(goal), config.endpoint, config.model, true, false)
            val ownedEngine = GoalAnalysisEngine(goals, { config }, GoalAnalysisClient(clock, transport), clock)
            engine = ownedEngine
            ownedEngine.request(key)
            withTimeout(30_000) { ownedEngine.awaitCompletion(key) }
            engine = null // Completion joined; do not cancel a successful run during cleanup/reopen.
            assertEquals(1, sends.get())
            assertEquals(GoalRunState.SUCCEEDED, goals.run(key)?.state)
            assertEquals(0, goals.detail(goal.id).summary.completed)
            assertEquals(1, goals.detail(goal.id).reviews.size)
            goals.confirmMilestone(goal.id, first.id, GoalCodec.criterionHash(first.criterion), true,
                "合成用户声明：已核对可运行原型", listOf(firstRef), emptySet(), uuid())
            assertEquals(50, goals.detail(goal.id).summary.percent)

            fun round(): SyncRoundResult.Complete {
                AndroidLanNetworks.requireSelected(context, network)
                val completed = client.exchangeOnce(route, peer, exchange, repository)
                check(completed is SyncRoundResult.Complete) {
                    when (completed) {
                        is SyncRoundResult.Stopped -> "Pinned Android HTTPS stopped: ${completed.code}: ${completed.message}"
                        is SyncRoundResult.Retryable -> "Pinned Android HTTPS retry required: ${completed.message}"
                        else -> "Pinned Android HTTPS round did not durably complete"
                    }
                }
                val local = exchange.status(peer.pairId)
                val remote = client.status(route, peer, exchange, repository)
                assertEquals(completed.pushedThrough, local.ackedLocalSeq)
                assertEquals(completed.pulledThrough, local.receivedRemoteSeq)
                assertTrue(remote.receivedRemoteSeq >= completed.pushedThrough)
                assertTrue(remote.ackedLocalSeq >= completed.pulledThrough)
                return completed
            }
            round()
            val originalReviewHeads = repository.headIds(GoalCodec.REVIEW_KIND, key.encoded)
            val originalGoalHeads = repository.headIds(GoalCodec.GOAL_KIND, goal.id)
            assertEquals(1, originalReviewHeads.size)
            phase("WAIT_HOST_SECOND_CONFIRMATION", sends.get())
            withTimeout(180_000) {
                while (goals.detail(goal.id).summary.completed != 2) {
                    delay(2_000)
                    round()
                }
            }
            val returned = goals.detail(goal.id)
            assertEquals(100, returned.summary.percent)
            assertTrue(returned.conflicts.isEmpty())
            assertTrue(returned.confirmations.single { it.value.milestoneId == second.id }.value.confirmed)
            goals.confirmMilestone(goal.id, first.id, GoalCodec.criterionHash(first.criterion), false,
                "合成用户声明：撤回第一项", emptyList(),
                repository.headIds(GoalCodec.CONFIRMATION_KIND, "${goal.id}/${first.id}"), uuid())
            val last = round()
            assertEquals(50, goals.detail(goal.id).summary.percent)
            val confirmationHeads = listOf(first, second).associate { milestone ->
                milestone.id to repository.headIds(GoalCodec.CONFIRMATION_KIND, "${goal.id}/${milestone.id}")
            }
            // No runtime, WorkManager, credential preferences or production database is touched.
            database.close()
            database = AndroidDatabase(context, name)
            repository = SyncRepository(database)
            exchange = SyncExchange(database)
            goals = GoalRepository(database, clock)
            assertEquals(last.pushedThrough, exchange.status(peer.pairId).ackedLocalSeq)
            assertEquals(last.pulledThrough, exchange.status(peer.pairId).receivedRemoteSeq)
            assertEquals(originalGoalHeads, repository.headIds(GoalCodec.GOAL_KIND, goal.id))
            assertEquals(originalReviewHeads, repository.headIds(GoalCodec.REVIEW_KIND, key.encoded))
            confirmationHeads.forEach { (id, heads) ->
                assertEquals(heads, repository.headIds(GoalCodec.CONFIRMATION_KIND, "${goal.id}/$id"))
            }
            assertEquals(50, goals.detail(goal.id).summary.percent)
            assertEquals(result.assessment, goals.detail(goal.id).reviews.single().assessment)
            round() // Incremental/no-change round after reopen must not multiply heads or invoke AI.
            assertEquals(originalReviewHeads, repository.headIds(GoalCodec.REVIEW_KIND, key.encoded))
            assertEquals(1, goals.list(true).count { it.spec?.id == goal.id })
            assertEquals(1, sends.get())
            phase("PASS_HTTPS_ACK_REOPEN_SIMULATED_AI", sends.get())
        } finally {
            // Join any still-running owned analysis before its isolated SQLite handle is removed.
            activeKey?.let { engine?.cancel(it) }
            database.close()
            context.deleteDatabase(name)
        }
    }
}
