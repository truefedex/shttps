package com.phlox.simpleserver.handlers.files;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class MoveDestinationTest {

    @Test
    public void aDirectoryCannotBeCopiedIntoItself() {
        assertTrue(MoveFileRequestHandler.destinationIsInsideSource("/photos", "/photos"));
        assertTrue(MoveFileRequestHandler.destinationIsInsideSource("/photos", "/photos/backup"));
        assertTrue(MoveFileRequestHandler.destinationIsInsideSource("/photos/", "/photos/backup/"));
    }

    @Test
    public void aSharedPrefixIsNotTheSameDirectory() {
        assertFalse(MoveFileRequestHandler.destinationIsInsideSource("/photo", "/photos"));
        assertFalse(MoveFileRequestHandler.destinationIsInsideSource("/photos/a.jpg", "/photos"));
    }
}
