package com.obsidian.backup.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * In-mod backup engine (pure Java, no external process).
 *
 * Mirrors the "install a mod, it just works" model of FTB Backups / xBackup:
 * backups run inside the JVM, stored as compressed snapshots under
 * {@code .obsidian/snapshots/}. Incremental backups copy only changed files
 * (compared by mtime+size against the previous snapshot's manifest).
 */
public class EmbeddedBackupEngine {

    /** Files/dirs excluded from every backup. */
    private static final List<String> EXCLUDES = List.of(
        "session.lock", "logs", "cache", "libraries", ".obsidian"
    );

    private final Path serverRoot;
    private final Path snapshotDir;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public EmbeddedBackupEngine(Path serverRoot) {
        this.serverRoot = serverRoot;
        this.snapshotDir = serverRoot.resolve(".obsidian/snapshots");
    }

    /** A single snapshot's metadata. */
    public static final class SnapshotInfo {
        public final String id;
        public final String timestamp;
        public final String tag;
        public final long fileCount;
        public final long bytes;
        public final boolean incremental;

        SnapshotInfo(String id, String timestamp, String tag, long fileCount, long bytes, boolean incremental) {
            this.id = id;
            this.timestamp = timestamp;
            this.tag = tag;
            this.fileCount = fileCount;
            this.bytes = bytes;
            this.incremental = incremental;
        }
    }

    /** Result of a backup run. */
    public static final class BackupResult {
        public final String snapshotId;
        public final int filesCopied;
        public final long bytesCopied;
        public final boolean incremental;

        BackupResult(String snapshotId, int filesCopied, long bytesCopied, boolean incremental) {
            this.snapshotId = snapshotId;
            this.filesCopied = filesCopied;
            this.bytesCopied = bytesCopied;
            this.incremental = incremental;
        }
    }

    /**
     * Run a backup of the server root (worlds + plugin config, minus exclusions).
     *
     * @param incremental if true, only copy files changed since the last snapshot.
     */
    public BackupResult backup(String tag, boolean incremental) throws IOException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("A backup is already in progress");
        }
        try {
            return doBackup(tag, incremental);
        } finally {
            running.set(false);
        }
    }

    private BackupResult doBackup(String tag, boolean incremental) throws IOException {
        Files.createDirectories(snapshotDir);
        String snapshotId = "snap_" + System.currentTimeMillis();
        Path outZip = snapshotDir.resolve(snapshotId + ".zip");

        // Load previous manifest for incremental comparison.
        Map<String, String> previous = incremental ? loadPreviousManifest() : Collections.emptyMap();

        final int[] fileCount = {0};
        final long[] bytes = {0};
        Map<String, String> current = new LinkedHashMap<>();

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(outZip))) {
            Files.walkFileTree(serverRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String rel = rel(dir);
                    if (rel.isEmpty() || isExcluded(rel)) {
                        return rel.isEmpty() || rel.equals(".obsidian")
                            ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String rel = rel(file);
                    if (isExcluded(rel)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String sig = attrs.size() + ":" + attrs.lastModifiedTime().toMillis();
                    current.put(rel, sig);
                    if (incremental && sig.equals(previous.get(rel))) {
                        return FileVisitResult.CONTINUE; // unchanged
                    }
                    zos.putNextEntry(new ZipEntry(rel));
                    Files.copy(file, zos);
                    zos.closeEntry();
                    fileCount[0]++;
                    bytes[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        writeManifest(snapshotId, tag, fileCount[0], bytes[0], incremental, current);
        return new BackupResult(snapshotId, fileCount[0], bytes[0], incremental);
    }

    /** Restore the latest (or a specific) snapshot over the server root. */
    public void restore(String snapshotId) throws IOException {
        Path zip = snapshotDir.resolve(snapshotId + ".zip");
        if (!Files.exists(zip)) {
            throw new IOException("Snapshot not found: " + snapshotId);
        }
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path target = serverRoot.resolve(entry.getName()).normalize();
                if (!target.startsWith(serverRoot)) {
                    throw new IOException("Unsafe zip entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (OutputStream out = Files.newOutputStream(target)) {
                        zis.transferTo(out);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    /** List all snapshots, newest first. */
    public List<SnapshotInfo> listSnapshots() throws IOException {
        if (!Files.exists(snapshotDir)) {
            return Collections.emptyList();
        }
        List<SnapshotInfo> result = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(snapshotDir, "*.json")) {
            for (Path p : ds) {
                Properties props = new Properties();
                try (InputStream in = Files.newInputStream(p)) {
                    props.load(in);
                }
                result.add(new SnapshotInfo(
                    props.getProperty("id"),
                    props.getProperty("timestamp"),
                    props.getProperty("tag"),
                    Long.parseLong(props.getProperty("fileCount", "0")),
                    Long.parseLong(props.getProperty("bytes", "0")),
                    Boolean.parseBoolean(props.getProperty("incremental", "false"))
                ));
            }
        }
        result.sort(Comparator.comparing(s -> s.timestamp, Comparator.reverseOrder()));
        return result;
    }

    /** Delete old snapshots, keeping the most recent {@code keep}. */
    public int prune(int keep) throws IOException {
        List<SnapshotInfo> snaps = listSnapshots();
        int removed = 0;
        for (int i = keep; i < snaps.size(); i++) {
            SnapshotInfo s = snaps.get(i);
            Files.deleteIfExists(snapshotDir.resolve(s.id + ".zip"));
            Files.deleteIfExists(snapshotDir.resolve(s.id + ".json"));
            removed++;
        }
        return removed;
    }

    public boolean isRunning() {
        return running.get();
    }

    // ---- internals ----

    private String rel(Path p) {
        return serverRoot.relativize(p).toString().replace('\\', '/');
    }

    private boolean isExcluded(String rel) {
        for (String e : EXCLUDES) {
            if (rel.equals(e) || rel.startsWith(e + "/")) {
                return true;
            }
        }
        return false;
    }

    private Map<String, String> loadPreviousManifest() throws IOException {
        Optional<SnapshotInfo> latest = listSnapshots().stream().findFirst();
        if (latest.isEmpty()) {
            return Collections.emptyMap();
        }
        Properties props = new Properties();
        Path manifest = snapshotDir.resolve(latest.get().id + ".json");
        try (InputStream in = Files.newInputStream(manifest)) {
            props.load(in);
        }
        Map<String, String> files = new HashMap<>();
        props.forEach((k, v) -> {
            String key = k.toString();
            if (key.startsWith("file:")) {
                files.put(key.substring(5), v.toString());
            }
        });
        return files;
    }

    private void writeManifest(String id, String tag, int fileCount, long bytes,
                               boolean incremental, Map<String, String> files) throws IOException {
        Properties props = new Properties();
        props.setProperty("id", id);
        props.setProperty("timestamp", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(new Date()));
        props.setProperty("tag", tag == null ? "" : tag);
        props.setProperty("fileCount", String.valueOf(fileCount));
        props.setProperty("bytes", String.valueOf(bytes));
        props.setProperty("incremental", String.valueOf(incremental));
        files.forEach((path, sig) -> props.setProperty("file:" + path, sig));
        try (OutputStream out = Files.newOutputStream(snapshotDir.resolve(id + ".json"))) {
            props.store(out, "Obsidian Backup snapshot manifest");
        }
    }
}
