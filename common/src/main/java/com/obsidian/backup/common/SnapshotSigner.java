package com.obsidian.backup.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.util.Base64;

/**
 * Ed25519 snapshot-manifest signing (Java implementation mirroring the Rust
 * sidecar's SnapshotSigner).
 *
 * A snapshot manifest is signed on commit; restore/verify re-check the
 * signature so offline tampering is detected. The key pair persists under
 * {@code .obsidian/keys/sign.key} (+ {@code .pub}).
 */
public final class SnapshotSigner {

    private final KeyPair keyPair;
    private final Path keyPath;

    private SnapshotSigner(KeyPair keyPair, Path keyPath) {
        this.keyPair = keyPair;
        this.keyPath = keyPath;
    }

    /** Load (or generate and persist) the signing key pair. */
    public static SnapshotSigner loadOrCreate(Path serverRoot) throws IOException {
        Path keysDir = serverRoot.resolve(".obsidian/keys");
        Path keyPath = keysDir.resolve("sign.key");
        Files.createDirectories(keysDir);

        if (Files.exists(keyPath)) {
            byte[] raw = Files.readAllBytes(keyPath);
            try {
                KeyFactory kf = KeyFactory.getInstance("Ed25519");
                PrivateKey priv = kf.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(raw));
                PublicKey pub = kf.generatePublic(
                    new java.security.spec.X509EncodedKeySpec(Files.readAllBytes(keysDir.resolve("sign.pub"))));
                return new SnapshotSigner(new KeyPair(pub, priv), keyPath);
            } catch (Exception e) {
                throw new IOException("Failed to load Ed25519 key", e);
            }
        }

        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
            KeyPair kp = kpg.generateKeyPair();
            Files.write(keyPath, kp.getPrivate().getEncoded());
            Files.write(keysDir.resolve("sign.pub"), kp.getPublic().getEncoded());
            return new SnapshotSigner(kp, keyPath);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Ed25519 unavailable", e);
        }
    }

    /** Sign bytes with Ed25519, returning a base64 signature. */
    public String sign(byte[] data) throws IOException {
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(keyPair.getPrivate());
            sig.update(data);
            return Base64.getEncoder().encodeToString(sig.sign());
        } catch (Exception e) {
            throw new IOException("Signing failed", e);
        }
    }

    /** Verify a base64 signature over the given data. */
    public boolean verify(byte[] data, String signatureB64) {
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(keyPair.getPublic());
            sig.update(data);
            return sig.verify(Base64.getDecoder().decode(signatureB64));
        } catch (Exception e) {
            return false;
        }
    }

    public byte[] publicKey() {
        return keyPair.getPublic().getEncoded();
    }
}
