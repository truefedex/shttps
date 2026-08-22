package com.phlox.simpleserver.database;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.phlox.simpleserver.database.model.TableData;
import com.phlox.simpleserver.utils.Holder;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Where-clause filters, and above all the ones that bind more than one argument.
 * <p>
 * The filter grammar (see {@code DBUtils.buildSimpleWhereStatement}) lets a single clause carry
 * several arguments: {@code name∈3} becomes {@code name IN (?,?,?)}. Update and delete used to size
 * their bind-argument array by the number of <i>clauses</i> rather than arguments, so any such
 * filter left the statement short of arguments and the platform rejected it - which is how the web
 * browser deletes several selected rows at once.
 */
@RunWith(AndroidJUnit4.class)
public class DatabaseAndroidFilterTest {
    private static final int TIMEOUT_MS = 30_000;

    private File dir;
    private DatabaseAndroid db;

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        dir = new File(context.getCacheDir(), "dbfilter-" + System.nanoTime());
        assertTrue(dir.mkdirs());
        db = new DatabaseAndroid(context, dir, "test.db");
        db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT, dept TEXT)");
        for (int i = 1; i <= 5; i++) {
            db.insert("t", new JSONObject()
                    .put("id", i)
                    .put("name", "Person " + i)
                    .put("dept", i % 2 == 0 ? "eng" : "sales"));
        }
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

    private List<Long> remainingIds() throws Exception {
        List<Long> ids = new ArrayList<>();
        try (TableData data = db.query("SELECT id FROM t ORDER BY id", null, false)) {
            while (data.next()) {
                ids.add(data.getLong(0));
            }
        }
        return ids;
    }

    private String nameOf(long id) throws Exception {
        try (TableData data = db.query("SELECT name FROM t WHERE id=" + id, null, false)) {
            return data.next() ? data.getString(0) : null;
        }
    }

    // ---- the bug ----

    /** One clause, three arguments: this is how several selected rows are deleted at once. */
    @Test(timeout = TIMEOUT_MS)
    public void deleteWithAnInFilterRemovesEveryNamedRow() throws Exception {
        int deleted = db.delete("t", new String[]{"id∈3"}, new Object[]{1, 3, 5});
        assertEquals(3, deleted);
        assertEquals(Arrays.asList(2L, 4L), remainingIds());
    }

    @Test(timeout = TIMEOUT_MS)
    public void updateWithAnInFilterChangesEveryNamedRow() throws Exception {
        int updated = db.update("t", new JSONObject().put("dept", "moved"),
                new String[]{"id∈2"}, new Object[]{2, 4});
        assertEquals(2, updated);
        try (TableData data = db.query("SELECT COUNT(*) FROM t WHERE dept='moved'", null, false)) {
            data.next();
            assertEquals(2, data.getLong(0));
        }
    }

    @Test(timeout = TIMEOUT_MS)
    public void deleteWithANotInFilterKeepsOnlyTheNamedRows() throws Exception {
        int deleted = db.delete("t", new String[]{"id∉2"}, new Object[]{1, 2});
        assertEquals(3, deleted);
        assertEquals(Arrays.asList(1L, 2L), remainingIds());
    }

    /** A multi-argument clause next to single-argument ones: the offsets have to line up. */
    @Test(timeout = TIMEOUT_MS)
    public void aMixOfSingleAndMultiArgumentClausesBindsInOrder() throws Exception {
        int deleted = db.delete("t", new String[]{"dept=", "id∈3"},
                new Object[]{"sales", 1, 2, 3});
        //ids 1 and 3 are sales, id 2 is eng and must survive
        assertEquals(2, deleted);
        assertEquals(Arrays.asList(2L, 4L, 5L), remainingIds());
    }

    @Test(timeout = TIMEOUT_MS)
    public void anInFilterOfOneStillWorks() throws Exception {
        assertEquals(1, db.delete("t", new String[]{"id∈1"}, new Object[]{3}));
        assertEquals(Arrays.asList(1L, 2L, 4L, 5L), remainingIds());
    }

    /** The read path always sized its arguments correctly; keep it that way. */
    @Test(timeout = TIMEOUT_MS)
    public void readingWithAnInFilterReturnsEveryNamedRow() throws Exception {
        try (TableData data = db.getTableDataSecure("t", new String[]{"id"}, null, null,
                new String[]{"id∈3"}, new Object[]{1, 3, 5}, "id", false, false, null)) {
            List<Long> ids = new ArrayList<>();
            while (data.next()) {
                ids.add(data.getLong(0));
            }
            assertEquals(Arrays.asList(1L, 3L, 5L), ids);
        }
    }

    /**
     * The out-count is the number of rows matching the filters, not the number on the page. It used
     * to be taken with the page window already in the query, and count(*) yields a single row that
     * any offset skips - so every page but the first reported a total of zero.
     */
    @Test(timeout = TIMEOUT_MS)
    public void theTotalIsTheUnpagedCount() throws Exception {
        for (long offset = 0; offset < 3; offset++) {
            Holder<Long> total = new Holder<>(0L);
            try (TableData data = db.getTableDataSecure("t", null, offset, 2L,
                    null, null, "id", false, false, total)) {
                int rows = 0;
                while (data.next()) rows++;
                assertEquals("wrong page size at offset " + offset, 2, rows);
            }
            assertEquals("wrong total at offset " + offset, Long.valueOf(5L), total.get());
        }
    }

    /** The same, with filters: the total counts everything the filters match. */
    @Test(timeout = TIMEOUT_MS)
    public void theTotalRespectsTheFilters() throws Exception {
        Holder<Long> total = new Holder<>(0L);
        try (TableData data = db.getTableDataSecure("t", null, 1L, 1L,
                new String[]{"dept="}, new Object[]{"eng"}, "id", false, false, total)) {
            while (data.next()) { /* drain */ }
        }
        //ids 2 and 4 are eng, so a page of one at offset one still reports both
        assertEquals(Long.valueOf(2L), total.get());
    }

    // ---- the ordinary cases, so the fix did not move them ----

    @Test(timeout = TIMEOUT_MS)
    public void singleArgumentFiltersStillWork() throws Exception {
        assertEquals(1, db.update("t", new JSONObject().put("name", "renamed"),
                new String[]{"id="}, new Object[]{2}));
        assertEquals("renamed", nameOf(2));

        assertEquals(1, db.delete("t", new String[]{"id="}, new Object[]{2}));
        assertEquals(Arrays.asList(1L, 3L, 4L, 5L), remainingIds());
    }

    @Test(timeout = TIMEOUT_MS)
    public void aLikeFilterStillWorks() throws Exception {
        assertEquals(5, db.delete("t", new String[]{"name?"}, new Object[]{"Person %"}));
        assertTrue(remainingIds().isEmpty());
    }

    @Test(timeout = TIMEOUT_MS)
    public void noFiltersMeansEveryRow() throws Exception {
        assertEquals(5, db.delete("t", null, null));
        assertTrue(remainingIds().isEmpty());
    }

    @Test(timeout = TIMEOUT_MS)
    public void anEmptyFilterArrayAlsoMeansEveryRow() throws Exception {
        assertEquals(5, db.delete("t", new String[0], new Object[0]));
        assertTrue(remainingIds().isEmpty());
    }

    /**
     * The platform binds where-arguments as strings and cannot express NULL, so this has to be
     * refused clearly rather than fail somewhere further down.
     */
    @Test(timeout = TIMEOUT_MS)
    public void aNullArgumentIsRefusedWithAnExplanation() {
        try {
            db.delete("t", new String[]{"name="}, new Object[]{null});
            fail("a null where-argument can not be bound");
        } catch (Exception e) {
            assertTrue(e.toString(), e instanceof IllegalArgumentException);
            assertTrue(e.getMessage(), e.getMessage().contains("null"));
        }
    }

    @Test(timeout = TIMEOUT_MS)
    public void anInvalidFilterIsRefused() {
        try {
            db.delete("t", new String[]{"name; DROP TABLE t"}, new Object[]{"x"});
            fail("an unparseable filter must be refused");
        } catch (Exception e) {
            assertTrue(e.toString(), e instanceof SecurityException);
        }
    }
}
