package com.obsidian.backup.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-mod backup engine (pure Java, no external process).
 *
 * Content-addressed storage: each file is hashed with SHA-256 and stored once
 * under {@code .obsidian/objects/<hash>}. Snapshots are manifests mapping a
 * file path to its content hash, so identical content across snapshots is
 * deduplicated. This mirrors the Rust sidecar's CAS model, implemented
 * directly in the JVM for a "install a mod, it just works" experience.
 */
public class EmbeddedBackupEngine {

    private static final List<String> EXCLUDES = List.of(
        "session.lock", "logs", "cache", "libraries", ".obsidian"
    );

    private final Path serverRoot;
    private final Path objectsDir;
    private final Path snapshotDir;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public EmbeddedBackupEngine(Path serverRoot) {
        this.serverRoot = serverRoot;
        this.objectsDir = serverRoot.resolve(".obsidian/objects");
        this.snapshotDir = serverRoot.resolve(".obsidian/snapshots");
    }

    // ---- result types ----

    public static final class SnapshotInfo {
        public final String id;
        public final String timestamp;
        public final String tag;
        public final long fileCount;
        public final long bytes;
        public final boolean incremental;
        SnapshotInfo(String id, String ts, String tag, long fileCount, long bytes, boolean inc) {
            this.id = id; this.timestamp = ts; this.tag = tag;
            this.fileCount = fileCount; this.bytes = bytes; this.incremental = inc;
        }
    }

    public static final class BackupResult {
        public final String snapshotId;
        public final int filesScanned;
        public final int filesDeduped;
        public final long bytesProcessed;
        public final boolean incremental;
        BackupResult(String id, int scanned, int deduped, long bytes, boolean inc) {
            this.snapshotId = id; this.filesScanned = scanned; this.filesDeduped = deduped;
            this.bytesProcessed = bytes; this.incremental = inc;
        }
    }

    public static final class VerifyResult {
        public final int checked;
        public final int healthy;
        public final int corrupted;
        VerifyResult(int c, int h, int x) { this.checked = c; this.healthy = h; this.corrupted = x; }
    }

    // ---- backup ----

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
        Files.createDirectories(objectsDir);
        Files.createDirectories(snapshotDir);
        String snapshotId = "snap_" + System.currentTimeMillis();

        Map<String, String> previous = incremental ? loadLatestManifest() : Collections.emptyMap();
        Map<String, String> current = new LinkedHashMap<>();
        int[] scanned = {0}, deduped = {0};
        long[] bytes = {0};

