package com.example.termmgmt.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AtomicFileWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void write_createsFileWithFullContent_andLeavesNoTempFile() throws Exception {
        Path target = tempDir.resolve("terms.csv");
        AtomicFileWriter.write(target, out -> out.write("a,b\n".getBytes(StandardCharsets.UTF_8)));

        assertEquals("a,b\n", Files.readString(target, StandardCharsets.UTF_8));
        try (var listing = Files.list(tempDir)) {
            assertEquals(1, listing.count(), "temporary file must not survive a successful write");
        }
    }

    @Test
    void write_replacesExistingContent() throws Exception {
        Path target = tempDir.resolve("terms.csv");
        Files.writeString(target, "old content", StandardCharsets.UTF_8);

        AtomicFileWriter.write(target, out -> out.write("new content".getBytes(StandardCharsets.UTF_8)));

        assertEquals("new content", Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void write_failureHalfWay_keepsOriginalFileAndCleansUpTemp() throws Exception {
        Path target = tempDir.resolve("terms.csv");
        Files.writeString(target, "ORIGINAL", StandardCharsets.UTF_8);

        IOException thrown = assertThrows(IOException.class, () ->
            AtomicFileWriter.write(target, out -> {
                out.write("PARTIAL".getBytes(StandardCharsets.UTF_8));
                throw new IOException("simulated disk failure");
            }));
        assertEquals("simulated disk failure", thrown.getMessage());

        assertEquals("ORIGINAL", Files.readString(target, StandardCharsets.UTF_8),
            "a failed atomic write must leave the previous content intact");
        try (var listing = Files.list(tempDir)) {
            assertEquals(1, listing.count(), "the temporary file must be cleaned up after failure");
        }
    }

    @Test
    void write_failureBeforeTargetExists_doesNotCreateTargetFile() {
        Path target = tempDir.resolve("brand-new.csv");

        assertThrows(IOException.class, () ->
            AtomicFileWriter.write(target, out -> {
                throw new IOException("boom");
            }));

        assertFalse(Files.exists(target));
    }

    @Test
    void write_moveFailure_isReportedAsIoException_andLeavesNoTempFile() throws Exception {
        // Moving over a non-empty directory always fails (AccessDenied on Windows,
        // DirectoryNotEmptyException elsewhere): the error must surface and the temp
        // file must still be cleaned up. Cross-process lock detection itself is
        // covered by FileAccessUtilsTest; the handlers route that case via isLockFailure.
        Path target = tempDir.resolve("blocked.dir");
        Files.createDirectory(target);
        Files.writeString(target.resolve("inner.txt"), "x");

        assertThrows(IOException.class, () ->
            AtomicFileWriter.write(target, out -> out.write("data".getBytes(StandardCharsets.UTF_8))));

        try (var listing = Files.list(tempDir)) {
            assertEquals(1, listing.count(), "no temp file may survive a failed move");
        }
    }
}
