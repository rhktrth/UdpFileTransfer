package com.github.rhktrth.udpfiletransfer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class FileHash {
    private static final int BUFFER_SIZE = 8192;

    private FileHash() {
    }

    static byte[] sha256(Path file) throws IOException {
        MessageDigest digest = newSha256();
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }

    static boolean sha256Equals(Path file, byte[] expected) throws IOException {
        return MessageDigest.isEqual(sha256(file), expected);
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 must be available in the JRE", e);
        }
    }
}
