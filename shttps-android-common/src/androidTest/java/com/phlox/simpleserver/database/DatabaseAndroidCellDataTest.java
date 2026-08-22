package com.phlox.simpleserver.database;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Reading a single cell as a stream.
 * <p>
 * This is the endpoint behind the "BLOB" links in the web database browser, and the one place the
 * two platform implementations used to disagree: a row that existed but held nothing came back as
 * "not found" here and as "no content" on the desktop. They now agree on "no content", and these
 * tests pin that down along with the chunked reader that large values are pulled through.
 */
@RunWith(AndroidJUnit4.class)
public class DatabaseAndroidCellDataTest {
    private static final int TIMEOUT_MS = 30_000;
    /** The reader fetches a value in 8 KB pieces, to stay under Android's cursor window limit. */
    private static final int CHUNK_SIZE = 8 * 1024;

    private File dir;
    private DatabaseAndroid db;

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        dir = new File(context.getCacheDir(), "dbcell-" + System.nanoTime());
        assertTrue(dir.mkdirs());
        db = new DatabaseAndroid(context, dir, "test.db");
        db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, label TEXT, body TEXT, blob_body BLOB)");
    }

    @After
    public void tearDown() throws Exception {
        if (db != null) db.close();
        deleteRecursively(dir);
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private Database.CellDataStreamInfo readCell(String column, long id) throws Exception {
        List<String> filters = Collections.singletonList("id=");
        List<Object> args = Collections.singletonList(id);
        return db.getSingleCellDataStream("t", column, filters, args);
    }

    private static byte[] drain(InputStream input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    @Test(timeout = TIMEOUT_MS)
    public void readsAShortTextValue() throws Exception {
        db.insert("t", new JSONObject().put("id", 1).put("body", "hello"));

        Database.CellDataStreamInfo cell = readCell("body", 1);
        assertNotNull(cell);
        assertEquals("text", cell.type);
        assertEquals("text/plain", cell.mimeType);
        assertEquals(5, cell.length);
        try (InputStream input = cell.inputStream) {
            assertEquals("hello", new String(drain(input), StandardCharsets.UTF_8));
        }
    }

    /**
     * A value bigger than one chunk is fetched in several passes, so this is what proves the reader
     * stitches them back together in the right order and stops at the end.
     */
    @Test(timeout = TIMEOUT_MS)
    public void readsAValueLargerThanOneChunkInFull() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; sb.length() < CHUNK_SIZE * 3 + 137; i++) {
            sb.append("line ").append(i).append(" of a long value\n");
        }
        String expected = sb.toString();
        db.insert("t", new JSONObject().put("id", 1).put("body", expected));

        Database.CellDataStreamInfo cell = readCell("body", 1);
        assertNotNull(cell);
        assertEquals(expected.length(), cell.length);
        try (InputStream input = cell.inputStream) {
            String actual = new String(drain(input), StandardCharsets.UTF_8);
            assertEquals("the value came back truncated or reordered", expected.length(), actual.length());
            assertEquals(expected, actual);
        }
    }

    @Test(timeout = TIMEOUT_MS)
    public void readsABlobLargerThanOneChunkInFull() throws Exception {
        byte[] expected = new byte[CHUNK_SIZE * 2 + 999];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (byte) (i % 251);//not a round number, so a misaligned chunk shows up
        }
        db.insert("t", new JSONObject().put("id", 1).put("blob_body", new JSONObject()
                .put("type", "blob")
                .put("value", Base64.getEncoder().encodeToString(expected))));

        Database.CellDataStreamInfo cell = readCell("blob_body", 1);
        assertNotNull(cell);
        assertEquals("blob", cell.type);
        assertEquals("application/octet-stream", cell.mimeType);
        assertEquals(expected.length, cell.length);
        try (InputStream input = cell.inputStream) {
            assertArrayEquals(expected, drain(input));
        }
    }

    /**
     * A row that exists with an empty cell is "no content", not "not found". Returning null here
     * used to make the endpoint answer 404, which claimed the row was missing.
     */
    @Test(timeout = TIMEOUT_MS)
    public void aNullCellIsReportedAsAnEmptyValueRatherThanAMissingRow() throws Exception {
        db.insert("t", new JSONObject().put("id", 1).put("label", "row exists"));

        Database.CellDataStreamInfo cell = readCell("body", 1);
        assertNotNull("a null cell must not be reported as a missing row", cell);
        assertNull("there is nothing to stream", cell.inputStream);
        assertEquals("null", cell.type);
    }

    /** No row at all is the one case that genuinely means "not found". */
    @Test(timeout = TIMEOUT_MS)
    public void aMissingRowIsReportedAsNothingAtAll() throws Exception {
        db.insert("t", new JSONObject().put("id", 1).put("body", "hello"));
        assertNull(readCell("body", 99999));
    }

    /** An empty string is a value, not an absence - it streams as zero bytes. */
    @Test(timeout = TIMEOUT_MS)
    public void anEmptyTextValueStreamsAsNoBytes() throws Exception {
        db.insert("t", new JSONObject().put("id", 1).put("body", ""));

        Database.CellDataStreamInfo cell = readCell("body", 1);
        assertNotNull(cell);
        assertEquals("text", cell.type);
        assertEquals(0, cell.length);
        try (InputStream input = cell.inputStream) {
            assertEquals(0, drain(input).length);
        }
    }

    /**
     * Text is fetched a number of characters at a time but handed out as UTF-8 bytes, so a value
     * where the two differ - and one that straddles a chunk boundary - is where that bookkeeping
     * would go wrong. Astral characters count as one to sqlite and two to a Java string.
     */
    @Test(timeout = TIMEOUT_MS)
    public void readsMultiByteTextAcrossChunkBoundariesIntact() throws Exception {
        StringBuilder sb = new StringBuilder();
        while (sb.codePointCount(0, sb.length()) < CHUNK_SIZE * 2 + 61) {
            sb.append("héllo wörld ").append("😀").append(" καλημέρα ");
        }
        String expected = sb.toString();
        db.insert("t", new JSONObject().put("id", 1).put("body", expected));

        Database.CellDataStreamInfo cell = readCell("body", 1);
        assertNotNull(cell);
        try (InputStream input = cell.inputStream) {
            assertEquals(expected, new String(drain(input), StandardCharsets.UTF_8));
        }
    }

    /**
     * The reported length is what becomes the response's Content-Length, so it has to be the number
     * of bytes the stream will produce - not the number of characters, which is what sqlite's
     * length() counts for text and which is smaller for anything outside ASCII.
     */
    @Test(timeout = TIMEOUT_MS)
    public void theReportedLengthOfTextIsInBytesNotCharacters() throws Exception {
        String value = "héllo 😀 καλημέρα";
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        assertTrue("the test value must be one where the two differ", bytes > value.length());
        db.insert("t", new JSONObject().put("id", 1).put("body", value));

        Database.CellDataStreamInfo cell = readCell("body", 1);
        assertNotNull(cell);
        assertEquals("the length must match what the stream actually produces", bytes, cell.length);
        try (InputStream input = cell.inputStream) {
            assertEquals(bytes, drain(input).length);
        }
    }

    @Test(timeout = TIMEOUT_MS)
    public void theReportedLengthOfABlobIsStillItsByteCount() throws Exception {
        byte[] value = new byte[1234];
        for (int i = 0; i < value.length; i++) value[i] = (byte) i;
        db.insert("t", new JSONObject().put("id", 1).put("blob_body", new JSONObject()
                .put("type", "blob")
                .put("value", Base64.getEncoder().encodeToString(value))));

        Database.CellDataStreamInfo cell = readCell("blob_body", 1);
        assertNotNull(cell);
        assertEquals(value.length, cell.length);
    }

    @Test(timeout = TIMEOUT_MS)
    public void aTextValueIsNotPaddedWithAStrayZeroByte() throws Exception {
        //the cursor terminates strings with a zero byte; it must not reach the client
        db.insert("t", new JSONObject().put("id", 1).put("body", "abc"));

        Database.CellDataStreamInfo cell = readCell("body", 1);
        assertNotNull(cell);
        try (InputStream input = cell.inputStream) {
            assertArrayEquals(new byte[]{'a', 'b', 'c'}, drain(input));
        }
    }

    @Test(timeout = TIMEOUT_MS)
    public void aColumnHoldingSomethingOtherThanTextOrBlobIsRefused() throws Exception {
        db.insert("t", new JSONObject().put("id", 1).put("label", 42));
        try {
            readCell("id", 1);
            fail("an integer cell can not be streamed");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Invalid column type"));
        }
    }

    @Test(timeout = TIMEOUT_MS)
    public void identifiersThatAreNotValidNamesAreRefused() {
        try {
            db.getSingleCellDataStream("t; DROP TABLE t", "body", null, null);
            fail("an invalid table name must be refused");
        } catch (Exception e) {
            assertTrue(e instanceof SecurityException);
        }
        try {
            db.getSingleCellDataStream("t", "body = 1 OR 1", null, null);
            fail("an invalid column name must be refused");
        } catch (Exception e) {
            assertTrue(e instanceof SecurityException);
        }
    }
}
