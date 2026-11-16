package dev.qlcstream.library;

import java.util.UUID;

/** A transfer must be absent before the library removes one of its files. */
public interface LibraryTransferControl {
    boolean cancelForFileDeletion(UUID downloadId);
    final class Unavailable extends RuntimeException {
        public Unavailable() { super("Transfer removal could not be confirmed."); }
    }
}
