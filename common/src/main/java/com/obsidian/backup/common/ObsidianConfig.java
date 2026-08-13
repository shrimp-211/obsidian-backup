package com.obsidian.backup.common;

/**
 * Configuration shared across all loader implementations.
 * Loaded via system properties with sensible defaults.
 */
public class ObsidianConfig {

    /** Backup engine modes. */
    public enum Engine { SIDECAR, EMBEDDED }

    private final String sidecarSocketPath;
    private final String authToken;
    private final long connectTimeoutMs;
    private final boolean enableBossBar;
    private final boolean enableChatOutput;
    private final String permissionsBase;
    private final Engine engine;
    private final long autoBackupIntervalMinutes;
    private final int autoBackupKeep;

    public ObsidianConfig(String sidecarSocketPath, String authToken, long connectTimeoutMs,
                          boolean enableBossBar, boolean enableChatOutput, String permissionsBase,
                          Engine engine, long autoBackupIntervalMinutes, int autoBackupKeep) {
        this.sidecarSocketPath = sidecarSocketPath;
        this.authToken = authToken;
        this.connectTimeoutMs = connectTimeoutMs;
        this.enableBossBar = enableBossBar;
        this.enableChatOutput = enableChatOutput;
        this.permissionsBase = permissionsBase;
        this.engine = engine;
        this.autoBackupIntervalMinutes = autoBackupIntervalMinutes;
        this.autoBackupKeep = autoBackupKeep;
    }

    public String sidecarSocketPath() { return sidecarSocketPath; }
    public String authToken() { return authToken; }
    public long connectTimeoutMs() { return connectTimeoutMs; }
    public boolean enableBossBar() { return enableBossBar; }
    public boolean enableChatOutput() { return enableChatOutput; }
    public String permissionsBase() { return permissionsBase; }
    public Engine engine() { return engine; }
    public long autoBackupIntervalMinutes() { return autoBackupIntervalMinutes; }
    public int autoBackupKeep() { return autoBackupKeep; }

    /** True when using the embedded (in-mod) backup engine. */
    public boolean isEmbedded() { return engine == Engine.EMBEDDED; }

    /** Load config from system properties with defaults. */
    public static ObsidianConfig load() {
        String engineProp = System.getProperty("obsidian.engine", "sidecar");
        Engine engine = "embedded".equalsIgnoreCase(engineProp) ? Engine.EMBEDDED : Engine.SIDECAR;
        return new ObsidianConfig(
            System.getProperty("obsidian.socket", ".obsidian/ipc/obsidian.sock"),
            System.getProperty("obsidian.token", "obsidian-default-token"),
            Long.parseLong(System.getProperty("obsidian.connect_timeout", "5000")),
            !"false".equals(System.getProperty("obsidian.bossbar", "true")),
            !"false".equals(System.getProperty("obsidian.chat", "true")),
            "obsidian.admin",
            engine,
            Long.parseLong(System.getProperty("obsidian.auto_backup_minutes", "30")),
            Integer.parseInt(System.getProperty("obsidian.auto_backup_keep", "10"))
        );
    }
}
