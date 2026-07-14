package com.obsidian.backup.ui

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.*
import java.text.NumberFormat
import java.util.Locale

/**
 * Renders rich text components for all /obsidian command outputs.
 * Uses Minecraft's Component system for clickable, hoverable text.
 */
class ChatRenderer {

    companion object {
        private val NUMBER_FORMAT = NumberFormat.getNumberInstance(Locale.US)

        fun formatBytes(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
                else -> "${"%.2f".format(bytes.toDouble() / (1024 * 1024 * 1024))} GB"
            }
        }

        fun formatNumber(n: Long): String = NUMBER_FORMAT.format(n)
    }

    // --- Command Output Builders ---

    fun statusHeader(): MutableComponent {
        return Component.literal("\n")
            .append(Component.literal("─── 核心流水线实时状态诊断 ───").withStyle(Style.EMPTY
                .withColor(ChatFormatting.GOLD)
                .withBold(true)))
    }

    fun statusLine(label: String, value: String, color: ChatFormatting = ChatFormatting.WHITE): MutableComponent {
        return Component.literal("")
            .append(Component.literal("$label: ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(value).withStyle(color))
    }

    fun statusRunning(running: Boolean, txId: String?): MutableComponent {
        val indicator = if (running) "🟢 ACTIVE" else "⚫ IDLE"
        val color = if (running) ChatFormatting.GREEN else ChatFormatting.GRAY
        val txInfo = txId?.let { " | 事务 ID: $it" } ?: ""
        return Component.literal("运行状态: ")
            .append(Component.literal("$indicator$txInfo").withStyle(color))
    }

    fun adaptiveLevel(tps: Double, throttled: Boolean): MutableComponent {
        val (level, color) = when {
            tps < 15.5 -> Pair("🔴 CRITICAL (TPS 跌破安全线，已挂起后台任务)", ChatFormatting.RED)
            tps < 16.5 -> Pair("🟡 WARN (检测到 TPS 波动，已自动节流)", ChatFormatting.YELLOW)
            throttled -> Pair("🟡 WARN (已节流)", ChatFormatting.YELLOW)
            else -> Pair("🟢 NORMAL", ChatFormatting.GREEN)
        }
        return Component.literal("自适应级: ")
            .append(Component.literal(level).withStyle(color))
    }

    fun queueStatus(status: com.obsidian.backup.ipc.IpcProtocol.QueueStatus): MutableComponent {
        val sb = StringBuilder()
        sb.append("[Scanner] ──(${status.scanner} 块)──► ")
        sb.append("[Chunk] ──(${status.chunk} 块)──► ")
        sb.append("[Compress] ──(${status.compress} 块")
        if (status.compress > 100) sb.append(" ⚠️")
        sb.append(")──► ")
        sb.append("[Encrypt] ──(${status.encrypt} 块)──► ")
        sb.append("[Upload]")

        // Bottleneck detection
        val maxQueue = maxOf(status.scanner, status.chunk, status.compress, status.encrypt, status.upload)
        val bottleneck = when {
            maxQueue == status.compress && status.compress > 50 -> "瓶颈诊断：[Compress] 队列积压，ZSTD 多线程压缩正在全力运转。"
            maxQueue == status.chunk && status.chunk > 50 -> "瓶颈诊断：[Chunk] 分块队列积压，FastCDC 正在处理大文件。"
            maxQueue == status.upload && status.upload > 50 -> "瓶颈诊断：[Upload] 上传队列积压，网络带宽不足。"
            maxQueue == status.encrypt && status.encrypt > 50 -> "瓶颈诊断：[Encrypt] 加密队列积压。"
            else -> null
        }

        val comp = Component.literal("📦 缓冲队列阻滞状态: ")
            .append(Component.literal(sb.toString()).withStyle(ChatFormatting.AQUA))

        return if (bottleneck != null) {
            comp.append("\n  ").append(Component.literal(bottleneck).withStyle(ChatFormatting.YELLOW))
        } else comp
    }

    fun performanceMetrics(
        tps: Double,
        cpu: Double,
        memory: Long,
        diskRead: Long,
        diskWrite: Long,
        network: Double
    ): MutableComponent {
        return Component.literal("⚡ 性能速率指标:")
            .append("\n  - 游戏 TPS: ${"%.2f".format(tps)} | 宿主机 CPU: ${"%.1f".format(cpu)}% | 堆外内存: $memory MB")
            .append("\n  - 磁盘 IOPS: ${formatNumber(diskRead)} / ${formatNumber(diskWrite)} (安全) | 网络: ${"%.1f".format(network)} MB/s")
            .withStyle(ChatFormatting.GRAY)
    }

    fun actionButtons(): MutableComponent {
        val pauseBtn = Component.literal("[ ⏸️ 暂停备份 ]")
            .withStyle(Style.EMPTY
                .withColor(ChatFormatting.GOLD)
                .withBold(true)
                .withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, "/obsidian backup --pause"))
                .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    Component.literal("点击暂停当前备份任务"))))

        val stopBtn = Component.literal("[ 🛑 终止并回滚事务 ]")
            .withStyle(Style.EMPTY
                .withColor(ChatFormatting.RED)
                .withBold(true)
                .withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, "/obsidian backup --cancel"))
                .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    Component.literal("点击终止当前备份并回滚事务"))))

        return Component.literal("  ")
            .append(pauseBtn)
            .append("  ")
            .append(stopBtn)
    }

    fun topHeader(): MutableComponent {
        return Component.literal("\n")
            .append(Component.literal("─── 全局存储仓空间热力图 (TOP) ───").withStyle(Style.EMPTY
                .withColor(ChatFormatting.DARK_PURPLE)
                .withBold(true)))
    }

    fun dedupStats(ratio: Double, dictGain: Double): MutableComponent {
        return Component.literal("📊 综合全局去重比: ${"%.1f".format(ratio)}% | 字典压缩增益: +${"%.1f".format(dictGain)}%")
            .withStyle(ChatFormatting.GRAY)
    }

    fun topFileEntry(rank: Int, path: String, size: Long, reason: String?): MutableComponent {
        val color = when (rank) {
            1 -> ChatFormatting.RED
            2 -> ChatFormatting.YELLOW
            else -> ChatFormatting.GREEN
        }
        val reasonText = reason?.let { " [分析原因: $it]" } ?: ""
        return Component.literal("$rank. ")
            .append(Component.literal(path).withStyle(color))
            .append("  [体积: ${formatBytes(size)}]")
            .append(Component.literal(reasonText).withStyle(ChatFormatting.GRAY))
    }

    fun error(message: String): MutableComponent {
        return Component.literal("❌ $message").withStyle(ChatFormatting.RED)
    }

    fun success(message: String): MutableComponent {
        return Component.literal("✅ $message").withStyle(ChatFormatting.GREEN)
    }

    fun warn(message: String): MutableComponent {
        return Component.literal("⚠️ $message").withStyle(ChatFormatting.YELLOW)
    }

    fun info(message: String): MutableComponent {
        return Component.literal("ℹ️ $message").withStyle(ChatFormatting.AQUA)
    }

    /**
     * Builds a formatted chat line for the /obsidian status output,
     * mirroring the layout specified in mainidea.md.
     */
    fun renderFullStatus(status: com.obsidian.backup.ipc.IpcProtocol.StatusData): List<MutableComponent> {
        return listOf(
            statusHeader(),
            Component.literal(""),
            statusRunning(status.running, status.currentTx),
            adaptiveLevel(status.tps, status.state != "idle"),
            Component.literal(""),
            queueStatus(status.queue_status),
            Component.literal(""),
            performanceMetrics(
                status.tps, status.cpu_percent, status.memory_mb,
                status.disk_iops_read, status.disk_iops_write,
                status.network_upload_mbps
            ),
            Component.literal(""),
            actionButtons()
        )
    }

    fun renderTop(entries: List<Triple<String, Long, String?>>, dedupRatio: Double, dictGain: Double): List<MutableComponent> {
        val lines = mutableListOf<MutableComponent>(
            topHeader(),
            Component.literal(""),
            dedupStats(dedupRatio, dictGain),
            Component.literal(""),
            Component.literal("📂 空间膨胀源文件排行 (悬停可查看该文件引发的去重碎片率):")
        )
        entries.forEachIndexed { index, (path, size, reason) ->
            lines.add(topFileEntry(index + 1, path, size, reason))
        }
        return lines
    }
}
