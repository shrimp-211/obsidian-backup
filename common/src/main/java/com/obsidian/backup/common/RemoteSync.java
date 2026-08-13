package com.obsidian.backup.common;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.*;
import java.util.List;
import java.util.Map;

/**
 * Peer-to-peer snapshot transfer (Java implementation mirroring the Rust
 * sidecar's remote_sync). Either peer can be the active sender — the one with
 * a public IP calls {@link #serve(int)}, the other {@link #push} / {@link #pull}.
 */
public final class RemoteSync {

    private final EmbeddedBackupEngine engine;
    private final String token;

    public RemoteSync(EmbeddedBackupEngine engine, String token) {
        this.engine = engine;
        this.token = token == null ? "obsidian-default-token" : token;
    }

    /** Push a snapshot to a remote peer. */
    public void push(String snapshotId, String host, int port) throws IOException {
        try (Socket sock = new Socket(host, port);
             DataOutputStream out = new DataOutputStream(sock.getOutputStream());
             DataInputStream in = new DataInputStream(sock.getInputStream())) {
            write(out, "push:" + snapshotId);
            if (!"ok".equals(read(in))) throw new IOException("Peer rejected push");
            engine.exportSnapshot(snapshotId, Path.of(System.getProperty("java.io.tmpdir"), "obsidian-export"));
            sendFiles(sock, snapshotId);
        }
    }

    /** Pull a snapshot from a remote peer. */
    public void pull(String snapshotId, String host, int port) throws IOException {
        try (Socket sock = new Socket(host, port);
             DataOutputStream out = new DataOutputStream(sock.getOutputStream());
             DataInputStream in = new DataInputStream(sock.getInputStream())) {
            write(out, "pull:" + snapshotId);
            if (!"ok".equals(read(in))) throw new IOException("Peer rejected pull");
            receiveFiles(sock);
        }
    }

    /** Listen (public-IP peer) and answer push/pull requests. */
    public void serve(int port) throws IOException {
        try (ServerSocket server = new ServerSocket(port)) {
            while (true) {
                try (Socket sock = server.accept()) {
                    handle(sock);
                } catch (IOException ignored) {}
            }
        }
    }

    private void handle(Socket sock) {
        try (DataOutputStream out = new DataOutputStream(sock.getOutputStream());
             DataInputStream in = new DataInputStream(sock.getInputStream())) {
            String req = read(in);
            write(out, "ok");
            if (req.startsWith("push:")) {
                receiveFiles(sock);
            } else if (req.startsWith("pull:")) {
                String sid = req.substring(5);
                engine.exportSnapshot(sid, Path.of(System.getProperty("java.io.tmpdir"), "obsidian-export"));
                sendFiles(sock, sid);
            }
        } catch (IOException ignored) {}
    }

    // ---- wire helpers (length-prefixed files) ----

    private void sendFiles(Socket sock, String snapshotId) throws IOException {
        Path export = Path.of(System.getProperty("java.io.tmpdir"), "obsidian-export");
        DataOutputStream out = new DataOutputStream(sock.getOutputStream());
        List<Path> files = Files.walk(export).filter(Files::isRegularFile).toList();
        write(out, String.valueOf(files.size()));
        for (Path f : files) {
            String rel = export.relativize(f).toString().replace('\\', '/');
            byte[] data = Files.readAllBytes(f);
            write(out, rel + ":" + data.length);
            out.write(data);
        }
        out.flush();
    }

    private void receiveFiles(Socket sock) throws IOException {
        DataInputStream in = new DataInputStream(sock.getInputStream());
        int count = Integer.parseInt(read(in));
        Path dest = Path.of(".obsidian/received");
        Files.createDirectories(dest);
        for (int i = 0; i < count; i++) {
            String header = read(in);
            int sep = header.lastIndexOf(':');
            String rel = header.substring(0, sep);
            int len = Integer.parseInt(header.substring(sep + 1));
            byte[] data = new byte[len];
            in.readFully(data);
            Path target = dest.resolve(rel).normalize();
            Files.createDirectories(target.getParent());
            Files.write(target, data);
        }
    }

    private static void write(DataOutputStream out, String s) throws IOException {
        out.writeInt(s.length());
        out.writeBytes(s);
        out.flush();
    }

    private static String read(DataInputStream in) throws IOException {
        int len = in.readInt();
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b);
    }
}
