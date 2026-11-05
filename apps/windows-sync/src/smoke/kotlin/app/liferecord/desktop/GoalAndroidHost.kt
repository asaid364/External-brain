package app.liferecord.desktop

import app.liferecord.core.*
import java.net.Inet4Address
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Operator-controlled counterpart of GoalSyncIntegrationTest; never starts an Android process.
 * Arguments: <freshly selected private interface ID|IPv4> <Android Network handle> [--file-control].
 * All state is confined to a new UUID directory under the ignored desktop build directory.
 * The generated runner.properties is sensitive: never print its contents. Invoke the generated
 * run-android.ps1, which passes actual Gradle -P arguments without echoing their values.
 *
 * Commands (or empty stdin lines in this order): approve, consent, confirm-second, verify-reopen.
 * File control consumes appended complete lines from commands.txt; use explicit command names.
 * inspect is read-only; stop closes the server without claiming acceptance. No step auto-approves.
 * renew explicitly opens a new five-minute pairing window and rewrites runner.properties;
 * it is permitted only before approval. Run the script again only after the old test has ended.
 * verify-reopen is valid only AFTER Android reports PASS_HTTPS_ACK_REOPEN_SIMULATED_AI; it stops
 * serving, reopens the host DB, verifies heads/cursors/progress, and exits. Retained UUID SQLite
 * and DPAPI files permit inspection; the plaintext runner file is deleted on normal shutdown.
 * This driver exercises repository actions, not Windows UI, physical LAN or an external AI.
 */
