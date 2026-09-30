package com.example.termmgmt.util;

import com.example.termmgmt.util.FileAccessUtils.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class FileAccessUtilsTest {

    @TempDir
    Path tempDir;

    private Path newFile() throws IOException {
        Path file = tempDir.resolve("terms.csv");
        Files.writeString(file, "zh-cn,en-us\n你好,hello\n");
        return file;
    }

    @Test
    void probeWritable_missingFile_isOk() {
        assertEquals(Status.OK, FileAccessUtils.probeWritable(tempDir.resolve("new.csv").toString()).status);
    }

    @Test
    void probeWritable_normalFile_isOkAndLeavesItUntouched() throws Exception {
        Path file = newFile();
        long size = Files.size(file);
        long modified = Files.getLastModifiedTime(file).toMillis();

        assertEquals(Status.OK, FileAccessUtils.probeWritable(file.toString()).status);

        assertEquals(size, Files.size(file));
        assertEquals(modified, Files.getLastModifiedTime(file).toMillis());
    }

    @Test
    void probeWritable_fileHeldByALock_isLocked() throws Exception {
        Path file = newFile();
        try (FileChannel holder = FileChannel.open(file, StandardOpenOption.WRITE);
             FileLock lock = holder.lock()) {
            assertEquals(Status.LOCKED, FileAccessUtils.probeWritable(file.toString()).status);
        }
        assertEquals(Status.OK, FileAccessUtils.probeWritable(file.toString()).status);
    }

    @Test
    void probeWritable_readOnlyFile_isNotWritable() throws Exception {
        Path file = newFile();
        assumeFalse(!file.toFile().setWritable(false) || Files.isWritable(file),
            "permissions are not enforced for this user (e.g. root)");
        assertEquals(Status.NOT_WRITABLE, FileAccessUtils.probeWritable(file.toString()).status);
    }

    @Test
    void probeWritable_directoryAndInvalidPath_areErrors() {
        assertEquals(Status.ERROR, FileAccessUtils.probeWritable(tempDir.toString()).status);
        assertEquals(Status.ERROR, FileAccessUtils.probeWritable("bad\0path").status);
    }

    @Test
    void isLockFailure_wrappedIoErrorOnLockedFile_isTrueWithoutRelyingOnMessageText() throws Exception {
        Path file = newFile();
        // Same shape as the loaders' wrapper: the message never contains the system text.
        Throwable wrapped = new RuntimeException("Failed to save CSV: " + file,
            new RuntimeException("Failed to save termbase", new IOException("Der Prozess kann nicht auf die Datei zugreifen")));
        try (FileChannel holder = FileChannel.open(file, StandardOpenOption.WRITE);
             FileLock lock = holder.lock()) {
            assertTrue(FileAccessUtils.isLockFailure(wrapped, file.toString()));
        }
    }

    @Test
    void isLockFailure_ioErrorOnFreeFile_isFalse() throws Exception {
        Path file = newFile();
        Throwable diskFull = new RuntimeException("Failed to save CSV", new IOException("No space left on device"));
        assertFalse(FileAccessUtils.isLockFailure(diskFull, file.toString()));
    }

    @Test
    void isLockFailure_nonIoErrorOnLockedFile_isFalse() throws Exception {
        Path file = newFile();
        try (FileChannel holder = FileChannel.open(file, StandardOpenOption.WRITE);
             FileLock lock = holder.lock()) {
            assertFalse(FileAccessUtils.isLockFailure(new IllegalStateException("bad data"), file.toString()));
        }
    }

    @Test
    void isLockFailure_englishWindowsWording_isStillRecognised() throws Exception {
        Path file = newFile();
        Throwable t = new UncheckedIOException(new IOException(
            "The process cannot access the file because it is being used by another process"));
        assertTrue(FileAccessUtils.isLockFailure(t, file.toString()));
    }
}