        Files.walkFileTree(serverRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String rel = rel(dir);
                if (!rel.isEmpty() && isExcluded(rel)) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String rel = rel(file);
                if (isExcluded(rel)) return FileVisitResult.CONTINUE;
                scanned[0]++;
                String hash = hash(file);
                current.put(rel, hash);
                bytes[0] += attrs.size();

                if (incremental && hash.equals(previous.get(rel))) {
                    deduped[0]++; // unchanged content
                    return FileVisitResult.CONTINUE;
                }

                // Content-addressed store: keep one copy per unique hash.
                Path object = objectsDir.resolve(hash);
                if (!Files.exists(object)) {
                    Files.copy(file, object);
                } else {
                    deduped[0]++; // dedup hit (identical content already stored)
                }
                return FileVisitResult.CONTINUE;
            }
        });

        writeManifest(snapshotId, tag, scanned[0], deduped[0], bytes[0], incremental, current);
        return new BackupResult(snapshotId, scanned[0], deduped[0], bytes[0], incremental);
    }

    // ---- restore ----

    public void restore(String snapshotId) throws IOException {
        Map<String, String> files = loadManifest(snapshotId);
        if (files == null) throw new IOException("Snapshot not found: " + snapshotId);
        for (Map.Entry<String, String> e : files.entrySet()) {
            Path target = serverRoot.resolve(e.getKey()).normalize();
            if (!target.startsWith(serverRoot)) throw new IOException("Unsafe path: " + e.getKey());
            Path object = objectsDir.resolve(e.getValue());
            if (!Files.exists(object)) throw new IOException("Missing object: " + e.getValue());
            Files.createDirectories(target.getParent());
            Files.copy(object, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ---- verify ----

    public VerifyResult verify() throws IOException {
        if (!Files.exists(snapshotDir)) return new VerifyResult(0, 0, 0);
        Set<String> hashes = new HashSet<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(snapshotDir, "*.json")) {
            for (Path p : ds) {
                Map<String, String> files = readManifestFile(p);
                if (files != null) hashes.addAll(files.values());
            }
        }
        int checked = 0, healthy = 0, corrupted = 0;
        for (String hash : hashes) {
            Path object = objectsDir.resolve(hash);
            checked++;
            if (Files.exists(object) && hash.equals(hash(object))) healthy++;
            else corrupted++;
        }
        return new VerifyResult(checked, healthy, corrupted);
    }

    // ---- snapshots ----

    public List<SnapshotInfo> listSnapshots() throws IOException {
        if (!Files.exists(snapshotDir)) return Collections.emptyList();
        List<SnapshotInfo> result = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(snapshotDir, "*.json")) {
            for (Path p : ds) {
                Properties props = readManifestFileProps(p);
                if (props == null) continue;
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

    public int prune(int keep) throws IOException {
        List<SnapshotInfo> snaps = listSnapshots();
        int removed = 0;
        for (int i = keep; i < snaps.size(); i++) {
            Files.deleteIfExists(snapshotDir.resolve(snaps.get(i).id + ".json"));
            removed++;
        }
        return removed;
    }

    public boolean isRunning() { return running.get(); }

    // ---- internals ----

    private String rel(Path p) {
        return serverRoot.relativize(p).toString().replace('\\', '/');
    }

    private boolean isExcluded(String rel) {
        for (String e : EXCLUDES) {
            if (rel.equals(e) || rel.startsWith(e + "/")) return true;
        }
        return false;
    }

    private String hash(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            try (InputStream in = Files.newInputStream(file)) {
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    private Map<String, String> loadLatestManifest() throws IOException {
        List<SnapshotInfo> snaps = listSnapshots();
        if (snaps.isEmpty()) return Collections.emptyMap();
        Map<String, String> files = loadManifest(snaps.get(0).id);
        return files == null ? Collections.emptyMap() : files;
    }

    private Map<String, String> loadManifest(String snapshotId) throws IOException {
        return readManifestFile(snapshotDir.resolve(snapshotId + ".json"));
    }

    private Map<String, String> readManifestFile(Path p) throws IOException {
        Properties props = readManifestFileProps(p);
        if (props == null) return null;
        Map<String, String> files = new HashMap<>();
        props.forEach((k, v) -> {
            String key = k.toString();
            if (key.startsWith("file:")) files.put(key.substring(5), v.toString());
        });
        return files;
    }

    private Properties readManifestFileProps(Path p) throws IOException {
        if (!Files.exists(p)) return null;
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(p)) { props.load(in); }
        return props;
    }

    private void writeManifest(String id, String tag, int scanned, int deduped, long bytes,
                               boolean incremental, Map<String, String> files) throws IOException {
        Properties props = new Properties();
        props.setProperty("id", id);
        props.setProperty("timestamp", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(new Date()));
        props.setProperty("tag", tag == null ? "" : tag);
        props.setProperty("fileCount", String.valueOf(scanned));
        props.setProperty("deduped", String.valueOf(deduped));
        props.setProperty("bytes", String.valueOf(bytes));
        props.setProperty("incremental", String.valueOf(incremental));
        files.forEach((path, hash) -> props.setProperty("file:" + path, hash));
        try (OutputStream out = Files.newOutputStream(snapshotDir.resolve(id + ".json"))) {
            props.store(out, "Obsidian Backup snapshot manifest");
        }
    }
}
