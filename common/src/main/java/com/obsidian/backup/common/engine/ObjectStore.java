package com.obsidian.backup.common.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Content-addressable object store with reference counting.
 *
 * Each chunk is stored once under {@code .obsidian/objects/<hash>}. The
 * reference count tracks how many files point at a chunk so that garbage
 * collection can reclaim unreferenced chunks after a snapshot is pruned.
 */
public final class ObjectStore {

    private final Path objectsDir;
    private final Map<String, AtomicLong> refCounts = new ConcurrentHashMap<>();

    public ObjectStore(Path serverRoot) throws IOException {
        this.objectsDir = serverRoot.resolve(".obsidian/objects");
        Files.createDirectories(objectsDir);
    }

    /** Store a chunk. Returns true if it was newly written (not a dedup hit). */
    public boolean put(String hash, byte[] data) throws IOException {
        Path object = objectsDir.resolve(hash);
        if (Files.exists(object)) {
            return false; // dedup hit
        }
        try (OutputStream out = Files.newOutputStream(object)) {
            out.write(data);
        }
        return true;
    }

    public byte[] get(String hash) throws IOException {
        return Files.readAllBytes(objectsDir.resolve(hash));
    }

    public boolean contains(String hash) {
        return Files.exists(objectsDir.resolve(hash));
    }

    public long size(String hash) throws IOException {
        return Files.size(objectsDir.resolve(hash));
    }

    /** Increment the reference count for a chunk. */
    public void incrementRef(String hash) {
        refCounts.computeIfAbsent(hash, h -> new AtomicLong()).incrementAndGet();
    }

    /** Decrement the reference count; returns the new count. */
    public long decrementRef(String hash) {
        AtomicLong c = refCounts.get(hash);
        if (c == null) return 0;
        long n = c.decrementAndGet();
        if (n <= 0) refCounts.remove(hash);
        return Math.max(0, n);
    }

    public long refCount(String hash) {
        AtomicLong c = refCounts.get(hash);
        return c == null ? 0 : c.get();
    }

    /** Delete chunks whose reference count has dropped to zero. */
    public int gc() throws IOException {
        int removed = 0;
        for (Map.Entry<String, AtomicLong> e : refCounts.entrySet()) {
            if (e.getValue().get() <= 0) {
                Files.deleteIfExists(objectsDir.resolve(e.getKey()));
                refCounts.remove(e.getKey());
                removed++;
            }
        }
        return removed;
    }
}
