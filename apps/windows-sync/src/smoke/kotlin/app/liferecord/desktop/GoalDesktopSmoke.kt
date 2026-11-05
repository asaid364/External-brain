package app.liferecord.desktop

import app.liferecord.core.*
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.ptr.IntByReference
import java.awt.Robot
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlinx.serialization.json.*

/** Opt-in real app-image driver. No injected runtime, database writes, AI calls, or global window search clicks. */
fun main(args: Array<String>) {
    require(System.getProperty("os.name").startsWith("Windows")) { "Windows desktop required" }
    require(args.size in 1..2 && (args.size == 1 || args[1] == "--interactive"))
    val executable = Path.of(args[0]).toAbsolutePath()
    check(Files.isRegularFile(executable)) { "Build :desktop:createDistributable first" }
    val fixture = UUID.randomUUID().toString()
    val root = Files.createTempDirectory("goal-desktop-$fixture-")
    val directory = Files.createDirectory(root.resolve("client"))
    val title = "SYNTH-GOAL-$fixture"
    val robot = Robot().apply { autoDelay = 180 }
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    val previousClipboard = clipboard.getContents(null)
    var app: OwnedGoalWindow? = null
    try {
        app = OwnedGoalWindow(executable, directory, robot)
        if ("--interactive" in args) {
            var captureNumber = 0
            fun captureStep() {
                val path = root.resolve("manual-${captureNumber++}.png")
                requireNotNull(app) { "Own app is closed; use reopen" }.captureManual(path)
                println("OWN_WINDOW_CAPTURE path=$path")
                System.out.flush()
            }
            println("MANUAL_ONLY fixture=$fixture data=$directory suggestedTitle=$title; commands: capture | clickRelative <PNG-x> <PNG-y> | type <Base64UTF8> | close | reopen | inspect <Base64UTF8-title> | quit; never automatic PASS")
            captureStep()
            while (true) {
                val line = readlnOrNull() ?: break
                val fields = line.trim().split(' ', limit = 3)
                when (fields.first()) {
                    "capture" -> captureStep()
                    "clickRelative" -> {
                        require(fields.size == 3)
                        requireNotNull(app).clickRelative(fields[1].toInt(), fields[2].toInt())
                        captureStep()
                    }
                    "type" -> {
                        require(fields.size == 2)
                        requireNotNull(app).typeManual(String(Base64.getDecoder().decode(fields[1]), Charsets.UTF_8))
                        captureStep()
                    }
                    "close" -> { app?.close(); app = null; println("OWN_APP_CLOSED") }
                    "reopen" -> {
                        check(app == null) { "Close owned app before reopening" }
                        app = OwnedGoalWindow(executable, directory, robot)
                        captureStep()
                    }
                    "inspect" -> {
                        check(app == null) { "Close owned app before SQLite inspection" }
                        require(fields.size == 2)
                        val inspectedTitle = String(Base64.getDecoder().decode(fields[1]), Charsets.UTF_8)
                        val persisted = inspectDesktopGoal(directory, inspectedTitle)
                        println("SQLITE_OBSERVED goal=${persisted.first} progress=1/2=50% heads=${persisted.second.sorted().joinToString(",")}")
                    }
                    "quit" -> break
                    else -> error("Unsupported manual command; no input issued")
                }
                System.out.flush()
            }
            return
        }
        with(requireNotNull(app)) {
            click("目标")
            click("＋ 新建")
            fill("目标标题 *", title)
            fill("期望成果（清晰可验证的结果）*", "SYNTH deliver a runnable prototype and independent validation")
            fill("起始日期 *", "2027-01-01")
            fill("截止日期 *", "2027-01-21")
            fill("回顾时区 *", "UTC")
            fill("周期日数 *", "7")
            fill("标准 1", "SYNTH runnable prototype delivered")
            click("＋ 添加完成标准")
            fill("标准 2", "SYNTH independently validated")
            click("保存")
            requireText(title)
            click("确认达成", first = true)
            click("确认达成")
            requireText("50%")
            requireText("1 / 2 项")
            capture(root.resolve("confirmed.png"))
            close()
        }
        app = null
        val persisted = inspectDesktopGoal(directory, title)
        app = OwnedGoalWindow(executable, directory, robot)
        with(requireNotNull(app)) {
            click("目标")
            click(title)
            requireText("50%")
            requireText("1 / 2 项")
            capture(root.resolve("reopened.png"))
            close()
        }
        app = null
        check(inspectDesktopGoal(directory, title) == persisted) { "Reopening changed persisted goal/confirmation heads" }
        println("GOAL_DESKTOP_SMOKE: PASS fixture=$fixture goal=${persisted.first} progress=1/2=50% screenshots=$root; real app-image Robot input, SQLite heads stable after reopen; external AI not exercised")
    } finally {
        app?.close()
        if (previousClipboard != null) clipboard.setContents(previousClipboard, null)
        // Preserve only this UUID fixture and screenshots as acceptance evidence.
        println("GOAL_DESKTOP_SMOKE evidence=$root")
    }
}

