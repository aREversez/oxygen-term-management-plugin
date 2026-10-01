package com.example.termmgmt.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Writes a file by filling a temporary file in the target's own directory and moving it over
 * the target once the content is complete. A crash half-way leaves the original file
 * untouched and the temporary file is always cleaned up, so a failed save never degrades
 * into a truncated or empty termbase.
 */
public final class AtomicFileWriter {

    /** Functional interface for the content-producing step. */
    public interface ContentWriter {
        void write(OutputStream out) throws IOException;
    }

    private AtomicFileWriter() {
    }

    /**
     * Write {@code target} atomically.
     *
     * @param target  the file to (over)write
     * @param content receives the output stream of the temporary file; must flush/write
     *                everything before returning
     * @throws IOException if producing the content or the final move failed; in that case
     *                     {@code target} still holds its previous content (or does not exist)
     */
    public static void write(Path target, ContentWriter content) throws IOException {
        Path dir = target.getParent();
        Path parent = (dir != null) ? dir : Path.of(".");
        Path tmp = Files.createTempFile(parent, target.getFileName() + ".", ".tmp");
        try {
            // 9.1: Preserve original file permissions on POSIX systems.
            preservePermissions(target, tmp);
            try (OutputStream out = new java.io.BufferedOutputStream(
                    Files.newOutputStream(tmp, StandardOpenOption.WRITE))) {
                content.write(out);
                out.flush();
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // File system (or cross-device target) cannot do it atomically; a plain
                // replace is still all-or-nothing enough here because the content is complete.
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e2) {
                    throw moveFailure(target, e2);
                }
            } catch (IOException e) {
                throw moveFailure(target, e);
            }
            tmp = null; // moved, nothing left to clean up
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // The temp file is garbage; the target is what matters and is intact.
                }
            }
        }
    }

    /**
     * A failed move usually means the target is held by another application (Excel on
     * Windows). Say so explicitly instead of letting the caller guess from the raw error.
     */
    private static IOException moveFailure(Path target, IOException cause) {
        FileAccessUtils.Result probe = FileAccessUtils.probeWritable(target.toString());
        if (probe.status == FileAccessUtils.Status.LOCKED) {
            return new IOException("File in use by another application, cannot save: " + target, cause);
        }
        if (probe.status == FileAccessUtils.Status.NOT_WRITABLE) {
            return new IOException("File is read-only, cannot save: " + target, cause);
        }
        return cause;
    }

    /**
     * 9.1: On POSIX file systems, copy the target's permissions to the temp file so the
     * atomic move does not silently change them. Non-POSIX systems (Windows) skip.
     */
    private static void preservePermissions(Path target, Path tmp) {
        try {
            if (Files.exists(target)) {
                Set<PosixFilePermission> perms = Files.getPosixFilePermissions(target);
                Files.setPosixFilePermissions(tmp, perms);
            }
        } catch (UnsupportedOperationException | IOException e) {
            // Non-POSIX FS or permission unavailable; skip silently.
        }
    }
}