fun main(args: Array<String>) {
    require(System.getProperty("os.name").startsWith("Windows")) { "Current-user Windows DPAPI required" }
    require(args.size in 2..3 && (args.size == 2 || args[2] == "--file-control")) {
        "Expected explicit private interface, Android Network handle, optional --file-control"
    }
    val handle = args[1].toLong().also { require(it > 0) { "Select a current Android LAN Network handle" } }
    val root = Files.createTempDirectory(Path.of("build", "smoke").also { Files.createDirectories(it) }, "goal-android-${UUID.randomUUID()}-").toAbsolutePath()
    val runnerFile = root.resolve("runner.properties")
    val runnerScript = root.resolve("run-android.ps1")
    val commandsFile = root.resolve("commands.txt")
    val dbPath = root.resolve("host.db")
    val clock = Clock.fixed(Instant.parse("2027-01-22T04:00:00Z"), ZoneOffset.UTC)
    val secrets = WindowsSecrets(root.resolve("secrets"))
    val identity = secrets.identity()
    var database: WindowsDatabase? = null
    var server: WindowsSyncServer? = null
    var stage = 0
    var expectedDevice: String? = null
    var goalId: String? = null
    var confirmedHeads: Map<String, Set<String>>? = null
    try {
        val selected = WindowsDiscovery(identity).use { discovery ->
            val available = discovery.availableInterfaces().filter { it.address is Inet4Address }
            available.forEach { println("GOAL_ANDROID_INTERFACE id=${it.id} address=${it.address.hostAddress}") }
            available.singleOrNull { it.id == args[0] || it.address.hostAddress == args[0] }
                ?: error("Selected private IPv4 interface unavailable or ambiguous; no fallback")
        }
        val db = WindowsDatabase(dbPath)
        database = db
        val sync = SyncRepository(db)
        val exchange = SyncExchange(db)
        val goals = GoalRepository(db, clock)
        val host = WindowsSyncServer(exchange, secrets, goalCoordinator = GoalReviewCoordinator(db, goals, clock))
        server = host
        val port = host.start(selected, 0)
        fun renewPairing() {
            check(stage == 0) { "Cannot renew after approval" }
            val pairing = host.startPairing()
            val runner = linkedMapOf(
                "class" to "app.liferecord.GoalSyncIntegrationTest",
                "goalSmokeHost" to selected.address.hostAddress,
                "goalSmokePort" to port.toString(),
                "goalSmokeServerId" to identity.serverId,
                "goalSmokePin" to identity.fingerprintSha256.replace(":", ""),
                "goalSmokeCode" to pairing.code,
                "goalSmokeNetworkHandle" to handle.toString(),
                "goalSmokeConsent" to "ISOLATED_SYNTHETIC_DATA_ONLY",
            )
            require(runner.values.all { it.matches(Regex("[A-Za-z0-9_.:-]+")) })
            Files.writeString(runnerFile, runner.entries.joinToString("\n", postfix = "\n") { (key, value) ->
                "android.testInstrumentationRunnerArguments.$key=$value"
            })
            println("GOAL_ANDROID_PAIRING_RENEWED expires=${pairing.expiresAt}; runner file rewritten; no approval")
        }
        renewPairing()
        Files.writeString(runnerScript, """
            param(
                [Parameter(Mandatory=${'$'}true)][string]${'$'}Wrapper,
                [Parameter(Mandatory=${'$'}true)][string]${'$'}AndroidProject,
                [Parameter(Mandatory=${'$'}true)][string]${'$'}Serial,
                [string]${'$'}JavaHome = 'E:/JavaJdk17'
            )
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}properties = Get-Content -Raw -LiteralPath (Join-Path ${'$'}PSScriptRoot 'runner.properties') | ConvertFrom-StringData
            if (${'$'}properties['android.testInstrumentationRunnerArguments.class'] -ne 'app.liferecord.GoalSyncIntegrationTest') { throw 'Unexpected runner class' }
            ${'$'}arguments = @('-p', ${'$'}AndroidProject, "-Dorg.gradle.java.home=${'$'}JavaHome", ':app:connectedSyncSmokeAndroidTest')
            foreach (${'$'}entry in ${'$'}properties.GetEnumerator()) {
                if (${'$'}entry.Key -notmatch '^android\.testInstrumentationRunnerArguments\.(class|goalSmokeHost|goalSmokePort|goalSmokeServerId|goalSmokePin|goalSmokeCode|goalSmokeNetworkHandle|goalSmokeConsent)${'$'}') { throw 'Unexpected runner property' }
                ${'$'}arguments += "-P${'$'}(${'$'}entry.Key)=${'$'}(${'$'}entry.Value)"
            }
            ${'$'}previousSerial = ${'$'}env:ANDROID_SERIAL
            try {
                ${'$'}env:ANDROID_SERIAL = ${'$'}Serial
                & ${'$'}Wrapper @arguments
                if (${'$'}LASTEXITCODE -ne 0) { throw "Android Gradle failed with exit code ${'$'}LASTEXITCODE" }
            } finally {
                ${'$'}env:ANDROID_SERIAL = ${'$'}previousSerial
            }
        """.trimIndent() + "\n")
        Files.writeString(commandsFile, "")
        println("GOAL_ANDROID_HOST_READY root=$root address=${selected.address.hostAddress} port=$port host=${identity.stableHostName} pin=${identity.fingerprintSha256}")
        println("GOAL_ANDROID_RUNNER_SCRIPT=$runnerScript")
        println("GOAL_ANDROID_COMMAND_FILE=$commandsFile")
        println("Operator commands: inspect / renew / approve / consent / confirm-second / verify-reopen / stop; no automatic approvals.")
        println("Consent scope: isolated synthetic records, drafts, reports, goals, milestone declarations, reviews and mirrored deletions in BOTH directions. No production data.")
        val stdin = System.`in`.bufferedReader()
        var consumed = 0
        fun command(): String? {
            if (args.size == 2) return stdin.readLine()
            while (true) {
                val lines = Files.readString(commandsFile)
                val complete = lines.substringBeforeLast('\n', "").lines()
                if (lines.contains('\n') && consumed < complete.size) return complete[consumed++].trimEnd('\r')
                Thread.sleep(250)
            }
        }
        fun detail(): GoalDetail {
            val device = requireNotNull(expectedDevice) { "Approve the isolated device first" }
            val summary = goals.list(true).single { it.spec?.title == device }
            val spec = requireNotNull(summary.spec)
            check(spec.milestones.size == 2 && summary.total == 2 && !summary.conflicted)
            check(goalId == null || goalId == spec.id)
            goalId = spec.id
            return goals.detail(spec.id).also { check(it.conflicts.isEmpty()) }
        }
        while (true) {
            val line = command() ?: break
            val operation = line.trim().ifEmpty {
                listOf("approve", "consent", "confirm-second", "verify-reopen").getOrElse(stage) { "inspect" }
            }
            try {
                when (operation) {
                    "renew" -> renewPairing()
                    "inspect" -> {
                        val pending = host.state().pendingRequest
                        val name = pending?.deviceName?.takeIf { it.matches(Regex("SYNTH-GOAL-[0-9a-f-]{36}")) }
                        println("GOAL_ANDROID_INSPECT stage=$stage pendingSyntheticDevice=${name ?: "none"} localConsent=${host.state().localSendConsent && host.state().localReceiveConsent}")
                        if (stage >= 2) {
                            val current = detail()
                            println("GOAL_ANDROID_PROGRESS goal=${goalId} completed=${current.summary.completed}/${current.summary.total} percent=${current.summary.percent} simulatedReviews=${current.reviews.size}")
                        }
                    }
                    "approve" -> {
                        check(stage == 0)
                        val pending = requireNotNull(host.state().pendingRequest)
                        check(pending.deviceName.matches(Regex("SYNTH-GOAL-[0-9a-f-]{36}")))
                        UUID.fromString(pending.deviceName.removePrefix("SYNTH-GOAL-"))
                        host.approvePairing(pending.requestId)
                        expectedDevice = pending.deviceName
                        stage = 1
                        println("GOAL_ANDROID_MANUAL_APPROVED device=$expectedDevice; next command consent explicitly accepts the expanded synthetic scope")
                    }
                    "consent" -> {
                        check(stage == 1)
                        check(secrets.pair()?.deviceName == expectedDevice)
                        host.setLocalConsent(true, true)
                        stage = 2
                        println("GOAL_ANDROID_MANUAL_CONSENT bothDirections=true; wait for Android WAIT_HOST_SECOND_CONFIRMATION, then inspect")
                    }
                    "confirm-second" -> {
                        check(stage == 2)
                        val current = detail()
                        val spec = requireNotNull(current.summary.spec)
                        check(current.summary.completed == 1 && current.summary.percent == 50)
                        check(current.reviews.size == 1 && current.reviews.single().assessment.text.startsWith("模拟AI"))
                        check(current.confirmations.single { it.value.milestoneId == spec.milestones[0].id }.value.confirmed)
                        check(current.confirmations.none { it.value.milestoneId == spec.milestones[1].id })
                        val second = spec.milestones[1]
                        goals.confirmMilestone(spec.id, second.id, GoalCodec.criterionHash(second.criterion), true,
                            "合成用户声明：操作员在隔离 Windows host 明确确认第二项", emptyList(),
                            sync.headIds(GoalCodec.CONFIRMATION_KIND, "${spec.id}/${second.id}"), UUID.randomUUID().toString())
                        check(goals.detail(spec.id).summary.percent == 100)
                        confirmedHeads = mapOf(GoalCodec.GOAL_KIND to sync.headIds(GoalCodec.GOAL_KIND, spec.id),
                            GoalCodec.REVIEW_KIND to sync.headIds(GoalCodec.REVIEW_KIND, current.reviews.single().key.encoded))
                        stage = 3
                        println("GOAL_ANDROID_HOST_CONFIRMED_SECOND goal=${spec.id} progress=2/2 percent=100; wait for Android PASS before verify-reopen")
                    }
                    "verify-reopen" -> {
                        check(stage == 3)
                        val current = detail()
                        val spec = requireNotNull(current.summary.spec)
                        check(current.summary.completed == 1 && current.summary.percent == 50)
                        check(!current.confirmations.single { it.value.milestoneId == spec.milestones[0].id }.value.confirmed)
                        check(current.confirmations.single { it.value.milestoneId == spec.milestones[1].id }.value.confirmed)
                        val pairId = requireNotNull(secrets.pair()).pairId
                        val before = exchange.status(pairId)
                        check(before.ackedLocalSeq > 0 && before.receivedRemoteSeq > 0)
                        val reviewKey = current.reviews.single().key.encoded
                        check(sync.headIds(GoalCodec.GOAL_KIND, spec.id) == requireNotNull(confirmedHeads)[GoalCodec.GOAL_KIND])
                        check(sync.headIds(GoalCodec.REVIEW_KIND, reviewKey) == requireNotNull(confirmedHeads)[GoalCodec.REVIEW_KIND])
                        val confirmationHeads = spec.milestones.associate { it.id to sync.headIds(GoalCodec.CONFIRMATION_KIND, "${spec.id}/${it.id}") }
                        host.close(); server = null
                        db.close(); database = null
                        WindowsDatabase(dbPath).use { reopened ->
                            val fresh = GoalRepository(reopened, clock).detail(spec.id)
                            val freshSync = SyncRepository(reopened)
                            check(fresh.summary.percent == 50 && fresh.confirmations == current.confirmations && fresh.reviews == current.reviews)
                            check(freshSync.headIds(GoalCodec.GOAL_KIND, spec.id) == requireNotNull(confirmedHeads)[GoalCodec.GOAL_KIND])
                            check(freshSync.headIds(GoalCodec.REVIEW_KIND, reviewKey) == requireNotNull(confirmedHeads)[GoalCodec.REVIEW_KIND])
                            confirmationHeads.forEach { (id, heads) -> check(freshSync.headIds(GoalCodec.CONFIRMATION_KIND, "${spec.id}/$id") == heads) }
                            val after = SyncExchange(reopened).status(pairId)
                            check(after.ackedLocalSeq == before.ackedLocalSeq && after.receivedRemoteSeq == before.receivedRemoteSeq)
                        }
                        check(WindowsSecrets(root.resolve("secrets")).identity().fingerprintSha256 == identity.fingerprintSha256)
                        println("GOAL_ANDROID_HOST_PASS goal=${spec.id} progress=50->100->50 headsAndCursorsReopened=true currentUserDPAPI=true; host repository evidence only; require separate Android PASS")
                        break
                    }
                    "stop" -> break
                    else -> println("GOAL_ANDROID_COMMAND_REJECTED unknown command; no action")
                }
            } catch (failure: Exception) {
                // Do not expose arbitrary library exceptions containing request bodies or credentials.
                println("GOAL_ANDROID_COMMAND_REJECTED stage=$stage type=${failure.javaClass.simpleName}; prerequisites unmet or operation failed; no acceptance claimed")
                if (database == null || server == null) throw IllegalStateException("Host closed before successful verification")
            }
        }
    } finally {
        server?.close()
        database?.close()
        Files.deleteIfExists(runnerFile)
    }
}
