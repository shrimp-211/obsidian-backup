package com.obsidian.backup.bukkit;

import com.obsidian.backup.common.IpcClient;
import com.obsidian.backup.common.IpcProtocol;
import com.obsidian.backup.common.ObsidianConfig;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.logging.Level;

/**
 * Bukkit/Paper plugin for Obsidian Backup.
 *
 * Provides the /obsidian command tree using the Bukkit Command API
 * with TabCompleter support. The IPC protocol is shared via the common module.
 */
public class ObsidianBackupPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {

    private ObsidianConfig config;
    private IpcClient ipcClient;
    private final IpcClient.Logger ipcLogger = new IpcClient.Logger() {
        @Override public void info(String msg, Object... args) {
            getLogger().info(String.format(msg.replace("{}", "%s"), args));
        }
        @Override public void warn(String msg, Object... args) {
            getLogger().warning(String.format(msg.replace("{}", "%s"), args));
        }
        @Override public void error(String msg, Object... args) {
            getLogger().severe(String.format(msg.replace("{}", "%s"), args));
        }
    };

    @Override
    public void onEnable() {
        config = ObsidianConfig.load();
        ipcClient = new IpcClient(config.sidecarSocketPath(), config.authToken(), ipcLogger);

        // Register command
        var cmd = getCommand("obsidian");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        // Connect to Sidecar
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!ipcClient.connect()) {
                getLogger().warning("Failed to connect to Sidecar daemon at " + config.sidecarSocketPath());
            }
        }, 20L);

        // Poll IPC responses every tick
        Bukkit.getScheduler().runTaskTimer(this, () -> ipcClient.pollResponses(), 1L, 1L);

        getLogger().info("[Obsidian Backup] Bukkit plugin enabled. Socket: " + config.sidecarSocketPath());
    }

    @Override
    public void onDisable() {
        if (ipcClient != null) ipcClient.close();
        getLogger().info("[Obsidian Backup] Bukkit plugin disabled");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                              @NotNull String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.GOLD + "Obsidian Backup v" + getDescription().getVersion());
            sender.sendMessage(ChatColor.GRAY + "/obsidian status - 实时状态诊断");
            sender.sendMessage(ChatColor.GRAY + "/obsidian backup [--tag <tag>] [--full] - 启动备份");
            sender.sendMessage(ChatColor.GRAY + "/obsidian restore <id> [--file <path>] - 恢复快照");
            sender.sendMessage(ChatColor.GRAY + "/obsidian top [limit] - 存储空间分析");
            sender.sendMessage(ChatColor.GRAY + "/obsidian diff <a> <b> - 快照差异对比");
            sender.sendMessage(ChatColor.GRAY + "/obsidian verify [repair] - 完整性巡检");
            sender.sendMessage(ChatColor.GRAY + "/obsidian forecast - 存储容量预测");
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "status" -> {
                send(sender, "&b正在拉取 Sidecar 实时状态...");
                ipcClient.sendRequest(IpcProtocol.OpCode.STATUS, IpcProtocol.paramsStatus(),
                    resp -> {
                        if ("ok".equals(resp.status) && resp.data != null) {
                            var s = IpcProtocol.StatusData.fromJson(resp.data);
                            send(sender, "&a状态: " + (s.running ? "ACTIVE" : "IDLE") +
                                " | TPS: " + String.format("%.2f", s.tps) +
                                " | 队列: " + s.queue_status.scanner + "/" +
                                s.queue_status.chunk + "/" + s.queue_status.compress);
                        } else {
                            send(sender, "&c无法连接到 Sidecar 守护进程");
                        }
                    });
            }
            case "backup" -> {
                String tag = extractArg(args, "--tag");
                boolean full = hasArg(args, "--full");
                boolean cancel = hasArg(args, "--cancel");

                if (cancel) {
                    ipcClient.sendRequest(IpcProtocol.OpCode.CANCEL, Map.of(),
                        resp -> send(sender, "ok".equals(resp.status) ? "&a已终止并回滚" : "&c终止失败"));
                } else {
                    send(sender, "&b启动" + (full ? "全量" : "增量") + "备份" +
                        (tag != null ? " (标签: " + tag + ")" : "") + "...");
                    ipcClient.sendRequest(IpcProtocol.OpCode.BACKUP, IpcProtocol.paramsBackup(tag, !full),
                        resp -> {
                            if ("ok".equals(resp.status) && resp.data != null) {
                                var d = resp.data;
                                send(sender, "&a备份完成! 快照: " + d.get("snapshot_id").getAsString() +
                                    " | 文件: " + d.get("files_changed").getAsLong());
                            } else {
                                send(sender, "&c备份失败: " + (resp.message != null ? resp.message : "未知错误"));
                            }
                        });
                }
            }
            case "restore" -> {
                if (args.length < 2) { send(sender, "&c用法: /obsidian restore <snapshot_id> [--file <path>]"); break; }
                String sid = args[1];
                String file = extractArg(args, "--file");
                String chunk = extractArg(args, "--chunk");
                send(sender, "&b正在沙箱中恢复快照 " + sid + "...");
                ipcClient.sendRequest(IpcProtocol.OpCode.RESTORE,
                    IpcProtocol.paramsRestore(sid, file, chunk),
                    resp -> send(sender, "ok".equals(resp.status) ? "&a恢复完成" : "&c恢复失败: " + resp.message));
            }
            case "top" -> {
                int limit = args.length > 1 ? parseInt(args[1], 5) : 5;
                ipcClient.sendRequest(IpcProtocol.OpCode.TOP, IpcProtocol.paramsTop(limit),
                    resp -> {
                        if ("ok".equals(resp.status) && resp.data != null) {
                            var files = resp.data.getAsJsonArray("files");
                            send(sender, "&5─── 存储空间热力图 TOP " + limit + " ───");
                            for (var elem : files) {
                                var obj = elem.getAsJsonObject();
                                send(sender, "  " + obj.get("path").getAsString() +
                                    " [" + obj.get("size").getAsLong() + " bytes]");
                            }
                        }
                    });
            }
            case "verify" -> {
                boolean repair = hasArg(args, "repair");
                ipcClient.sendRequest(IpcProtocol.OpCode.VERIFY, IpcProtocol.paramsVerify(repair),
                    resp -> send(sender, "ok".equals(resp.status) ? "&a巡检完成" : "&c巡检失败"));
            }
            case "diff" -> {
                if (args.length < 3) { send(sender, "&c用法: /obsidian diff <id_a> <id_b>"); break; }
                ipcClient.sendRequest(IpcProtocol.OpCode.DIFF, IpcProtocol.paramsDiff(args[1], args[2]),
                    resp -> {
                        if ("ok".equals(resp.status) && resp.data != null) {
                            var d = resp.data;
                            send(sender, "&e─── 快照差异对比 ───");
                            send(sender, "&a+ 新增: " + d.getAsJsonArray("added").size());
                            send(sender, "&e* 修改: " + d.getAsJsonArray("modified").size());
                            send(sender, "&c- 删除: " + d.getAsJsonArray("deleted").size());
                        }
                    });
            }
            case "forecast" -> {
                ipcClient.sendRequest(IpcProtocol.OpCode.FORECAST, IpcProtocol.paramsForecast(),
                    resp -> {
                        if ("ok".equals(resp.status) && resp.data != null) {
                            var d = resp.data;
                            send(sender, "&b存储预测: " +
                                String.format("%.1f", d.get("days_remaining").getAsDouble()) + " 天剩余");
                        } else {
                            send(sender, "&e需要至少 2 个快照才能预测");
                        }
                    });
            }
            default -> send(sender, "&c未知子命令: /obsidian " + sub);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                       @NotNull String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("status", "backup", "restore", "top", "diff", "verify", "forecast"), args[0]);
        }
        return switch (args[0].toLowerCase()) {
            case "backup" -> filter(List.of("--tag", "--full", "--cancel"), args[args.length - 1]);
            case "restore" -> args.length == 2 ? List.of("<snapshot_id>") : filter(List.of("--file", "--chunk"), args[args.length - 1]);
            default -> List.of();
        };
    }

    private static List<String> filter(List<String> options, String prefix) {
        return options.stream().filter(s -> s.toLowerCase().startsWith(prefix.toLowerCase())).toList();
    }

    private static String extractArg(String[] args, String flag) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(flag)) return args[i + 1];
        }
        return null;
    }

    private static boolean hasArg(String[] args, String flag) {
        for (String arg : args) if (arg.equals(flag)) return true;
        return false;
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return def; }
    }

    private static void send(CommandSender sender, String msg) {
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
    }
}
