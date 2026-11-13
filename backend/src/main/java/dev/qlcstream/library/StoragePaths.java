package dev.qlcstream.library;

import java.nio.file.Path;

/** Relative destinations are portable between the backend and the Linux engine. */
public final class StoragePaths {
    private StoragePaths() {}

    public static Path relative(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Informe uma pasta de destino.");
        var portable = value.trim().replace('\\', '/');
        if (portable.startsWith("/") || portable.contains(":")) throw invalid();
        var path = Path.of(portable).normalize();
        if (path.isAbsolute() || path.startsWith("..") || path.toString().isEmpty() || path.toString().equals(".")) throw invalid();
        return path;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("A pasta deve estar dentro da biblioteca local.");
    }
}
