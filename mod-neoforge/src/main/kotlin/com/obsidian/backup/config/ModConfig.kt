package com.obsidian.backup.config

import com.obsidian.backup.ObsidianBackupMod

/**
 * Configuration for the Obsidian Backup NeoForge mod.
 * In production, these would be loaded from a YAML/TOML config file.
 * For Phase 1, we use sensible defaults with system property overrides.
 */
data class ModConfig(
    val sidecarSocketPath: String = ".obsidian/ipc/obsidian.sock",
    val sidecarToken: String = "obsidian-default-token",
    val embeddedEngine: Boolean = true,
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
            val props = java.util.Properties()
            try {
                java.nio.file.Files.newInputStream(
                    java.nio.file.Path.of("config/obsidian.properties")
                ).use { props.load(it) }
            } catch (_: Exception) {
                // config file absent — use defaults
            }

            fun prop(key: String, def: String): String =
                System.getProperty(key) ?: props.getProperty(key, def)

            val socketPath = prop("obsidian.socket", ".obsidian/ipc/obsidian.sock")
            val token = prop("obsidian.token", "obsidian-default-token")
            val embedded = !"sidecar".equals(prop("obsidian.engine", "embedded"), true)
            val connectTimeout = prop("obsidian.connect_timeout", "5000").toLong()
            val requestTimeout = prop("obsidian.request_timeout", "30000").toLong()

            ObsidianBackupMod.LOGGER.info("[Config] Sidecar socket: {}", socketPath)

            return ModConfig(
                sidecarSocketPath = socketPath,
                sidecarToken = token,
                embeddedEngine = embedded,
                sidecarConnectTimeoutMs = connectTimeout,
                sidecarRequestTimeoutMs = requestTimeout
            )
        }
    }
}
