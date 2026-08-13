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
    private final String sidecarBinaryPath;
    private final boolean autoStartSidecar;

    public ObsidianConfig(String sidecarSocketPath, String authToken, long connectTimeoutMs,
                          boolean enableBossBar, boolean enableChatOutput, String permissionsBase,
                          String sidecarBinaryPath, boolean autoStartSidecar) {
        this.sidecarSocketPath = sidecarSocketPath;
        this.authToken = authToken;
        this.connectTimeoutMs = connectTimeoutMs;
        this.enableBossBar = enableBossBar;
        this.enableChatOutput = enableChatOutput;
        this.permissionsBase = permissionsBase;
        this.sidecarBinaryPath = sidecarBinaryPath;
        this.autoStartSidecar = autoStartSidecar;
    }

    public String sidecarSocketPath() { return sidecarSocketPath; }
    public String authToken() { return authToken; }
    public long connectTimeoutMs() { return connectTimeoutMs; }
    public boolean enableBossBar() { return enableBossBar; }
    public boolean enableChatOutput() { return enableChatOutput; }
    public String permissionsBase() { return permissionsBase; }
    public String sidecarBinaryPath() { return sidecarBinaryPath; }
    public boolean autoStartSidecar() { return autoStartSidecar; }

    /** Load config from system properties with defaults. */
    public static ObsidianConfig load() {
        return new ObsidianConfig(
            System.getProperty("obsidian.socket", ".obsidian/ipc/obsidian.sock"),
            System.getProperty("obsidian.token", "obsidian-default-token"),
            Long.parseLong(System.getProperty("obsidian.connect_timeout", "5000")),
            !"false".equals(System.getProperty("obsidian.bossbar", "true")),
            !"false".equals(System.getProperty("obsidian.chat", "true")),
            "obsidian.admin",
            System.getProperty("obsidian.sidecar_binary", SidecarProcessManager.defaultBinaryPath()),
            !"false".equals(System.getProperty("obsidian.autostart_sidecar", "true"))
        );
    }
}
