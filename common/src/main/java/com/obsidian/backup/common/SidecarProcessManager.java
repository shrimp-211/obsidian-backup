package com.obsidian.backup.common;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Manages the Sidecar process lifecycle so operators don't have to run a
 * daemon manually — mirroring the "install a mod, it just works" experience of
 * FTB Backups / xBackup.
 *
 * The Sidecar binary lives in the server root (e.g. `obsidian-sidecar` on
 * Unix, `obsidian-sidecar.exe` on Windows). On server start the loader spawns
 * it; on server stop it is terminated. If the binary is absent, callers fall
 * back to an externally-managed Sidecar.
 */
public class SidecarProcessManager implements AutoCloseable {

    /** Single-method log sink (SAM-compatible for Java and Kotlin callers). */
    @FunctionalInterface
    public interface Logger {
        void log(String msg);
    }

    private final String binaryPath;
    private final String serverRoot;
    private final String socketPath;
    private final Logger logger;
    private Process process;

    public SidecarProcessManager(String binaryPath, String serverRoot, String socketPath, Logger logger) {
        this.binaryPath = binaryPath;
        this.serverRoot = serverRoot;
        this.socketPath = socketPath;
        this.logger = logger;
    }

    /** Resolve the default Sidecar binary path for the current platform. */
    public static String defaultBinaryPath() {
        return isWindows() ? "obsidian-sidecar.exe" : "obsidian-sidecar";
    }

    /**
     * Start the Sidecar if it isn't already reachable.
     *
     * @return true if the Sidecar is now (or was already) reachable.
     */
    public boolean startIfNeeded() {
        // If the socket is already reachable, an external Sidecar is running.
        if (isReachable()) {
            return true;
        }

        File binary = new File(serverRoot, binaryPath);
        if (!binary.exists()) {
            logger.log("[Obsidian] Sidecar binary not found at " + binary.getAbsolutePath()
                    + ". Run obsidian-sidecar manually, or place the binary in the server root.");
            return false;
        }

        try {
            logger.log("[Obsidian] Starting Sidecar process: " + binary.getAbsolutePath());
            ProcessBuilder pb = new ProcessBuilder(
                binary.getAbsolutePath(),
                "--server-root", new File(serverRoot).getAbsolutePath(),
                "--socket", socketPath
            );
            pb.directory(new File(serverRoot));
            pb.redirectErrorStream(true);
            process = pb.start();
        } catch (IOException e) {
            logger.log("[Obsidian] Failed to start Sidecar: " + e.getMessage());
            return false;
        }

        // Wait for the IPC socket to become reachable.
        for (int i = 0; i < 30; i++) {
            if (isReachable()) {
                logger.log("[Obsidian] Sidecar is up (" + (i + 1) * 200 + " ms)");
                return true;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        logger.log("[Obsidian] Sidecar did not become reachable within 6s");
        return false;
    }

    /** Check whether the Sidecar IPC endpoint is reachable. */
    private boolean isReachable() {
        return new File(socketPath).exists();
    }

    @Override
    public void close() {
        if (process != null) {
            logger.log("[Obsidian] Stopping Sidecar process");
            process.destroy();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            process = null;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
