package si.opendavki.broker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** A broker statement file held in memory, with its name and SHA-256 for audit references. */
public record StatementSource(String name, byte[] content) {

    public static StatementSource read(Path path) throws IOException {
        return new StatementSource(path.getFileName().toString(), Files.readAllBytes(path));
    }

    public static StatementSource ofText(String name, String text) {
        return new StatementSource(name, text.getBytes(StandardCharsets.UTF_8));
    }

    public String sha256() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** UTF-8 text without a leading byte-order mark. */
    public String text() {
        String s = new String(content, StandardCharsets.UTF_8);
        return s.startsWith("﻿") ? s.substring(1) : s;
    }
}
