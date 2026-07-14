package com.obsidian.backup.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.obsidian.backup.ObsidianBackupMod
import com.obsidian.backup.hook.BackupHooks
import com.obsidian.backup.ipc.IpcProtocol
import com.obsidian.backup.ui.BossBarIndicator
import com.obsidian.backup.ui.ChatRenderer
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component

/**
 * Root command registration for the /obsidian command tree.
 *
 * Implements all commands specified in the Obsidian Backup design document:
 *   /obsidian status
 *   /obsidian top [limit]
 *   /obsidian forecast
 *   /obsidian backup [--tag <tag>] [--incremental]
 *   /obsidian restore <snapshot_id> [--file <path>|--chunk <coord>]
 *   /obsidian diff <id_a> <id_b>
 *   /obsidian browse <snapshot_id> [path]
 *   /obsidian clone <snapshot_id> <new_name>
 *   /obsidian rollback --duration <1m>
 *   /obsidian verify [repair]
 *   /obsidian pin <snapshot_id> --days <count>
 *   /obsidian snapshot export <path>
 *   /obsidian snapshot import <path>
 */
object ObsidianCommandRoot {

    private const val PERMISSION_PREFIX = "obsidian.admin"

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        val root = Commands.literal("obsidian")
            .requires { src ->
                src.hasPermission(2) || src.hasPermission(4) // OP level 2+ or integrated server
            }
            .then(statusCommand())
            .then(topCommand())
            .then(forecastCommand())
            .then(backupCommand())
            .then(restoreCommand())
            .then(diffCommand())
            .then(browseCommand())
            .then(cloneCommand())
            .then(rollbackCommand())
            .then(verifyCommand())
            .then(pinCommand())
            .then(snapshotCommand())

