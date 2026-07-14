package com.obsidian.backup.hook

import com.obsidian.backup.ObsidianBackupMod
import net.minecraft.server.MinecraftServer
import net.neoforged.bus.api.Event
import net.neoforged.bus.api.EventPriority
import net.neoforged.neoforge.common.NeoForge

/**
 * Application Hook API for backup lifecycle events.
 *
 * Other mods can listen to these events to:
 * - Pause database writes before a backup snapshot
 * - Flush caches before the backup scanner runs
 * - Resume operations after backup completes
 *
 * Usage from another mod:
 * ```
 * @SubscribeEvent
 * fun onBeforeBackup(event: BackupHooks.BeforeBackupEvent) {
 *     myDatabase.flushAndLock()
 * }
 * ```
 */
class BackupHooks {

    /**
     * Posted on the Forge event bus BEFORE the Sidecar scanner starts.
     * Listeners should synchronously flush/stop writes.
     * This event is NOT cancellable — the backup proceeds regardless.
     */
    class BeforeBackupEvent(val server: MinecraftServer) : Event()

    /**
     * Posted AFTER a successful backup transaction commits.
     * Listeners can resume normal operations.
     */
    class AfterBackupEvent(
        val server: MinecraftServer,
        val snapshotId: String,
        val filesProcessed: Long,
        val bytesProcessed: Long
    ) : Event()

    /**
     * Posted if a backup transaction fails and rolls back.
     */
    class BackupFailedEvent(
        val server: MinecraftServer,
        val reason: String
    ) : Event()

    /**
     * Posted BEFORE a restore operation begins.
     * Listeners can cancel pending operations on the target world.
     */
    class BeforeRestoreEvent(
        val server: MinecraftServer,
        val snapshotId: String,
        val targetPath: String?
    ) : Event()

    /**
     * Posted AFTER a successful restore completes.
     */
    class AfterRestoreEvent(
        val server: MinecraftServer,
        val snapshotId: String
    ) : Event()

    // --- Convenience methods ---

    fun onServerStart(server: MinecraftServer) {
        ObsidianBackupMod.LOGGER.info("[Hooks] Backup event bus registered")
    }

    fun onServerStop(server: MinecraftServer) {
        ObsidianBackupMod.LOGGER.info("[Hooks] Backup event bus shutting down")
    }

    companion object {
        fun fireBeforeBackup(server: MinecraftServer) {
            NeoForge.EVENT_BUS.post(BeforeBackupEvent(server))
        }

        fun fireAfterBackup(server: MinecraftServer, snapshotId: String, files: Long, bytes: Long) {
            NeoForge.EVENT_BUS.post(AfterBackupEvent(server, snapshotId, files, bytes))
        }

        fun fireBackupFailed(server: MinecraftServer, reason: String) {
            NeoForge.EVENT_BUS.post(BackupFailedEvent(server, reason))
        }

        fun fireBeforeRestore(server: MinecraftServer, snapshotId: String, targetPath: String?) {
            NeoForge.EVENT_BUS.post(BeforeRestoreEvent(server, snapshotId, targetPath))
        }

        fun fireAfterRestore(server: MinecraftServer, snapshotId: String) {
            NeoForge.EVENT_BUS.post(AfterRestoreEvent(server, snapshotId))
        }
    }
}
