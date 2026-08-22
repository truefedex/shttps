package com.phlox.simpleserver.channels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * The path half of the STATE protocol on its own: the escape rules of RFC 6901 and what walking a
 * document is allowed to create along the way.
 */
public class JsonPointerTest {
    @Test
    public void anEmptyOrAbsentPathIsTheRoot() throws Exception {
        JSONObject document = new JSONObject().put("players", new JSONObject());

        assertTrue(JsonPointer.parse("").isRoot());
        assertTrue(JsonPointer.parse(null).isRoot(), "an absent path field means the root");
        //a single leading slash is optional throughout, so "/" is the root written the other way
        assertTrue(JsonPointer.parse("/").isRoot());

        assertSame(document, JsonPointer.parse("").resolve(document));
        //the root has nothing above it: an operation that needs a parent has to refuse it
        assertThrows(ChannelCommandException.class,
                () -> JsonPointer.parse("").resolveParentCreating(document));
        assertThrows(IllegalStateException.class, () -> JsonPointer.parse("").lastToken());
    }

    @Test
    public void aLeadingSlashIsOptional() throws Exception {
        JSONObject document = new JSONObject(
                "{\"players\":{\"alice\":{\"score\":3}}}");

        assertEquals(3, ((Number) JsonPointer.parse("players/alice/score").resolve(document)).intValue());
        assertEquals(3, ((Number) JsonPointer.parse("/players/alice/score").resolve(document)).intValue());
        assertEquals(2, JsonPointer.parse("/players/alice").depth(),
                "the leading slash is a separator, not a segment of its own");
    }

    @Test
    public void createsTheMissingLevelsOfAPath() throws Exception {
        JSONObject document = new JSONObject();

        JsonPointer pointer = JsonPointer.parse("rooms/main/players/bob");
        Object parent = pointer.resolveParentCreating(document);
        JsonPointer.putChild(parent, pointer.lastToken(), new JSONObject().put("score", 0));

        assertEquals(0, document.getJSONObject("rooms").getJSONObject("main")
                .getJSONObject("players").getJSONObject("bob").getInt("score"));
    }

    @Test
    public void keepsTheLevelsItWalksThroughRatherThanReplacingThem() throws Exception {
        JSONObject document = new JSONObject("{\"players\":{\"alice\":{\"score\":3}}}");

        JsonPointer pointer = JsonPointer.parse("players/bob/score");
        JsonPointer.putChild(pointer.resolveParentCreating(document), pointer.lastToken(), 1);

        assertEquals(3, document.getJSONObject("players").getJSONObject("alice").getInt("score"),
                "creating a sibling must not disturb what was already there");
        assertEquals(1, document.getJSONObject("players").getJSONObject("bob").getInt("score"));
    }

    @Test
    public void refusesToRunAPathThroughAValue() throws Exception {
        JSONObject document = new JSONObject("{\"players\":{\"alice\":7}}");

        //7 is not a level of the document, and overwriting it would throw away a value nobody
        //asked to lose
        ChannelCommandException refusal = assertThrows(ChannelCommandException.class,
                () -> JsonPointer.parse("players/alice/score").resolveParentCreating(document));
        assertTrue(refusal.getMessage().contains("alice"), refusal.getMessage());
        assertEquals(7, document.getJSONObject("players").getInt("alice"));
    }

    @Test
    public void resolveParentCreatesNothing() throws Exception {
        JSONObject document = new JSONObject();

        assertNull(JsonPointer.parse("players/bob").resolveParent(document));
        assertNull(JsonPointer.parse("players/bob/score").resolve(document));
        assertEquals(0, document.length(),
                "a read-only walk must leave no empty objects behind - that is what makes a delete " +
                        "of an absent path a real no-op");
    }

