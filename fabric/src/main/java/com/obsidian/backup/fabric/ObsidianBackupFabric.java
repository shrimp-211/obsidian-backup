package com.obsidian.backup.fabric;

import com.obsidian.backup.common.IpcClient;
import com.obsidian.backup.common.IpcProtocol;
import com.obsidian.backup.common.ObsidianConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.ChatFormatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Fabric server mod entry point for Obsidian Backup.
 *
 * Uses Fabric API for command registration and lifecycle events.
 * The IPC protocol is shared via the common module.
 */
public class ObsidianBackupFabric implements DedicatedServerModInitializer {

    public static final String MOD_ID = "obsidian_backup";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private ObsidianConfig config;
    private IpcClient ipcClient;
    private final IpcClient.Logger ipcLogger = new IpcClient.Logger() {
        @Override public void info(String msg, Object... args) { LOGGER.info(msg, args); }
        @Override public void warn(String msg, Object... args) { LOGGER.warn(msg, args); }
        @Override public void error(String msg, Object... args) { LOGGER.error(msg, args); }
    };

    @Override
    public void onInitializeServer() {
        LOGGER.info("[Obsidian Backup] Fabric server mod initializing...");

        config = ObsidianConfig.load();
        ipcClient = new IpcClient(config.sidecarSocketPath(), config.authToken(), ipcLogger);

        // Register commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerCommands(dispatcher);
        });

        // Lifecycle events
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LOGGER.info("[Obsidian Backup] Server started — connecting to Sidecar...");
            ipcClient.connect();
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("[Obsidian Backup] Server stopping — disconnecting...");
            ipcClient.close();
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            ipcClient.pollResponses();
        });

        LOGGER.info("[Obsidian Backup] Fabric mod initialized. Socket: {}", config.sidecarSocketPath());
    }

    private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = literal("obsidian")
            .requires(src -> src.hasPermission(2))
            .then(literal("status")
                .executes(ctx -> {
                    sendInfo(ctx.getSource(), "正在拉取 Sidecar 实时状态...");
                    ipcClient.sendRequest(IpcProtocol.OpCode.STATUS, IpcProtocol.paramsStatus(),
                        resp -> {
                            if ("ok".equals(resp.status) && resp.data != null) {
                                var s = IpcProtocol.StatusData.fromJson(resp.data);
                                ctx.getSource().sendSystemMessage(
                                    renderStatus(s)
                                );
                            } else {
                                sendError(ctx.getSource(), resp.message != null ? resp.message : "无法连接");
                            }
                        });
                    return 1;
                }))
            .then(literal("backup")
                .executes(ctx -> executeBackup(ctx.getSource(), null, true))
                .then(literal("--tag").then(argument("tag", StringArgumentType.string())
                    .executes(ctx -> {
                        String tag = StringArgumentType.getString(ctx, "tag");
                        return executeBackup(ctx.getSource(), tag, true);
                    })))
                .then(literal("--full").executes(ctx -> executeBackup(ctx.getSource(), null, false)))
                .then(literal("--cancel").executes(ctx -> {
                    ipcClient.sendRequest(IpcProtocol.OpCode.CANCEL, Map.of(), resp -> {
                        if ("ok".equals(resp.status)) {
                            sendSuccess(ctx.getSource(), "备份事务已终止并回滚");
                        } else {
                            sendError(ctx.getSource(), "终止失败: " + resp.message);
                        }
                    });
                    return 1;
                })))
            .then(literal("restore").then(argument("snapshot_id", StringArgumentType.string())
                .executes(ctx -> {
                    String sid = StringArgumentType.getString(ctx, "snapshot_id");
                    return executeRestore(ctx.getSource(), sid, null, null);
                })
                .then(literal("--file").then(argument("path", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        String sid = StringArgumentType.getString(ctx, "snapshot_id");
                        String path = StringArgumentType.getString(ctx, "path");
                        return executeRestore(ctx.getSource(), sid, path, null);
                    })))
                .then(literal("--chunk").then(argument("coord", StringArgumentType.string())
                    .executes(ctx -> {
                        String sid = StringArgumentType.getString(ctx, "snapshot_id");
                        String coord = StringArgumentType.getString(ctx, "coord");
                        return executeRestore(ctx.getSource(), sid, null, coord);
                    })))))
            .then(literal("top")
                .executes(ctx -> executeTop(ctx.getSource(), 5))
                .then(argument("limit", IntegerArgumentType.integer(1, 20))
                    .executes(ctx -> executeTop(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "limit")))))
            .then(literal("diff").then(argument("id_a", StringArgumentType.string())
                .then(argument("id_b", StringArgumentType.string())
                    .executes(ctx -> {
                        String a = StringArgumentType.getString(ctx, "id_a");
                        String b = StringArgumentType.getString(ctx, "id_b");
                        ipcClient.sendRequest(IpcProtocol.OpCode.DIFF, IpcProtocol.paramsDiff(a, b),
                            resp -> {
                                if ("ok".equals(resp.status) && resp.data != null) {
                                    var d = resp.data;
                                    ctx.getSource().sendSystemMessage(
                                        Component.literal("─── 快照差异对比 ───").withStyle(ChatFormatting.GOLD));
                                    sendSuccess(ctx.getSource(), "+ 新增: " + d.getAsJsonArray("added").size());
                                    sendSuccess(ctx.getSource(), "* 修改: " + d.getAsJsonArray("modified").size());
                                    sendError(ctx.getSource(), "- 删除: " + d.getAsJsonArray("deleted").size());
                                }
                            });
                        return 1;
                    }))))
            .then(literal("verify")
                .executes(ctx -> {
                    ipcClient.sendRequest(IpcProtocol.OpCode.VERIFY, IpcProtocol.paramsVerify(false),
                        resp -> sendInfo(ctx.getSource(), "巡检结果: " + (resp.data != null ? resp.data.toString() : "无")));
                    return 1;
                })
                .then(literal("repair").executes(ctx -> {
                    ipcClient.sendRequest(IpcProtocol.OpCode.VERIFY, IpcProtocol.paramsVerify(true),
                        resp -> sendInfo(ctx.getSource(), "巡检+修复结果: " + (resp.data != null ? resp.data.toString() : "无")));
                    return 1;
                })))
            .then(literal("forecast").executes(ctx -> {
                ipcClient.sendRequest(IpcProtocol.OpCode.FORECAST, IpcProtocol.paramsForecast(),
                    resp -> {
                        if ("ok".equals(resp.status) && resp.data != null) {
                            var d = resp.data;
                            sendInfo(ctx.getSource(), String.format("存储预测: %.1f 天剩余, %.1f MB/天增长",
                                d.get("days_remaining").getAsDouble(),
                                d.get("growth_rate_mb_per_day").getAsDouble()));
                        }
                    });
                return 1;
            }));

        dispatcher.register(root);
        LOGGER.info("[Fabric] Brigadier command tree registered");
    }

    private int executeBackup(CommandSourceStack src, String tag, boolean incremental) {
        sendInfo(src, incremental ? "启动增量备份..." : "启动全量备份...");
        ipcClient.sendRequest(IpcProtocol.OpCode.BACKUP, IpcProtocol.paramsBackup(tag, incremental),
            resp -> {
                if ("ok".equals(resp.status) && resp.data != null) {
                    var d = resp.data;
                    sendSuccess(src, String.format("备份完成! 快照: %s | 文件: %d | 耗时: %.1fs",
                        d.get("snapshot_id").getAsString(),
                        d.get("files_changed").getAsLong(),
                        d.get("duration_ms").getAsLong() / 1000.0));
                } else {
                    sendError(src, "备份失败: " + (resp.message != null ? resp.message : "未知错误"));
                }
            });
        return 1;
    }

    private int executeRestore(CommandSourceStack src, String sid, String filePath, String chunkCoord) {
        sendInfo(src, "正在沙箱中准备恢复快照 " + sid + "...");
        ipcClient.sendRequest(IpcProtocol.OpCode.RESTORE, IpcProtocol.paramsRestore(sid, filePath, chunkCoord),
            resp -> {
                if ("ok".equals(resp.status)) {
                    sendSuccess(src, "沙箱恢复完成，已通过原子切换覆盖到目标位置");
                } else {
                    sendError(src, "恢复失败: " + (resp.message != null ? resp.message : "未知错误"));
                }
            });
        return 1;
    }

    private int executeTop(CommandSourceStack src, int limit) {
        ipcClient.sendRequest(IpcProtocol.OpCode.TOP, IpcProtocol.paramsTop(limit),
            resp -> {
                if ("ok".equals(resp.status) && resp.data != null) {
                    var files = resp.data.getAsJsonArray("files");
                    src.sendSystemMessage(Component.literal("─── 存储空间热力图 TOP " + limit + " ───")
                        .withStyle(ChatFormatting.DARK_PURPLE));
                    for (var elem : files) {
                        var obj = elem.getAsJsonObject();
                        src.sendSystemMessage(Component.literal(String.format("  %s [%d bytes]",
                            obj.get("path").getAsString(),
                            obj.get("size").getAsLong())));
                    }
                }
            });
        return 1;
    }

    // --- Chat helpers ---

    private static Component renderStatus(IpcProtocol.StatusData s) {
        var sb = new StringBuilder();
        sb.append("─── 核心流水线实时状态诊断 ───\n");
        sb.append(String.format("状态: %s | TPS: %.2f | CPU: %.1f%%\n",
            s.running ? "ACTIVE" : "IDLE", s.tps, s.cpu_percent));
        sb.append(String.format("队列: scanner=%d chunk=%d compress=%d encrypt=%d upload=%d\n",
            s.queue_status.scanner, s.queue_status.chunk,
            s.queue_status.compress, s.queue_status.encrypt,
            s.queue_status.upload));
        sb.append(String.format("存储: %d 快照 | %.1f%% 去重 | %d packfiles",
            s.storage_stats.total_snapshots,
            s.storage_stats.dedup_ratio,
            s.storage_stats.packfile_count));
        return Component.literal(sb.toString());
    }

    private static void sendSuccess(CommandSourceStack src, String msg) {
        src.sendSystemMessage(Component.literal("✓ " + msg).withStyle(ChatFormatting.GREEN));
    }

    private static void sendError(CommandSourceStack src, String msg) {
        src.sendSystemMessage(Component.literal("✗ " + msg).withStyle(ChatFormatting.RED));
    }

    private static void sendInfo(CommandSourceStack src, String msg) {
        src.sendSystemMessage(Component.literal("ℹ " + msg).withStyle(ChatFormatting.AQUA));
    }
}
