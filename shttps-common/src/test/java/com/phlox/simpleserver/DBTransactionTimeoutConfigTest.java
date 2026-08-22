package com.phlox.simpleserver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.phlox.simpleserver.database.TestDBEnvironment;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * The two {@code /api/db/transaction} timeouts are the cap on how long one client may hold the
 * database's single write thread, so their bounds are a safety property rather than a preference:
 * whatever a user types into the settings dialog, or a hand-edited configuration file carries, the
 * value actually in force has to stay inside {@code [DB_TRANSACTION_TIMEOUT_MIN_MS,
 * DB_TRANSACTION_TIMEOUT_MAX_MS]}.
 * <p>
 * Clamping happens on read, not on write, which is what lets the UI display the number that is
 * really in effect by calling {@link SHTTPSConfig#clampDBTransactionTimeout} itself. These tests
 * pin that arrangement, and pin the two keys into config export/import - settings that are only
 * reachable from a dialog are exactly the ones that go missing from {@code serializeAll()}
 * unnoticed.
 */
public class DBTransactionTimeoutConfigTest {

    @Test
    public void defaultsAreTheDocumentedOnes() {
        SHTTPSConfig config = new TestDBEnvironment.Config();

        assertEquals(5000, config.getDBTransactionMaxLifetimeMillis());
        assertEquals(2000, config.getDBTransactionInactivityTimeoutMillis());
    }

    @Test
    public void valuesInsideTheRangeArePassedThrough() {
        SHTTPSConfig config = new TestDBEnvironment.Config();

        config.setDBTransactionMaxLifetimeMillis(30_000);
        config.setDBTransactionInactivityTimeoutMillis(1500);

        assertEquals(30_000, config.getDBTransactionMaxLifetimeMillis());
        assertEquals(1500, config.getDBTransactionInactivityTimeoutMillis());
    }

    @Test
    public void bothBoundsAreThemselvesValid() {
        SHTTPSConfig config = new TestDBEnvironment.Config();

        config.setDBTransactionMaxLifetimeMillis(SHTTPSConfig.DB_TRANSACTION_TIMEOUT_MIN_MS);
        config.setDBTransactionInactivityTimeoutMillis(SHTTPSConfig.DB_TRANSACTION_TIMEOUT_MAX_MS);

        assertEquals(SHTTPSConfig.DB_TRANSACTION_TIMEOUT_MIN_MS, config.getDBTransactionMaxLifetimeMillis());
        assertEquals(SHTTPSConfig.DB_TRANSACTION_TIMEOUT_MAX_MS, config.getDBTransactionInactivityTimeoutMillis());
    }

    @Test
    public void outOfRangeValuesReadBackClamped() {
        SHTTPSConfig config = new TestDBEnvironment.Config();

        config.setDBTransactionMaxLifetimeMillis(10);
        config.setDBTransactionInactivityTimeoutMillis(999_999);

        assertEquals(SHTTPSConfig.DB_TRANSACTION_TIMEOUT_MIN_MS, config.getDBTransactionMaxLifetimeMillis(),
                "a value below the floor must not shorten a transaction past what a client can use");
        assertEquals(SHTTPSConfig.DB_TRANSACTION_TIMEOUT_MAX_MS, config.getDBTransactionInactivityTimeoutMillis(),
                "a value above the ceiling must not produce an effectively unbounded transaction");

        //negative and zero are the shapes a mis-parsed or empty field would produce
        config.setDBTransactionMaxLifetimeMillis(0);
        config.setDBTransactionInactivityTimeoutMillis(-1);

        assertEquals(SHTTPSConfig.DB_TRANSACTION_TIMEOUT_MIN_MS, config.getDBTransactionMaxLifetimeMillis());
        assertEquals(SHTTPSConfig.DB_TRANSACTION_TIMEOUT_MIN_MS, config.getDBTransactionInactivityTimeoutMillis());
    }

    @Test
    public void clampHelperAgreesWithTheGetters() {
        assertEquals(SHTTPSConfig.DB_TRANSACTION_TIMEOUT_MIN_MS, SHTTPSConfig.clampDBTransactionTimeout(0));
        assertEquals(SHTTPSConfig.DB_TRANSACTION_TIMEOUT_MAX_MS, SHTTPSConfig.clampDBTransactionTimeout(Integer.MAX_VALUE));
        assertEquals(4321, SHTTPSConfig.clampDBTransactionTimeout(4321));
    }

    @Test
    public void bothSettingsSurviveExportAndImport() {
        SHTTPSConfig source = new TestDBEnvironment.Config();
        //in-range values, so this checks the export/import wiring rather than the clamp: export
        //goes through the clamping getter while import goes through the non-clamping setter, and
        //an out-of-range value would come back changed for that reason alone
        source.setDBTransactionMaxLifetimeMillis(12_345);
        source.setDBTransactionInactivityTimeoutMillis(678);

        JSONObject exported = source.serializeAll();

        SHTTPSConfig restored = new TestDBEnvironment.Config();
        restored.applyAll(exported);

        assertEquals(12_345, restored.getDBTransactionMaxLifetimeMillis());
        assertEquals(678, restored.getDBTransactionInactivityTimeoutMillis());
    }
}