    @Test
    public void unescapesTheTwoEscapeSequences() throws Exception {
        JSONObject document = new JSONObject();
        document.put("a/b", "slash");
        document.put("a~b", "tilde");
        document.put("~1", "escaped-looking key");

        assertEquals("slash", JsonPointer.parse("a~1b").resolve(document));
        assertEquals("tilde", JsonPointer.parse("a~0b").resolve(document));
        //~01 is ~0 followed by a literal "1", not ~1: taking them in the other order would make
        //this key unaddressable
        assertEquals("escaped-looking key", JsonPointer.parse("~01").resolve(document));
        assertEquals("a/b", JsonPointer.parse("a~1b").lastToken());
        assertEquals("a~b", JsonPointer.parse("a~0b").lastToken());
    }

    @Test
    public void refusesABrokenEscapeSequence() {
        assertThrows(ChannelCommandException.class, () -> JsonPointer.parse("a~2b"));
        assertThrows(ChannelCommandException.class, () -> JsonPointer.parse("trailing~"));
    }

    @Test
    public void walksIntoAnExistingArrayByIndex() throws Exception {
        JSONObject document = new JSONObject("{\"log\":[{\"text\":\"first\"},{\"text\":\"second\"}]}");

        assertEquals("second", JsonPointer.parse("log/1/text").resolve(document));
        //an index is only an index where an array already is; nothing here ever creates one
        assertNull(JsonPointer.parse("log/2").resolve(document), "past the end of the array");
        assertNull(JsonPointer.parse("log/first").resolve(document), "not an index at all");
        assertNull(JsonPointer.parse("log/01").resolve(document),
                "a padded index would make two different paths address one element");
        assertThrows(ChannelCommandException.class,
                () -> JsonPointer.parse("log/2/text").resolveParentCreating(document));
    }

    @Test
    public void writesIntoAnArrayOnlyWhereAnElementAlreadyIs() throws Exception {
        JSONObject document = new JSONObject("{\"log\":[\"first\"]}");
        JSONArray log = document.getJSONArray("log");

        JsonPointer.putChild(log, "0", "replaced");
        assertEquals("replaced", log.getString(0));

        //growing an array is push's job, and only at its end
        assertThrows(ChannelCommandException.class, () -> JsonPointer.putChild(log, "1", "appended"));
        assertThrows(ChannelCommandException.class, () -> JsonPointer.removeChild(log, "0"));
        assertEquals(1, log.length());
    }

    @Test
    public void removesAKeyAndShrugsAtAnAbsentOne() throws Exception {
        JSONObject document = new JSONObject("{\"players\":{\"bob\":{\"score\":0}}}");

        JsonPointer pointer = JsonPointer.parse("players/bob");
        JsonPointer.removeChild(pointer.resolveParent(document), pointer.lastToken());
        assertFalse(document.getJSONObject("players").has("bob"));

        JsonPointer.removeChild(document.getJSONObject("players"), "bob");
        assertEquals(0, document.getJSONObject("players").length());
    }

    @Test
    public void resolvesWhatPushNeedsToFindOrCreateAnArray() throws Exception {
        JSONObject document = new JSONObject();

        //the array is missing: the parent resolves (creating the levels above it) and the leaf is
        //simply not there yet, which is push's signal to create it
        JsonPointer missing = JsonPointer.parse("rooms/main/log");
        Object parent = missing.resolveParentCreating(document);
        assertNull(JsonPointer.child(parent, missing.lastToken()));
        JsonPointer.putChild(parent, missing.lastToken(), new JSONArray().put("entry"));
        assertEquals("entry", document.getJSONObject("rooms").getJSONObject("main")
                .getJSONArray("log").getString(0));

        //something else is already there: what push finds is not an array, and that is the whole
        //check it makes
        JSONObject occupied = new JSONObject("{\"log\":\"not an array\"}");
        JsonPointer taken = JsonPointer.parse("log");
        Object value = JsonPointer.child(taken.resolveParentCreating(occupied), taken.lastToken());
        assertFalse(value instanceof JSONArray);
        assertEquals("not an array", value);
    }
}
