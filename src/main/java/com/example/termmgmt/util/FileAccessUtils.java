package com.example.termmgmt.util;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * Tells whether a termbase file can be written and, if not, why, without depending on the
 * wording of operating-system error messages (which are localized).
 */
public final class FileAccessUtils {

    public enum Status {
        /** The file does not exist yet, or can be opened for writing. */
        OK,
        /** The file exists and is writable by permissions, but another application holds it. */
        LOCKED,
        /** Read-only or no write permission. */
        NOT_WRITABLE,
        /** Anything else (invalid path, not a regular file). */
        ERROR
    }

    public static final class Result {
        public final Status status;
        /** Text for "Cannot access file: %s"; the path or a short reason. */
        public final String detail;

        Result(Status status, String detail) {
            this.status = status;
            this.detail = detail;
        }
    }

    private FileAccessUtils() {
    }

    /**
     * Check whether {@code filePath} can be written, without modifying it.
     *
     * The file is opened for writing (no truncation, nothing is written) and an exclusive lock
     * is tried:
     * - failing to open a file that permissions allow writing means another application has
     *   it open (a Windows sharing violation, e.g. Excel);
     * - a lock that cannot be obtained (held by another process, or by another channel of
     *   this process) also means the file is in use;
     * - a file system that does not support locking is not treated as "in use".
     */
    public static Result probeWritable(String filePath) {
        Path path;
        try {
            path = Paths.get(filePath);
        } catch (InvalidPathException e) {
            return new Result(Status.ERROR, filePath);
        }
        if (!Files.exists(path)) {
            return new Result(Status.OK, filePath);
        }
        if (!Files.isRegularFile(path)) {
            return new Result(Status.ERROR, filePath);
        }
        if (!Files.isWritable(path)) {
            return new Result(Status.NOT_WRITABLE, filePath);
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            try {
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    return new Result(Status.LOCKED, filePath);
                }
                lock.release();
            } catch (OverlappingFileLockException e) {
                return new Result(Status.LOCKED, filePath);
            } catch (IOException e) {
                // Locking is not supported here; opening for write worked, so carry on.
            }
            return new Result(Status.OK, filePath);
        } catch (IOException e) {
            return new Result(Status.LOCKED, filePath);
        }
    }

    /**
     * Decide whether a failed read or write of {@code filePath} was caused by the file being
     * in use by another application.
     *
     * The failure must involve an IOException somewhere in its cause chain (the loaders wrap
     * it, and the wrapper's message does not repeat the system text), and a fresh probe must
     * find the file locked. A disk-full or similar failure therefore stays "generic". As a
     * last resort the English Windows wording is still recognised.
     */
    public static boolean isLockFailure(Throwable error, String filePath) {
        boolean hasIoCause = false;
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof IOException) {
                hasIoCause = true;
            }
            String msg = t.getMessage();
            if (msg != null && msg.toLowerCase(Locale.ROOT).contains("being used by another process")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return hasIoCause && probeWritable(filePath).status == Status.LOCKED;
    }
}
