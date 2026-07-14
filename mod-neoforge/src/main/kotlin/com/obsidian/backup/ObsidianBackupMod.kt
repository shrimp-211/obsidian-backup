package com.obsidian.backup

import com.obsidian.backup.command.ObsidianCommandRoot
import com.obsidian.backup.config.ModConfig
import com.obsidian.backup.hook.BackupHooks
import com.obsidian.backup.ipc.IpcClient
import com.obsidian.backup.ui.BossBarIndicator
import com.obsidian.backup.ui.ChatRenderer
import com.mojang.logging.LogUtils
import net.minecraft.resources.ResourceLocation
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import org.slf4j.Logger

@Mod(ObsidianBackupMod.MOD_ID)
class ObsidianBackupMod(
    private val modEventBus: IEventBus,
    private val modContainer: ModContainer
) {
    companion object {
        const val MOD_ID = "obsidian_backup"
        val LOGGER: Logger = LogUtils.getLogger()

        lateinit var instance: ObsidianBackupMod
            private set

        val SIDECAR_SOCKET_PATH = ".obsidian/ipc/obsidian.sock"

        fun loc(path: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(MOD_ID, path)
    }

    val config: ModConfig = ModConfig.load()
    val ipcClient: IpcClient = IpcClient(config)
    val chatRenderer: ChatRenderer = ChatRenderer()
    val bossBarIndicator: BossBarIndicator = BossBarIndicator()
    val hooks: BackupHooks = BackupHooks()

    init {
        instance = this
        modEventBus.addListener(this::commonSetup)
        modEventBus.addListener(this::serverSetup)
    }

    private fun commonSetup(event: FMLCommonSetupEvent) {
        LOGGER.info("[Obsidian Backup] Initializing Sidecar Bridge on UDS: {}", config.sidecarSocketPath)
    }

    private fun serverSetup(event: FMLDedicatedServerSetupEvent) {
        // Register forge-level event listeners
        val forgeBus = NeoForge.EVENT_BUS
        forgeBus.addListener(this::onRegisterCommands)
        forgeBus.addListener(this::onServerStarted)
        forgeBus.addListener(this::onServerStopping)
        forgeBus.addListener(this::onServerStopped)
        forgeBus.addListener(this::onServerTick)
    }

    private fun onRegisterCommands(event: RegisterCommandsEvent) {
        ObsidianCommandRoot.register(event.dispatcher)
        LOGGER.info("[Obsidian Backup] Brigadier command tree registered (/obsidian ...)")
    }

    private fun onServerStarted(event: ServerStartedEvent) {
        LOGGER.info("[Obsidian Backup] Connecting to Sidecar daemon...")
        ipcClient.connect()
        hooks.onServerStart(event.server)
    }

    private fun onServerStopping(event: ServerStoppingEvent) {
        LOGGER.info("[Obsidian Backup] Server stopping — draining pending transactions...")
        ipcClient.disconnect()
    }

    private fun onServerStopped(event: ServerStoppedEvent) {
        hooks.onServerStop(event.server)
    }

    private fun onServerTick(event: ServerTickEvent) {
        if (event is ServerTickEvent.Pre) return
        // Process IPC responses on each tick (non-blocking poll)
        ipcClient.pollResponses()
    }
}