        dispatcher.register(root)
    }

    // =========================================================================
    // /obsidian status
    // =========================================================================
    private fun statusCommand(): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal("status")
            .executes { ctx ->
                val mod = ObsidianBackupMod.instance
                val source = ctx.source

                source.sendSystemMessage(ChatRenderer().info("正在拉取 Sidecar 实时状态..."))

                mod.ipcClient.sendRequest(
                    op = IpcProtocol.OpCode.STATUS,
                    params = IpcProtocol.Params.status()
                ) { response ->
                    if (response.status == "ok" && response.data != null) {
                        try {
                            val statusData = IpcProtocol.parseStatusData(response.data)
                            val lines = ChatRenderer().renderFullStatus(statusData)
                            lines.forEach { source.sendSystemMessage(it) }
                        } catch (e: Exception) {
                            source.sendSystemMessage(ChatRenderer().error("解析状态数据失败: ${e.message}"))
                        }
                    } else {
                        source.sendSystemMessage(ChatRenderer().error(
                            response.message ?: "无法连接到 Sidecar 守护进程"
                        ))
                    }
                }
                1
            }
    }

    // =========================================================================
    // /obsidian top [limit]
    // =========================================================================
    private fun topCommand(): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal("top")
            .executes { executeTop(it, 5) }
            .then(
                Commands.argument("limit", IntegerArgumentType.integer(1, 20))
                    .executes { ctx ->
                        val limit = IntegerArgumentType.getInteger(ctx, "limit")
                        executeTop(ctx, limit)
                    }
            )
    }

    private fun executeTop(ctx: CommandContext<CommandSourceStack>, limit: Int): Int {
        val mod = ObsidianBackupMod.instance
        val source = ctx.source

        source.sendSystemMessage(ChatRenderer().info("正在分析存储空间..."))

        mod.ipcClient.sendRequest(
            op = IpcProtocol.OpCode.TOP,
            params = IpcProtocol.Params.top(limit)
        ) { response ->
            if (response.status == "ok" && response.data != null) {
                val data = response.data
                val entries = mutableListOf<Triple<String, Long, String?>>()

                data.getAsJsonArray("files")?.forEach { elem ->
                    val obj = elem.asJsonObject
                    entries.add(Triple(
                        obj.get("path").asString,
                        obj.get("size").asLong,
                        obj.get("reason")?.asString
                    ))
                }

                val dedupRatio = data.get("dedup_ratio")?.asDouble ?: 0.0
                val dictGain = data.get("dict_gain")?.asDouble ?: 0.0

                val lines = ChatRenderer().renderTop(entries, dedupRatio, dictGain)
                lines.forEach { source.sendSystemMessage(it) }
            } else {
                source.sendSystemMessage(ChatRenderer().warn(response.message ?: "暂无存储分析数据"))
            }
        }
        return 1
    }

    // =========================================================================
    // /obsidian forecast
    // =========================================================================
    private fun forecastCommand(): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal("forecast")
            .executes { ctx ->
                val mod = ObsidianBackupMod.instance
                val source = ctx.source

                mod.ipcClient.sendRequest(
                    op = IpcProtocol.OpCode.FORECAST,
                    params = IpcProtocol.Params.forecast()
                ) { response ->
                    if (response.status == "ok" && response.data != null) {
                        val data = response.data
                        val daysRemaining = data.get("days_remaining")?.asDouble ?: 0.0
                        val growthRate = data.get("growth_rate_mb_per_day")?.asDouble ?: 0.0
                        val totalCapacity = data.get("total_capacity_gb")?.asDouble ?: 0.0

                        source.sendSystemMessage(Component.literal(""))
                        source.sendSystemMessage(
                            ChatRenderer().info("📊 存储容量预测:")
                                .append("\n  当前增长率: ${"%.1f".format(growthRate)} MB/天")
                                .append("\n  总容量: ${"%.1f".format(totalCapacity)} GB")
                                .append("\n  预计剩余天数: ${"%.1f".format(daysRemaining)} 天")
                        )
                    } else {
                        source.sendSystemMessage(ChatRenderer().warn("无法计算预测数据，请先创建至少2个快照"))
                    }
                }
                1
            }
    }

    // =========================================================================
    // /obsidian backup [--tag <tag>] [--incremental]
    // =========================================================================
    private fun backupCommand(): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal("backup")
            .executes { executeBackup(it, null, true) }
            .then(
                Commands.literal("--tag")
                    .then(Commands.argument("tag", StringArgumentType.string())
                        .executes { ctx ->
                            val tag = StringArgumentType.getString(ctx, "tag")
                            executeBackup(ctx, tag, true)
                        }
                    )
            )
            .then(
                Commands.literal("--full")
                    .executes { ctx ->
                        executeBackup(ctx, null, false)
                    }
            )
            .then(
                Commands.literal("--cancel")
                    .executes { ctx ->
                        val mod = ObsidianBackupMod.instance
                        val source = ctx.source

                        mod.ipcClient.sendRequest(
                            op = IpcProtocol.OpCode.CANCEL,
                            params = emptyMap()
                        ) { response ->
                            if (response.status == "ok") {
                                source.sendSystemMessage(ChatRenderer().success("备份事务已终止并回滚"))
                            } else {
                                source.sendSystemMessage(ChatRenderer().error("终止失败: ${response.message}"))
                            }
                        }
                        1
                    }
            )
    }

    private fun executeBackup(
        ctx: CommandContext<CommandSourceStack>,
        tag: String?,
        incremental: Boolean
    ): Int {
        val mod = ObsidianBackupMod.instance
        val source = ctx.source
        val server = source.server ?: run {
            source.sendSystemMessage(ChatRenderer().error("只能在服务器端执行备份"))
            return 0
        }

        // Fire before-backup hook
        BackupHooks.fireBeforeBackup(server)

        // Show BossBar to all ops
        val onlineOps = server.playerList.players
            .filter { it.hasPermissions(2) }
        if (mod.config.enableBossBarProgress) {
            mod.bossBarIndicator.showProgress(
                onlineOps, BossBarIndicator.BackupPhase.SCANNING, 0f,
                "正在初始化..."
            )
        }

        source.sendSystemMessage(ChatRenderer().info(
            if (incremental) "🔒 启动增量备份..." else "🔒 启动全量备份..."
        ))
        if (tag != null) {
            source.sendSystemMessage(ChatRenderer().info("  标签: $tag"))
        }

        mod.ipcClient.sendRequest(
            op = IpcProtocol.OpCode.BACKUP,
            params = IpcProtocol.Params.backup(
                tag = tag,
                worldPath = server.getWorldPath(server.overworld()).toString(),
                incremental = incremental
            )
        ) { response ->
            if (response.status == "ok" && response.data != null) {
                val data = response.data
                val snapshotId = data.get("snapshot_id")?.asString ?: "unknown"
                val files = data.get("files_scanned")?.asLong ?: 0
                val bytes = data.get("bytes_processed")?.asLong ?: 0
                val duration = data.get("duration_ms")?.asLong ?: 0

                source.sendSystemMessage(ChatRenderer().success(
                    "备份完成! 快照 ID: $snapshotId | " +
                    "文件: ${ChatRenderer.formatNumber(files)} | " +
                    "大小: ${ChatRenderer.formatBytes(bytes)} | " +
                    "耗时: ${duration / 1000}s"
                ))

                BackupHooks.fireAfterBackup(server, snapshotId, files, bytes)
            } else {
                source.sendSystemMessage(ChatRenderer().error(
                    "备份失败: ${response.message ?: "未知错误"}"
                ))
                BackupHooks.fireBackupFailed(server, response.message ?: "未知错误")
            }

            // Hide BossBar
            onlineOps.forEach { mod.bossBarIndicator.hideForPlayer(it) }
        }
        return 1
    }

    // =========================================================================
    // /obsidian restore <id> [--file <path>|--chunk <coord>]
    // =========================================================================
    private fun restoreCommand(): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal("restore")
            .then(
                Commands.argument("snapshot_id", StringArgumentType.string())
                    .suggests { ctx, builder ->
                        SharedSuggestionProvider.suggest(
                            listOf("latest", "snap_"), builder
                        )
                    }
                    .executes { ctx ->
                        val snapshotId = StringArgumentType.getString(ctx, "snapshot_id")
                        executeRestore(ctx, snapshotId, null, null)
                    }
                    .then(
                        Commands.literal("--file")
                            .then(Commands.argument("path", StringArgumentType.greedyString())
                                .executes { ctx ->
                                    val snapshotId = StringArgumentType.getString(ctx, "snapshot_id")
                                    val path = StringArgumentType.getString(ctx, "path")
                                    executeRestore(ctx, snapshotId, path, null)
                                }
                            )
                    )
                    .then(
                        Commands.literal("--chunk")
                            .then(Commands.argument("coord", StringArgumentType.string())
                                .executes { ctx ->
                                    val snapshotId = StringArgumentType.getString(ctx, "snapshot_id")
                                    val coord = StringArgumentType.getString(ctx, "coord")
                                    executeRestore(ctx, snapshotId, null, coord)
                                }
                            )
                    )
            )
    }

    private fun executeRestore(
        ctx: CommandContext<CommandSourceStack>,
        snapshotId: String,
        filePath: String?,
        chunkCoord: String?
    ): Int {
        val mod = ObsidianBackupMod.instance
        val source = ctx.source
        val server = source.server ?: return 0

        val targetDesc = when {
            filePath != null -> "单文件: $filePath"
            chunkCoord != null -> "单区块: $chunkCoord"
            else -> "完整世界"
        }

        source.sendSystemMessage(ChatRenderer().warn(
            "🔄 正在沙箱中准备恢复快照 $snapshotId ($targetDesc)..."
        ))
        source.sendSystemMessage(ChatRenderer().info(
            "沙箱恢复确保原子切换，不会在线覆写原始目录"
        ))

        BackupHooks.fireBeforeRestore(server, snapshotId, filePath)

        mod.ipcClient.sendRequest(
            op = IpcProtocol.OpCode.RESTORE,
            params = IpcProtocol.Params.restore(snapshotId, filePath, chunkCoord)
        ) { response ->
            if (response.status == "ok") {
                source.sendSystemMessage(ChatRenderer().success(
                    "✅ 沙箱恢复完成。快照 $snapshotId 已通过原子切换覆盖到目标位置"
                ))
                BackupHooks.fireAfterRestore(server, snapshotId)
            } else {
                source.sendSystemMessage(ChatRenderer().error(
                    "恢复失败: ${response.message ?: "未知错误"}"
                ))
            }
        }
        return 1
    }

    // =========================================================================
    // /obsidian diff <id_a> <id_b>
    // =========================================================================
    private fun diffCommand(): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal("diff")
            .then(
                Commands.argument("snapshot_a", StringArgumentType.string())
                    .then(Commands.argument("snapshot_b", StringArgumentType.string())
                        .executes { ctx ->
                            val idA = StringArgumentType.getString(ctx, "snapshot_a")
                            val idB = StringArgumentType.getString(ctx, "snapshot_b")
                            executeDiff(ctx, idA, idB)
                        }
                    )
            )
    }

    private fun executeDiff(ctx: CommandContext<CommandSourceStack>, idA: String, idB: String): Int {
        val mod = ObsidianBackupMod.instance
        val source = ctx.source

        source.sendSystemMessage(ChatRenderer().info("正在对比快照 $idA ↔ $idB ..."))

        mod.ipcClient.sendRequest(
            op = IpcProtocol.OpCode.DIFF,
            params = IpcProtocol.Params.diff(idA, idB)
        ) { response ->
            if (response.status == "ok" && response.data != null) {
                val data = response.data
                source.sendSystemMessage(Component.literal(""))
                source.sendSystemMessage(
                    Component.literal("─── 快照差异对比 ───").withStyle(net.minecraft.ChatFormatting.GOLD)
                )

                val added = data.getAsJsonArray("added") ?: com.google.gson.JsonArray()
                val modified = data.getAsJsonArray("modified") ?: com.google.gson.JsonArray()
                val deleted = data.getAsJsonArray("deleted") ?: com.google.gson.JsonArray()

                if (added.size() > 0) {
                    source.sendSystemMessage(ChatRenderer().success("+ 新增 (${added.size()}):"))
                    added.forEach { source.sendSystemMessage(
                        Component.literal("  + ${it.asString}").withStyle(net.minecraft.ChatFormatting.GREEN)
                    )}
                }
                if (modified.size() > 0) {
                    source.sendSystemMessage(ChatRenderer().warn("* 修改 (${modified.size()}):"))
                    modified.forEach { source.sendSystemMessage(
                        Component.literal("  * ${it.asString}").withStyle(net.minecraft.ChatFormatting.YELLOW)
                    )}
                }
                if (deleted.size() > 0) {
                    source.sendSystemMessage(ChatRenderer().error("- 删除 (${deleted.size()}):"))
                    deleted.forEach { source.sendSystemMessage(
                        Component.literal("  - ${it.asString}").withStyle(net.minecraft.ChatFormatting.RED)
                    )}
                }
                if (added.size() == 0 && modified.size() == 0 && deleted.size() == 0) {
                    source.sendSystemMessage(ChatRenderer().info("两个快照之间没有差异"))
                }
            }
        }
        return 1
    }

    // =========================================================================
    // /obsidian browse <snapshot_id> [path]
    // =========================================================================
    private fun browseCommand(): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal("browse")
            .then(
                Commands.argument("snapshot_id", StringArgumentType.string())
                    .executes { ctx ->
                        val sid = StringArgumentType.getString(ctx, "snapshot_id")
                        executeBrowse(ctx, sid, null)
                    }
                    .then(Commands.argument("path", StringArgumentType.greedyString())
                        .executes { ctx ->
                            val sid = StringArgumentType.getString(ctx, "snapshot_id")
                            val path = StringArgumentType.getString(ctx, "path")
                            executeBrowse(ctx, sid, path)
                        }
                    )
            )
    }

    private fun executeBrowse(ctx: CommandContext<CommandSourceStack>, sid: String, path: String?): Int {
        val mod = ObsidianBackupMod.instance
        val source = ctx.source

        mod.ipcClient.sendRequest(
            op = IpcProtocol.OpCode.BROWSE,
            params = IpcProtocol.Params.browse(sid, path)
        ) { response ->
            if (response.status == "ok" && response.data != null) {
                val data = response.data
                source.sendSystemMessage(Component.literal("─── 快照 $sid 文件树 ───")
                    .withStyle(net.minecraft.ChatFormatting.GOLD))

                data.getAsJsonArray("entries")?.forEach { entry ->
                    val obj = entry.asJsonObject
                    val name = obj.get("name").asString
                    val isDir = obj.get("is_dir")?.asBoolean ?: false
                    val size = obj.get("size")?.asLong ?: 0
                    val prefix = if (isDir) "📁" else "📄"
                    val sizeStr = if (!isDir) " (${ChatRenderer.formatBytes(size)})" else ""
                    source.sendSystemMessage(Component.literal("  $prefix $name$sizeStr"))
                }
            }
        }
        return 1
    }

    // =========================================================================
    // /obsidian clone <snapshot_id> <new_name>
    // =========================================================================
    private fun cloneCommand(): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal("clone")
            .then(
                Commands.argument("snapshot_id", StringArgumentType.string())
                    .then(Commands.argument("new_name", StringArgumentType.string())
                        .executes { ctx ->
                            val sid = StringArgumentType.getString(ctx, "snapshot_id")
                            val newName = StringArgumentType.getString(ctx, "new_name")
                            executeClone(ctx, sid, newName)
                        }
                    )
            )
    }

    private fun executeClone(ctx: CommandContext<CommandSourceStack>, sid: String, newName: String): Int {
        val mod = ObsidianBackupMod.instance
        val source = ctx.source

        source.sendSystemMessage(ChatRenderer().info("正在从快照 $sid 克隆世界 '$newName' ..."))

        mod.ipcClient.sendRequest(
            op = IpcProtocol.OpCode.CLONE,
            params = IpcProtocol.Params.clone(sid, newName)
        ) { response ->
            if (response.status == "ok") {
                source.sendSystemMessage(ChatRenderer().success("世界 '$newName' 已从快照 $sid 克隆完成"))
            } else {
                source.sendSystemMessage(ChatRenderer().error("克隆失败: ${response.message}"))
            }
        }
        return 1
    }

    // =========================================================================
    // /obsidian rollback --duration <duration>
    // =========================================================================
    private fun rollbackCommand(): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal("rollback")
            .then(
                Commands.literal("--duration")
                    .then(Commands.argument("duration", StringArgumentType.string())
                        .executes { ctx ->
                            val duration = StringArgumentType.getString(ctx, "duration")
                            executeRollback(ctx, duration)
                        }
                    )
            )
    }

    private fun executeRollback(ctx: CommandContext<CommandSourceStack>, duration: String): Int {
        val mod = ObsidianBackupMod.instance
        val source = ctx.source

        source.sendSystemMessage(ChatRenderer().warn("⏪ 正在将近线状态回滚 $duration 之前..."))

        mod.ipcClient.sendRequest(
            op = IpcProtocol.OpCode.ROLLBACK,
            params = IpcProtocol.Params.rollback(duration)
        ) { response ->
            if (response.status == "ok") {
                source.sendSystemMessage(ChatRenderer().success("近线闪回完成，已回滚至 $duration 前状态"))
            } else {
                source.sendSystemMessage(ChatRenderer().error("回滚失败: ${response.message}"))
            }
        }
        return 1
    }

    // =========================================================================
    // /obsidian verify [repair]
    // =========================================================================
    private fun verifyCommand(): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal("verify")
            .executes { ctx ->
                executeVerify(ctx, false)
            }
            .then(
                Commands.literal("repair")
                    .executes { ctx ->
                        executeVerify(ctx, true)
                    }
            )
    }

    private fun executeVerify(ctx: CommandContext<CommandSourceStack>, repair: Boolean): Int {
        val mod = ObsidianBackupMod.instance
        val source = ctx.source

        val action = if (repair) "巡检并修复" else "巡检"
        source.sendSystemMessage(ChatRenderer().info("🔍 正在$action整仓快照完整性..."))

        mod.ipcClient.sendRequest(
            op = IpcProtocol.OpCode.VERIFY,
            params = IpcProtocol.Params.verify(repair)
        ) { response ->
            if (response.status == "ok" && response.data != null) {
                val data = response.data
                val total = data.get("total_checked")?.asLong ?: 0
                val healthy = data.get("healthy")?.asLong ?: 0
                val corrupted = data.get("corrupted")?.asLong ?: 0
                val repaired = data.get("repaired")?.asLong ?: 0

                source.sendSystemMessage(ChatRenderer().success(
                    "巡检完成: 总计 $total | 健康 $healthy | 损坏 $corrupted" +
                    (if (repair) " | 已修复 $repaired" else "")
                ))
                if (corrupted > 0 && !repair) {
                    source.sendSystemMessage(ChatRenderer().warn(
                        "发现 $corrupted 个损坏快照。使用 /obsidian verify repair 尝试纠删码修复"
                    ))
                }
            }
        }
        return 1
    }

    // =========================================================================
    // /obsidian pin <snapshot_id> --days <count>
    // =========================================================================
    private fun pinCommand(): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal("pin")
            .then(
                Commands.argument("snapshot_id", StringArgumentType.string())
                    .then(
                        Commands.literal("--days")
                            .then(Commands.argument("days", IntegerArgumentType.integer(1, 365))
                                .executes { ctx ->
                                    val sid = StringArgumentType.getString(ctx, "snapshot_id")
                                    val days = IntegerArgumentType.getInteger(ctx, "days")
                                    executePin(ctx, sid, days)
                                }
                            )
                    )
            )
    }

    private fun executePin(ctx: CommandContext<CommandSourceStack>, sid: String, days: Int): Int {
        val mod = ObsidianBackupMod.instance
        val source = ctx.source

        mod.ipcClient.sendRequest(
            op = IpcProtocol.OpCode.PIN,
            params = IpcProtocol.Params.pin(sid, days)
        ) { response ->
            if (response.status == "ok") {
                source.sendSystemMessage(ChatRenderer().success(
                    "🔒 快照 $sid 已锁定 $days 天（WORM 保护），此期间不可被 GC 删除"
                ))
            } else {
                source.sendSystemMessage(ChatRenderer().error("锁定失败: ${response.message}"))
            }
        }
        return 1
    }

    // =========================================================================
    // /obsidian snapshot export <path>
    // /obsidian snapshot import <path>
    // =========================================================================
    private fun snapshotCommand(): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal("snapshot")
            .then(
                Commands.literal("export")
                    .then(Commands.argument("path", StringArgumentType.greedyString())
                        .executes { ctx ->
                            val path = StringArgumentType.getString(ctx, "path")
                            executeSnapshotExport(ctx, path)
                        }
                    )
            )
            .then(
                Commands.literal("import")
                    .then(Commands.argument("path", StringArgumentType.greedyString())
                        .executes { ctx ->
                            val path = StringArgumentType.getString(ctx, "path")
                            executeSnapshotImport(ctx, path)
                        }
                    )
            )
    }

    private fun executeSnapshotExport(ctx: CommandContext<CommandSourceStack>, path: String): Int {
        val mod = ObsidianBackupMod.instance
        val source = ctx.source
        source.sendSystemMessage(ChatRenderer().info("正在导出快照归档到 $path ..."))
        mod.ipcClient.sendRequest(IpcProtocol.OpCode.BACKUP, mapOf("export_path" to path)) { response ->
            if (response.status == "ok") {
                source.sendSystemMessage(ChatRenderer().success("快照归档已导出至 $path"))
            } else {
                source.sendSystemMessage(ChatRenderer().error("导出失败: ${response.message}"))
            }
        }
        return 1
    }

    private fun executeSnapshotImport(ctx: CommandContext<CommandSourceStack>, path: String): Int {
        val mod = ObsidianBackupMod.instance
        val source = ctx.source
        source.sendSystemMessage(ChatRenderer().info("正在从 $path 导入快照归档..."))
        mod.ipcClient.sendRequest(IpcProtocol.OpCode.BACKUP, mapOf("import_path" to path)) { response ->
            if (response.status == "ok") {
                source.sendSystemMessage(ChatRenderer().success("快照归档已从 $path 导入"))
            } else {
                source.sendSystemMessage(ChatRenderer().error("导入失败: ${response.message}"))
            }
        }
        return 1
    }
}
