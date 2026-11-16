package dev.qlcstream.library;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.util.ArrayList;
import java.util.List;

/** All traversal and deletion stay relative to pinned directory handles. */
final class LibraryFileDeletion implements AutoCloseable {
    private final List<DirectoryStream<Path>> handles = new ArrayList<>();
    private SecureDirectoryStream<Path> parent;
    private Path name;
    private boolean absent;
    private Path helperRoot;
    private String helperRelative;
    private static final Path HELPER = Path.of("/app/library-file-delete.py");

    static LibraryFileDeletion open(Path root, String relativePath) throws IOException {
        var operation = new LibraryFileDeletion();
        try {
            var relative = Path.of(relativePath);
            if (relativePath.isBlank() || relative.isAbsolute() || relativePath.matches("(?i)^[a-z]:.*")
                    || relativePath.startsWith("\\") || relative.toString().equals(".")) throw new IOException("unsafe path");
            for (var part : relative) {
                if (part.toString().equals("..") || part.toString().equals(".")) throw new IOException("unsafe path");
            }
            var absoluteRoot = root.toAbsolutePath().normalize();
            var first = Files.newDirectoryStream(absoluteRoot.getRoot());
            operation.handles.add(first);
            if (!(first instanceof SecureDirectoryStream<Path> secure)) {
                if (!Files.isRegularFile(HELPER)) throw new UnsupportedOperationException();
                operation.helperRoot = absoluteRoot;
                operation.helperRelative = relativePath;
                operation.runHelper("check");
                return operation;
            }
            operation.parent = secure;
            // Root parents are traversed with the same no-links rule as media parents.
            for (var part : absoluteRoot) operation.descend(part);
            for (var i = 0; i < relative.getNameCount() - 1; i++) {
                try { operation.descend(relative.getName(i)); }
                catch (NoSuchFileException missing) { operation.absent = true; return operation; }
            }
            operation.name = relative.getFileName();
            try {
                var attributes = operation.parent.getFileAttributeView(operation.name,
                        BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
                if (!attributes.isRegularFile()) throw new IOException("not a regular file");
            } catch (NoSuchFileException missing) { operation.absent = true; }
            return operation;
        } catch (IOException | RuntimeException failure) {
            operation.close();
            throw failure;
        }
    }

    private void descend(Path part) throws IOException {
        var next = parent.newDirectoryStream(part, LinkOption.NOFOLLOW_LINKS);
        handles.add(next);
        parent = next;
    }

    void delete() throws IOException {
        if (helperRoot != null) { runHelper("delete"); return; }
        if (absent) return;
        try { parent.deleteFile(name); }
        catch (NoSuchFileException missing) { /* A concurrent removal is already complete. */ }
    }

    private void runHelper(String action) throws IOException {
        var process = new ProcessBuilder("python3", "-I", "-S", "-B", HELPER.toString(), action,
                helperRoot.toString(), helperRelative)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        boolean interrupted = false;
        try {
            for (;;) {
                try {
                    if (process.waitFor() != 0) throw new IOException("Secure file operation refused.");
                    return;
                } catch (InterruptedException interruption) { interrupted = true; }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    @Override public void close() throws IOException {
        IOException failure = null;
        for (var i = handles.size() - 1; i >= 0; i--) {
            try { handles.get(i).close(); }
            catch (IOException exception) { failure = exception; }
        }
        if (failure != null) throw failure;
    }
}
