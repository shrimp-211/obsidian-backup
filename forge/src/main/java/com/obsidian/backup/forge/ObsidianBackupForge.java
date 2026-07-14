package com.obsidian.backup.forge;

import com.obsidian.backup.common.IpcClient;
import com.obsidian.backup.common.IpcProtocol;
import com.obsidian.backup.common.ObsidianConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

@Mod(ObsidianBackupForge.MOD_ID)
public class ObsidianBackupForge {

    public static final String MOD_ID = "obsidian_backup";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private ObsidianConfig config;
    private IpcClient ipcClient;
    private final IpcClient.Logger ipcLogger = new IpcClient.Logger() {
        @Override public void info(String msg, Object... args) { LOGGER.info(msg, args); }
        @Override public void warn(String msg, Object... args) { LOGGER.warn(msg, args); }
        @Override public void error(String msg, Object... args) { LOGGER.error(msg, args); }
    };

    public ObsidianBackupForge() {
        config = ObsidianConfig.load();
        ipcClient = new IpcClient(config.sidecarSocketPath(), config.authToken(), ipcLogger);

        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::serverSetup);

        // Make sure the mod is server-side only
        ModLoadingContext.get().registerExtensionPoint(
            IExtensionPoint.DisplayTest.class,
            () -> new IExtensionPoint.DisplayTest(() -> "obsidian_backup", (a, b) -> true)
        );

        MinecraftForge.EVENT_BUS.register(this);
    }

    private void serverSetup(FMLDedicatedServerSetupEvent event) {
        LOGGER.info("[Obsidian Backup] Forge server mod initializing...");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        registerCommands(event.getDispatcher());
        LOGGER.info("[Forge] Brigadier command tree registered");
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        LOGGER.info("[Obsidian Backup] Server started — connecting to Sidecar...");
        ipcClient.connect();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("[Obsidian Backup] Server stopping — disconnecting...");
        ipcClient.close();
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            ipcClient.pollResponses();
        }
    }

    private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = literal("obsidian")
            .requires(src -> src.hasPermission(2))
            .then(literal("status")
                .executes(ctx -> doStatus(ctx.getSource())))
            .then(literal("backup")
                .executes(ctx -> doBackup(ctx.getSource(), null, true))
                .then(literal("--tag").then(argument("tag", StringArgumentType.string())
                    .executes(ctx -> doBackup(ctx.getSource(),
                        StringArgumentType.getString(ctx, "tag"), true))))
                .then(literal("--full").executes(ctx -> doBackup(ctx.getSource(), null, false)))
                .then(literal("--cancel").executes(ctx -> doCancel(ctx.getSource()))))
            .then(literal("restore").then(argument("snapshot_id", StringArgumentType.string())
                .executes(ctx -> doRestore(ctx.getSource(),
                    StringArgumentType.getString(ctx, "snapshot_id"), null, null))))
            .then(literal("top").executes(ctx -> doTop(ctx.getSource(), 5)))
            .then(literal("verify")
                .executes(ctx -> doVerify(ctx.getSource(), false))
                .then(literal("repair").executes(ctx -> doVerify(ctx.getSource(), true))))
            .then(literal("forecast").executes(ctx -> doForecast(ctx.getSource())));

        dispatcher.register(root);
    }

    private int doStatus(CommandSourceStack src) {
        sendInfo(src, "正在拉取 Sidecar 实时状态...");
        ipcClient.sendRequest(IpcProtocol.OpCode.STATUS, IpcProtocol.paramsStatus(), resp -> {
            if ("ok".equals(resp.status) && resp.data != null) {
                var s = IpcProtocol.StatusData.fromJson(resp.data);
                sendSuccess(src, String.format("状态: %s | TPS: %.2f | 队列: %d/%d/%d",
                    s.running ? "ACTIVE" : "IDLE", s.tps,
                    s.queue_status.chunk, s.queue_status.compress, s.queue_status.upload));
            } else {
                sendError(src, "无法连接到 Sidecar 守护进程");
            }
        });
        return 1;
    }

    private int doBackup(CommandSourceStack src, String tag, boolean incremental) {
        sendInfo(src, "启动备份...");
        ipcClient.sendRequest(IpcProtocol.OpCode.BACKUP, IpcProtocol.paramsBackup(tag, incremental), resp -> {
            if ("ok".equals(resp.status) && resp.data != null) {
                var d = resp.data;
                sendSuccess(src, "备份完成! 快照: " + d.get("snapshot_id").getAsString());
            } else {
                sendError(src, "备份失败: " + resp.message);
            }
        });
        return 1;
    }

    private int doCancel(CommandSourceStack src) {
        ipcClient.sendRequest(IpcProtocol.OpCode.CANCEL, Map.of(),
            resp -> sendInfo(src, "ok".equals(resp.status) ? "已终止" : "终止失败"));
        return 1;
    }

    private int doRestore(CommandSourceStack src, String sid, String file, String chunk) {
        sendInfo(src, "沙箱恢复中...");
        ipcClient.sendRequest(IpcProtocol.OpCode.RESTORE, IpcProtocol.paramsRestore(sid, file, chunk),
            resp -> sendSuccess(src, "ok".equals(resp.status) ? "恢复完成" : "恢复失败: " + resp.message));
        return 1;
    }

    private int doTop(CommandSourceStack src, int limit) {
        ipcClient.sendRequest(IpcProtocol.OpCode.TOP, IpcProtocol.paramsTop(limit), resp -> {
            if ("ok".equals(resp.status) && resp.data != null) {
                src.sendSystemMessage(Component.literal("─── 存储热力图 TOP " + limit + " ───")
                    .withStyle(ChatFormatting.DARK_PURPLE));
            }
        });
        return 1;
    }

    private int doVerify(CommandSourceStack src, boolean repair) {
        ipcClient.sendRequest(IpcProtocol.OpCode.VERIFY, IpcProtocol.paramsVerify(repair),
            resp -> sendInfo(src, "ok".equals(resp.status) ? "巡检完成" : "巡检失败"));
        return 1;
    }

    private int doForecast(CommandSourceStack src) {
        ipcClient.sendRequest(IpcProtocol.OpCode.FORECAST, IpcProtocol.paramsForecast(), resp -> {
            if ("ok".equals(resp.status) && resp.data != null) {
                var d = resp.data;
                sendInfo(src, "存储预测: " + String.format("%.1f", d.get("days_remaining").getAsDouble()) + " 天");
            }
        });
        return 1;
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
