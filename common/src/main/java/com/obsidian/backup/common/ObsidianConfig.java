package com.obsidian.backup.common;

/**
 * Configuration shared across all loader implementations.
 * Loaded via system properties with sensible defaults.
 */
public class ObsidianConfig {

    private final String sidecarSocketPath;
    private final String authToken;
    private final long connectTimeoutMs;
    private final boolean enableBossBar;
    private final boolean enableChatOutput;
    private final String permissionsBase;

    public ObsidianConfig(String sidecarSocketPath, String authToken, long connectTimeoutMs,
                          boolean enableBossBar, boolean enableChatOutput, String permissionsBase) {
        this.sidecarSocketPath = sidecarSocketPath;
        this.authToken = authToken;
        this.connectTimeoutMs = connectTimeoutMs;
        this.enableBossBar = enableBossBar;
        this.enableChatOutput = enableChatOutput;
        this.permissionsBase = permissionsBase;
    }

    public String sidecarSocketPath() { return sidecarSocketPath; }
    public String authToken() { return authToken; }
    public long connectTimeoutMs() { return connectTimeoutMs; }
    public boolean enableBossBar() { return enableBossBar; }
    public boolean enableChatOutput() { return enableChatOutput; }
    public String permissionsBase() { return permissionsBase; }

    /** Load config from system properties with defaults. */
    public static ObsidianConfig load() {
        return new ObsidianConfig(
            System.getProperty("obsidian.socket", ".obsidian/ipc/obsidian.sock"),
            System.getProperty("obsidian.token", "obsidian-default-token"),
            Long.parseLong(System.getProperty("obsidian.connect_timeout", "5000")),
            !"false".equals(System.getProperty("obsidian.bossbar", "true")),
            !"false".equals(System.getProperty("obsidian.chat", "true")),
            "obsidian.admin"
        );
    }
}
