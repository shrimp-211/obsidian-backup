package com.obsidian.backup.config

import com.obsidian.backup.ObsidianBackupMod

/**
 * Configuration for the Obsidian Backup NeoForge mod.
 * In production, these would be loaded from a YAML/TOML config file.
 * For Phase 1, we use sensible defaults with system property overrides.
 */
data class ModConfig(
    val sidecarSocketPath: String = ".obsidian/ipc/obsidian.sock",
    val sidecarConnectTimeoutMs: Long = 5000,
    val sidecarRequestTimeoutMs: Long = 30000,
    val enableBossBarProgress: Boolean = true,
    val enableChatOutput: Boolean = true,
    val permissionsBase: String = "obsidian.admin",
    val exclusionPatterns: List<String> = listOf(
        "**/session.lock",
        "**/logs/**",
        "**/cache/**",
        "**/libraries/**"
    ),
    val adaptiveThresholds: AdaptiveThresholds = AdaptiveThresholds()
) {
    data class AdaptiveThresholds(
        val tpsCritical: Double = 15.5,
        val tpsDanger: Double = 16.5,
        val maxMemoryMb: Long = 2048
    )

    companion object {
        fun load(): ModConfig {
            val socketPath = System.getProperty("obsidian.socket", ".obsidian/ipc/obsidian.sock")
            val connectTimeout = System.getProperty("obsidian.connect_timeout", "5000").toLong()
            val requestTimeout = System.getProperty("obsidian.request_timeout", "30000").toLong()

            ObsidianBackupMod.LOGGER.info("[Config] Sidecar socket: {}", socketPath)

            return ModConfig(
                sidecarSocketPath = socketPath,
                sidecarConnectTimeoutMs = connectTimeout,
                sidecarRequestTimeoutMs = requestTimeout
            )
        }
    }
}