private fun inspectDesktopGoal(root: Path, title: String): Pair<String, Set<String>> =
    WindowsDatabase(root.resolve("records.db")).use { db ->
        val repo = GoalRepository(db)
        val summary = repo.list(true).single { it.spec?.title == title }
        val detail = repo.detail(requireNotNull(summary.spec).id)
        check(detail.summary.completed == 1 && detail.summary.total == 2)
        val spec = requireNotNull(detail.summary.spec)
        val sync = SyncRepository(db)
        spec.id to (sync.headIds("goal", spec.id) + spec.milestones.flatMap {
            sync.headIds("goal-confirmation", "${spec.id}/${it.id}")
        })
    }

/** Every action revalidates PID ownership, exact smoke title, foreground HWND and target bounds. */
private class OwnedGoalWindow(executable: Path, directory: Path, private val robot: Robot) : AutoCloseable {
    private val process = ProcessBuilder(executable.toString(), "--data-dir=$directory", "--smoke")
        .redirectErrorStream(true).redirectOutput(directory.parent.resolve("app-${UUID.randomUUID()}.log").toFile()).start()
    private val owned = linkedMapOf<Long, ProcessHandle>(process.pid() to process.toHandle())
    private val user = User32.INSTANCE
    private val evidenceDirectory = directory.parent
    private val window: HWND
    init {
        var found: HWND? = null
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        while (found == null && System.nanoTime() < deadline) {
            process.descendants().use { stream -> stream.forEach { owned[it.pid()] = it } }
            val candidates = mutableListOf<HWND>()
            user.EnumWindows({ handle, _ ->
                val pid = IntByReference()
                user.GetWindowThreadProcessId(handle, pid)
                val text = CharArray(512)
                user.GetWindowText(handle, text, text.size)
                if (pid.value.toLong() in owned && user.IsWindowVisible(handle) &&
                    String(text).substringBefore('\u0000') == "生活记录 · 隔离合成同步测试") candidates += handle
                true
            }, null)
            if (candidates.size > 1) { close(); error("Ambiguous own window; no clicks issued") }
            found = candidates.singleOrNull()
            if (found == null) Thread.sleep(250)
        }
        if (found == null) { close(); error("No reliably identified own smoke window; no clicks issued") }
        window = found
        user.SetForegroundWindow(window)
        Thread.sleep(1000)
        try { fitOwnedWindow() } catch (failure: Throwable) { close(); throw failure }
    }
    private fun assertOwned(): Rectangle {
        val pid = IntByReference()
        user.GetWindowThreadProcessId(window, pid)
        check(owned[pid.value.toLong()]?.isAlive == true && user.IsWindow(window)) { "Own window disappeared; stop" }
        val title = CharArray(512)
        user.GetWindowText(window, title, title.size)
        check(String(title).substringBefore('\u0000') == "生活记录 · 隔离合成同步测试") { "Own window title changed; stop" }
        check(user.GetForegroundWindow() == window) { "Own window lost foreground; stop without further input" }
        val rect = RECT()
        check(user.GetWindowRect(window, rect))
        return Rectangle(rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top)
    }
    private var querySequence = 0
    private fun target(name: String, first: Boolean = false, role: String = "Text"): Rectangle {
        assertOwned()
        val handle = Pointer.nativeValue(window.pointer)
        val encodedName = Base64.getEncoder().encodeToString(name.toByteArray(Charsets.UTF_8))
        val script = """
            ${'$'}ErrorActionPreference='Stop'
            Add-Type -AssemblyName UIAutomationClient
            Add-Type -AssemblyName UIAutomationTypes
            ${'$'}root=[System.Windows.Automation.AutomationElement]::FromHandle([IntPtr]$handle)
            ${'$'}name=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$encodedName'))
            ${'$'}items=${'$'}root.FindAll([System.Windows.Automation.TreeScope]::Descendants,[System.Windows.Automation.Condition]::TrueCondition)
            ${'$'}observed=@()
            ${'$'}result=@()
            function Describe(${'$'}item) {
                ${'$'}r=${'$'}item.Current.BoundingRectangle
                return @{name=${'$'}item.Current.Name;type=${'$'}item.Current.ControlType.ProgrammaticName;offscreen=${'$'}item.Current.IsOffscreen;enabled=${'$'}item.Current.IsEnabled;x=${'$'}r.X;y=${'$'}r.Y;w=${'$'}r.Width;h=${'$'}r.Height}
            }
            foreach(${'$'}item in ${'$'}items) {
                ${'$'}observed+=Describe ${'$'}item
                if(${'$'}item.Current.Name -ne ${'$'}name) { continue }
                ${'$'}target=${'$'}item
                if('$role' -ne 'Text') {
                    ${'$'}parent=${'$'}item
                    while(${'$'}null -ne ${'$'}parent -and -not ${'$'}parent.Equals(${'$'}root)) {
                        if(${'$'}parent.Current.ControlType.ProgrammaticName -eq 'ControlType.$role') { ${'$'}target=${'$'}parent; break }
                        ${'$'}parent=[System.Windows.Automation.TreeWalker]::ControlViewWalker.GetParent(${'$'}parent)
                    }
                }
                if(${'$'}target.Current.IsOffscreen) {
                    ${'$'}pattern=${'$'}null
                    if(${'$'}target.TryGetCurrentPattern([System.Windows.Automation.ScrollItemPattern]::Pattern,[ref]${'$'}pattern)) {
                        ${'$'}pattern.ScrollIntoView()
                        Start-Sleep -Milliseconds 200
                    }
                }
                if(-not ${'$'}target.Current.IsOffscreen -and ${'$'}target.Current.IsEnabled) { ${'$'}result+=Describe ${'$'}target }
            }
            ${'$'}json=ConvertTo-Json -Depth 6 -Compress -InputObject @{matches=@(${'$'}result);observed=@(${'$'}observed)}
            [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(${'$'}json))
        """.trimIndent()
        val command = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val encodedOutput = directoryForEvidence().resolve("uia-${querySequence}.base64")
        val query = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", command)
            .redirectError(directoryForEvidence().resolve("uia-${querySequence}.stderr.log").toFile())
            .redirectOutput(encodedOutput.toFile()).start()
        if (!query.waitFor(10, TimeUnit.SECONDS)) { query.destroyForcibly(); error("Accessibility query timed out; stop") }
        check(query.exitValue() == 0) { "Accessibility query failed; see own-window UIA stderr; stop without coordinate guessing" }
        // ASCII Base64 is independent of PowerShell's OEM/GBK console codepage.
        val json = String(Base64.getDecoder().decode(Files.readString(encodedOutput, Charsets.US_ASCII).trim()), Charsets.UTF_8)
        val document = SyncWire.json.parseToJsonElement(json).jsonObject
        val evidence = directoryForEvidence().resolve("uia-${querySequence++}.json")
        Files.writeString(evidence, json, Charsets.UTF_8)
        fun bounds(item: JsonObject): Rectangle {
            fun number(key: String) = item.getValue(key).jsonPrimitive.double.toInt()
            return Rectangle(number("x"), number("y"), number("w"), number("h"))
        }
        val candidates = document.getValue("matches").jsonArray.map { it.jsonObject }
            .filter { bounds(it).width > 0 && bounds(it).height > 0 }
        val preferred = candidates.filter { it.getValue("type").jsonPrimitive.content == "ControlType.$role" }
            .ifEmpty { candidates }
        val rectangles = preferred.map(::bounds).distinct().sortedWith(compareBy<Rectangle> { it.y }.thenBy { it.x })
        // Text and its button may share a name. Collapse only overlapping nested bounds,
        // never disjoint same-name controls (except an explicitly requested first milestone).
        val separate = rectangles.filter { outer -> rectangles.none { inner -> inner != outer && outer.contains(inner) } }
        if (separate.isEmpty() || (!first && separate.size != 1)) {
            capture(directoryForEvidence().resolve("uia-failure-${querySequence}.png"))
            val observed = document.getValue("observed").jsonArray.map { it.jsonObject }.filter {
                it.getValue("offscreen").jsonPrimitive.boolean.not()
            }.map { "${it.getValue("type").jsonPrimitive.content}:${it.getValue("name").jsonPrimitive.content}" }
            println("OWN_WINDOW_CONTROLS evidence=$evidence visible=${observed.joinToString(" | ")}")
            error("Cannot uniquely locate visible '$name' role=$role matches=${separate.size}; own-window controls/PNG saved; stop")
        }
        val selected = separate.first()
        check(assertOwned().contains(selected)) { "Target outside own window; stop" }
        return selected
    }
    private fun directoryForEvidence(): Path = evidenceDirectory
    fun click(name: String, first: Boolean = false) {
        val rect = target(name, first, "Button")
        assertOwned()
        robot.mouseMove(rect.x + rect.width / 2, rect.y + rect.height / 2)
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        Thread.sleep(500)
    }
    fun fill(name: String, text: String) {
        val rect = target(name, role = "Edit")
        assertOwned()
        robot.mouseMove(rect.x + rect.width / 2, rect.y + rect.height / 2)
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        assertOwned()
        chord(KeyEvent.VK_A)
        chord(KeyEvent.VK_V)
    }
    private fun chord(key: Int) {
        assertOwned()
        robot.keyPress(KeyEvent.VK_CONTROL)
        try { robot.keyPress(key); robot.keyRelease(key) } finally { robot.keyRelease(KeyEvent.VK_CONTROL) }
    }
    fun requireText(text: String) { target(text) }
    fun capture(path: Path) { ImageIO.write(robot.createScreenCapture(assertOwned()), "png", path.toFile()) }
    private var lastManualCapture: Pair<Rectangle, Rectangle>? = null
    private fun fitOwnedWindow() {
        val before = assertOwned()
        val configuration = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        val screen = configuration.bounds
        val transform = configuration.defaultTransform
        val nativeWidth = user.GetSystemMetrics(0)
        val nativeHeight = user.GetSystemMetrics(1)
        println("OWN_WINDOW_BEFORE_FIT win32=$before graphics=$screen nativePrimary=${nativeWidth}x$nativeHeight scaleX=${transform.scaleX} scaleY=${transform.scaleY}")
        check(screen.x == 0 && screen.y == 0 && transform.shearX == 0.0 && transform.shearY == 0.0) { "Unsupported primary display transform; stop" }
        check(transform.scaleX > 0 && transform.scaleY > 0)
        val availableWidth = minOf(nativeWidth, kotlin.math.floor(screen.width * transform.scaleX).toInt())
        val availableHeight = minOf(nativeHeight, kotlin.math.floor(screen.height * transform.scaleY).toInt())
        val marginX = kotlin.math.ceil(24 * transform.scaleX).toInt()
        val marginY = kotlin.math.ceil(24 * transform.scaleY).toInt()
        val width = minOf(kotlin.math.floor(1000 * transform.scaleX).toInt(), availableWidth - marginX * 2)
        // Reserve extra space below the window for the taskbar; no system work-area changes.
        val height = minOf(kotlin.math.floor(760 * transform.scaleY).toInt(), availableHeight - kotlin.math.ceil(96 * transform.scaleY).toInt())
        check(width >= 400 * transform.scaleX && height >= 300 * transform.scaleY) { "Primary display too small for safe owned-window capture" }
        assertOwned()
        check(user.SetWindowPos(window, null, marginX, marginY, width, height, 0x0004 or 0x0010)) { "Could not resize owned smoke window" }
        Thread.sleep(600)
        val after = assertOwned()
        println("OWN_WINDOW_AFTER_FIT win32=$after graphics=$screen nativePrimary=${nativeWidth}x$nativeHeight scaleX=${transform.scaleX} scaleY=${transform.scaleY}")
        manualRobotBounds(after)
    }
    private fun manualRobotBounds(physical: Rectangle): Rectangle {
        val configuration = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        val scale = configuration.defaultTransform
        check(configuration.bounds.x == 0 && configuration.bounds.y == 0) { "Manual DPI mapping requires origin primary display; stop" }
        check(scale.shearX == 0.0 && scale.shearY == 0.0 && scale.scaleX > 0 && scale.scaleY > 0)
        val logical = Rectangle(
            kotlin.math.ceil(physical.x / scale.scaleX).toInt(),
            kotlin.math.ceil(physical.y / scale.scaleY).toInt(),
            kotlin.math.floor(physical.width / scale.scaleX).toInt(),
            kotlin.math.floor(physical.height / scale.scaleY).toInt(),
        )
        println("OWN_WINDOW_GEOMETRY win32=$physical robot=$logical graphics=${configuration.bounds} scaleX=${scale.scaleX} scaleY=${scale.scaleY}")
        check(configuration.bounds.contains(logical)) { "Own window crosses supported display bounds; stop" }
        return logical
    }
    fun captureManual(path: Path) {
        val physical = assertOwned()
        val logical = manualRobotBounds(physical)
        val image = robot.createScreenCapture(logical)
        ImageIO.write(image, "png", path.toFile())
        lastManualCapture = physical to logical
        println("OWN_WINDOW_IMAGE width=${image.width} height=${image.height}")
    }
    fun clickRelative(x: Int, y: Int) {
        val (physical, logical) = requireNotNull(lastManualCapture) { "Capture and visually inspect own window first" }
        check(assertOwned() == physical) { "Own window moved/resized since capture; recapture before input" }
        require(x in 0 until logical.width && y in 0 until logical.height) { "Point outside captured own window" }
        robot.mouseMove(logical.x + x, logical.y + y)
        assertOwned()
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        Thread.sleep(500)
    }
    fun typeManual(text: String) {
        check(lastManualCapture?.first == assertOwned()) { "Own window geometry changed; recapture" }
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        chord(KeyEvent.VK_A)
        chord(KeyEvent.VK_V)
        Thread.sleep(500)
    }
    override fun close() {
        // Closing a window may only hide it to tray. Terminate only the tracked launch tree.
        process.descendants().use { stream -> stream.forEach { owned[it.pid()] = it } }
        owned.values.toList().asReversed().forEach { if (it.isAlive) it.destroy() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (owned.values.any { it.isAlive } && System.nanoTime() < deadline) Thread.sleep(100)
        owned.values.filter { it.isAlive }.forEach { it.destroyForcibly() }
        owned.values.forEach { if (it.isAlive) it.onExit().get(10, TimeUnit.SECONDS) }
    }
}
