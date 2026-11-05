package app.liferecord.desktop

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Acquire before opening SQLite or reading any encrypted configuration. */
class DesktopDataDirectory private constructor(val path: Path, private val channel: FileChannel, private val lock: FileLock) : AutoCloseable {
    override fun close() { try { lock.release() } finally { channel.close() } }

    companion object {
        fun open(arguments: Array<String>): DesktopDataDirectory {
            require(arguments.all { it == "--smoke" || it.startsWith("--data-dir=") }) { "不支持的启动参数" }
            val supplied = arguments.filter { it.startsWith("--data-dir=") }
            require(supplied.size <= 1) { "只能指定一个数据目录" }
            val smoke = "--smoke" in arguments
            require(!smoke || supplied.isNotEmpty()) { "--smoke 必须显式指定隔离 --data-dir" }
            val normal = Path.of(requireNotNull(System.getenv("LOCALAPPDATA")) { "无法确定 Windows 本地数据目录" }, "LifeRecord", "client").toAbsolutePath().normalize()
            val selected = supplied.singleOrNull()?.substringAfter('=')?.also { require(it.isNotBlank()) { "数据目录不能为空" } }?.let(Path::of)?.toAbsolutePath()?.normalize() ?: normal
            require(!smoke || selected != normal) { "合成烟测不能使用正常用户数据目录" }
            Files.createDirectories(selected)
            val real = selected.toRealPath()
            require(!smoke || !Files.exists(normal) || real != normal.toRealPath()) { "合成烟测不能通过链接指向正常数据目录" }
            if (smoke) {
                val marker = real.resolve(".liferecord-smoke")
                if (!Files.exists(marker)) {
                    Files.list(real).use { entries -> require(entries.findAny().isEmpty) { "首次合成烟测只能使用空目录" } }
                    Files.writeString(marker, "isolated synthetic data only", StandardOpenOption.CREATE_NEW)
                }
            }
            val channel = FileChannel.open(real.resolve("client.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            try {
                val lock = channel.tryLock() ?: error("此数据目录已由另一实例打开；未打开数据库")
                return DesktopDataDirectory(real, channel, lock)
            } catch (failure: Throwable) { channel.close(); throw failure }
        }
    }
}
