package com.obsidian.backup.ui

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.BossEvent
import java.util.UUID

/**
 * Renders backup progress as an in-game BossBar.
 *
 * Phases map to BossBar colors:
 *   SCANNING  → BLUE
 *   CHUNKING  → YELLOW
 *   BACKING_UP → GREEN
 *   RESTORING  → RED
 *   VERIFYING → PURPLE
 *   COMMITTING → WHITE
 */
class BossBarIndicator {
    // Use a fixed key so the same BossBar is reused across progress updates
    private val progressBarKey = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private var progressBar: ServerBossEvent? = null

    private val barId = ResourceLocation.fromNamespaceAndPath("obsidian_backup", "backup_progress")

    fun showProgress(
        players: Collection<ServerPlayer>,
        phase: BackupPhase,
        percent: Float,
        detail: String
    ) {
        val bar = progressBar ?: ServerBossEvent(
            Component.literal("Obsidian Backup"),
            phase.bossBarColor,
            BossEvent.BossBarOverlay.PROGRESS
        ).apply {
            isVisible = true
            setProgress(0f)
            progressBar = this
        }

        bar.color = phase.bossBarColor
        bar.name = Component.literal("§lObsidian Backup§r §8|§r ${phase.label} §8[${String.format("%.1f", percent)}%]§r §7$detail")
        bar.setProgress((percent / 100f).coerceIn(0f, 1f))

        players.forEach { bar.addPlayer(it) }
    }

    fun hideProgress(players: Collection<ServerPlayer>) {
        progressBar?.let { bar ->
            players.forEach { bar.removePlayer(it) }
            bar.isVisible = false
        }
        progressBar = null
    }

    fun hideForPlayer(player: ServerPlayer) {
        progressBar?.let { bar -> bar.removePlayer(player) }
    }

    enum class BackupPhase(val label: String, val bossBarColor: BossEvent.BossBarColor) {
        SCANNING("Scanning", BossEvent.BossBarColor.BLUE),
        CHUNKING("Chunking", BossEvent.BossBarColor.YELLOW),
        COMPRESSING("Compressing", BossEvent.BossBarColor.YELLOW),
        ENCRYPTING("Encrypting", BossEvent.BossBarColor.YELLOW),
        UPLOADING("Uploading", BossEvent.BossBarColor.GREEN),
        COMMITTING("Committing", BossEvent.BossBarColor.WHITE),
        RESTORING("Restoring", BossEvent.BossBarColor.RED),
        VERIFYING("Verifying", BossEvent.BossBarColor.PURPLE),
        ROLLING_BACK("Rolling Back", BossEvent.BossBarColor.RED),
        IDLE("Idle", BossEvent.BossBarColor.PINK)
    }
}
