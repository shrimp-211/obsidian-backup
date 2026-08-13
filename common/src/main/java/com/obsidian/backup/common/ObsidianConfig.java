package com.obsidian.backup.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Configuration shared across all loader implementations.
 *
 * Precedence (highest first):
 *   1. JVM system properties (-Dobsidian.*)
 *   2. Config file `.obsidian/obsidian.properties` (server root)
 *   3. Built-in defaults
 *
 * The default engine is EMBEDDED (in-mod, no external process). Set
 * `engine=sidecar` in the config file to use the high-performance Rust sidecar.
 */
public class ObsidianConfig {

    public enum Engine { SIDECAR, EMBEDDED }

    private static final String CONFIG_FILE = ".obsidian/obsidian.properties";

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

    public boolean isEmbedded() { return engine == Engine.EMBEDDED; }

    /** Load config from the server-root config file + system properties. */
    public static ObsidianConfig load() {
        return load(Paths.get(CONFIG_FILE));
    }

    /** Load config, reading the given config file first (system props override). */
    public static ObsidianConfig load(Path configFile) {
        Properties file = new Properties();
        if (Files.exists(configFile)) {
            try (InputStream in = Files.newInputStream(configFile)) {
                file.load(in);
            } catch (IOException ignored) {
                // Fall back to defaults if the config file is unreadable.
            }
        }
        return new ObsidianConfig(
            prop("obsidian.socket", file, ".obsidian/ipc/obsidian.sock"),
            prop("obsidian.token", file, "obsidian-default-token"),
            Long.parseLong(prop("obsidian.connect_timeout", file, "5000")),
            !"false".equals(prop("obsidian.bossbar", file, "true")),
            !"false".equals(prop("obsidian.chat", file, "true")),
            "obsidian.admin",
            "sidecar".equalsIgnoreCase(prop("obsidian.engine", file, "embedded"))
                ? Engine.SIDECAR : Engine.EMBEDDED,
            Long.parseLong(prop("obsidian.auto_backup_minutes", file, "30")),
            Integer.parseInt(prop("obsidian.auto_backup_keep", file, "10"))
        );
    }

    /** System property takes precedence over the config file. */
    private static String prop(String key, Properties file, String def) {
        String sys = System.getProperty(key);
        if (sys != null) return sys;
        return file.getProperty(key, def);
    }
}
