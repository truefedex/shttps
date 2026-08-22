package com.phlox.simpleserver.remotecontrol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Remote input arrives from whoever can reach the screen stream socket, so the parser has to turn
 * anything else into "no command" rather than into a touch somewhere unintended. The coordinate
 * math is here too: it decides where on the device a remote finger lands, and the system rejects a
 * gesture that reaches outside the display.
 */
public class RemoteInputCommandTest {

    @Test
    public void parsesTouchCommands() {
        RemoteInputCommand down = RemoteInputCommand.parse(
                "{\"type\":\"touch\",\"action\":\"down\",\"x\":0.25,\"y\":0.75}");
        assertNotNull(down);
        assertEquals(RemoteInputCommand.Type.TOUCH, down.type);
        assertEquals(RemoteInputCommand.TouchAction.DOWN, down.touchAction);
        assertEquals(0.25f, down.x, 0.0001f);
        assertEquals(0.75f, down.y, 0.0001f);

        assertEquals(RemoteInputCommand.TouchAction.MOVE, RemoteInputCommand.parse(
                "{\"type\":\"touch\",\"action\":\"move\",\"x\":0,\"y\":0}").touchAction);
        assertEquals(RemoteInputCommand.TouchAction.UP, RemoteInputCommand.parse(
                "{\"type\":\"touch\",\"action\":\"up\",\"x\":1,\"y\":1}").touchAction);
    }

    @Test
    public void touchCoordinatesAreClampedToThePicture() {
        //rounding on the client can put a touch at the very edge slightly outside
        RemoteInputCommand command = RemoteInputCommand.parse(
                "{\"type\":\"touch\",\"action\":\"move\",\"x\":1.0004,\"y\":-0.0003}");
        assertEquals(1f, command.x, 0.0001f);
        assertEquals(0f, command.y, 0.0001f);
    }

    @Test
    public void touchWithoutPositionIsOnlyAcceptedAsCancel() {
        assertNull(RemoteInputCommand.parse("{\"type\":\"touch\",\"action\":\"down\"}"));
        assertNull(RemoteInputCommand.parse("{\"type\":\"touch\",\"action\":\"move\",\"x\":0.5}"));
        //a cancel ends the stroke wherever it happens to be, so it needs no position
        assertEquals(RemoteInputCommand.TouchAction.CANCEL,
                RemoteInputCommand.parse("{\"type\":\"touch\",\"action\":\"cancel\"}").touchAction);
    }

    @Test
    public void parsesGlobalActions() {
        assertEquals(RemoteInputCommand.GlobalAction.BACK,
                RemoteInputCommand.parse("{\"type\":\"key\",\"action\":\"back\"}").globalAction);
        assertEquals(RemoteInputCommand.GlobalAction.HOME,
                RemoteInputCommand.parse("{\"type\":\"key\",\"action\":\"home\"}").globalAction);
        assertEquals(RemoteInputCommand.GlobalAction.RECENTS,
                RemoteInputCommand.parse("{\"type\":\"key\",\"action\":\"recents\"}").globalAction);
        assertEquals(RemoteInputCommand.GlobalAction.NOTIFICATIONS, RemoteInputCommand.parse(
                "{\"type\":\"key\",\"action\":\"notifications\"}").globalAction);
    }

    @Test
    public void actionsThatWouldEndTheCaptureSessionAreNotOffered() {
        //locking the screen stops the MediaProjection, so such a button would kill the stream it
        //is pressed through - these must not become commands even if a client asks for them
        assertNull(RemoteInputCommand.parse("{\"type\":\"key\",\"action\":\"lock_screen\"}"));
        assertNull(RemoteInputCommand.parse("{\"type\":\"key\",\"action\":\"power\"}"));
        assertNull(RemoteInputCommand.parse("{\"type\":\"key\",\"action\":\"take_screenshot\"}"));
    }

    @Test
    public void parsesTextAndEditing() {
        RemoteInputCommand text = RemoteInputCommand.parse("{\"type\":\"text\",\"text\":\"hello\"}");
        assertEquals(RemoteInputCommand.Type.TEXT, text.type);
        assertEquals("hello", text.text);

        assertEquals(RemoteInputCommand.EditAction.BACKSPACE, RemoteInputCommand.parse(
                "{\"type\":\"edit\",\"action\":\"backspace\"}").editAction);
        assertEquals(RemoteInputCommand.EditAction.ENTER, RemoteInputCommand.parse(
                "{\"type\":\"edit\",\"action\":\"enter\"}").editAction);
    }

    @Test
    public void parsesStatusRequest() {
        //a page that was told it may not control sends no input, so this is its only way back
        RemoteInputCommand status = RemoteInputCommand.parse("{\"type\":\"status\"}");
        assertNotNull(status);
        assertEquals(RemoteInputCommand.Type.STATUS, status.type);
    }

    @Test
    public void rejectsEmptyAndOversizedText() {
        assertNull(RemoteInputCommand.parse("{\"type\":\"text\",\"text\":\"\"}"));
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 1025; i++) {
            longText.append('a');
        }
        assertNull(RemoteInputCommand.parse("{\"type\":\"text\",\"text\":\"" + longText + "\"}"));
    }

    @Test
    public void rejectsAnythingThatIsNotACommand() {
        assertNull(RemoteInputCommand.parse(null));
        assertNull(RemoteInputCommand.parse(""));
        assertNull(RemoteInputCommand.parse("   "));
        //the plain text command the endpoint had before input existed is handled by the caller
        assertNull(RemoteInputCommand.parse("request-key-frame"));
        assertNull(RemoteInputCommand.parse("{ this is not json"));
        assertNull(RemoteInputCommand.parse("[{\"type\":\"touch\"}]"));
        assertNull(RemoteInputCommand.parse("{\"type\":\"unknown\"}"));
        assertNull(RemoteInputCommand.parse("{\"type\":\"touch\",\"action\":\"tap\",\"x\":0,\"y\":0}"));
        assertNull(RemoteInputCommand.parse("{\"type\":\"edit\",\"action\":\"delete-all\"}"));
    }

    @Test
    public void parsesMouseCommands() {
        RemoteInputCommand down = RemoteInputCommand.parse(
                "{\"type\":\"mouse\",\"action\":\"down\",\"button\":\"right\",\"x\":0.25,\"y\":0.75}");
        assertNotNull(down);
        assertEquals(RemoteInputCommand.Type.MOUSE, down.type);
        assertEquals(RemoteInputCommand.PointerAction.DOWN, down.pointerAction);
        assertEquals(RemoteInputCommand.PointerButton.RIGHT, down.pointerButton);
        assertEquals(0.25f, down.x, 0.0001f);
        assertEquals(0.75f, down.y, 0.0001f);
        assertTrue(down.hasPosition);

        assertEquals(RemoteInputCommand.PointerAction.MOVE, RemoteInputCommand.parse(
                "{\"type\":\"mouse\",\"action\":\"move\",\"x\":0,\"y\":0}").pointerAction);
        assertEquals(RemoteInputCommand.PointerButton.MIDDLE, RemoteInputCommand.parse(
                "{\"type\":\"mouse\",\"action\":\"up\",\"button\":\"middle\",\"x\":1,\"y\":1}").pointerButton);
        assertEquals(RemoteInputCommand.PointerButton.BACK, RemoteInputCommand.parse(
                "{\"type\":\"mouse\",\"action\":\"down\",\"button\":\"back\",\"x\":0,\"y\":0}").pointerButton);
    }

    @Test
    public void anUnnamedMouseButtonIsTheLeftOneButAnUnknownOneIsAnError() {
        assertEquals(RemoteInputCommand.PointerButton.LEFT, RemoteInputCommand.parse(
                "{\"type\":\"mouse\",\"action\":\"down\",\"x\":0.5,\"y\":0.5}").pointerButton);
        //silently turning a typo into a left click is how the wrong thing gets clicked
        assertNull(RemoteInputCommand.parse(
                "{\"type\":\"mouse\",\"action\":\"down\",\"button\":\"leftt\",\"x\":0.5,\"y\":0.5}"));
        assertNull(RemoteInputCommand.parse(
                "{\"type\":\"mouse\",\"action\":\"down\",\"button\":\"\",\"x\":0.5,\"y\":0.5}"));
        assertNull(RemoteInputCommand.parse(
                "{\"type\":\"mouse\",\"action\":\"drag\",\"x\":0.5,\"y\":0.5}"));
    }

    @Test
    public void onlyAMouseReleaseMayOmitItsPosition() {
        assertNull(RemoteInputCommand.parse("{\"type\":\"mouse\",\"action\":\"down\"}"));
        assertNull(RemoteInputCommand.parse("{\"type\":\"mouse\",\"action\":\"move\",\"x\":0.5}"));
        //a release or a cancel happens wherever the pointer already is
        RemoteInputCommand up = RemoteInputCommand.parse("{\"type\":\"mouse\",\"action\":\"up\"}");
        assertNotNull(up);
        assertFalse(up.hasPosition);
        assertFalse(RemoteInputCommand.parse("{\"type\":\"mouse\",\"action\":\"cancel\"}").hasPosition);
    }

    @Test
    public void parsesWheelCommands() {
        RemoteInputCommand wheel = RemoteInputCommand.parse(
                "{\"type\":\"wheel\",\"dx\":1.5,\"dy\":-3,\"x\":0.5,\"y\":0.25}");
        assertNotNull(wheel);
        assertEquals(RemoteInputCommand.Type.WHEEL, wheel.type);
        assertEquals(1.5f, wheel.wheelX, 0.0001f);
        assertEquals(-3f, wheel.wheelY, 0.0001f);
        assertTrue(wheel.hasPosition);
        assertEquals(0.5f, wheel.x, 0.0001f);

        //a wheel that says nothing about where it happened still scrolls, under the cursor
        RemoteInputCommand noPosition = RemoteInputCommand.parse("{\"type\":\"wheel\",\"dy\":1}");
        assertNotNull(noPosition);
        assertFalse(noPosition.hasPosition);
        assertEquals(0f, noPosition.wheelX, 0.0001f);
    }

    @Test
    public void rejectsWheelsThatWouldDoNothingOrCannotBeUnderstood() {
        assertNull(RemoteInputCommand.parse("{\"type\":\"wheel\"}"));
        assertNull(RemoteInputCommand.parse("{\"type\":\"wheel\",\"dx\":0,\"dy\":0}"));
        assertNull(RemoteInputCommand.parse("{\"type\":\"wheel\",\"dy\":\"lots\"}"));
    }

    @Test
    public void clampsWheelNotchesToOneGesture() {
        //a client reporting its deltas in some unit we did not anticipate must not scroll a
        //document to its end in a single frame
        assertEquals(20f, RemoteInputCommand.parse(
                "{\"type\":\"wheel\",\"dy\":9000}").wheelY, 0.0001f);
        assertEquals(-20f, RemoteInputCommand.parse(
                "{\"type\":\"wheel\",\"dx\":-9000,\"dy\":1}").wheelX, 0.0001f);
    }

    @Test
    public void parsesKeyboardCommands() {
        RemoteInputCommand down = RemoteInputCommand.parse(
                "{\"type\":\"keyboard\",\"action\":\"down\",\"code\":\"ControlLeft\"}");
        assertNotNull(down);
        assertEquals(RemoteInputCommand.Type.KEYBOARD, down.type);
        assertEquals(RemoteInputCommand.KeyAction.DOWN, down.keyAction);
        assertEquals("ControlLeft", down.keyCode);

        assertEquals(RemoteInputCommand.KeyAction.UP, RemoteInputCommand.parse(
                "{\"type\":\"keyboard\",\"action\":\"up\",\"code\":\"KeyC\"}").keyAction);
    }

    @Test
    public void aKeyboardResetNeedsNoCode() {
        //what a page sends when it loses focus, so a held modifier is not left pressed
        RemoteInputCommand reset =
                RemoteInputCommand.parse("{\"type\":\"keyboard\",\"action\":\"reset\"}");
        assertNotNull(reset);
        assertEquals(RemoteInputCommand.KeyAction.RESET, reset.keyAction);
        assertNull(reset.keyCode);
    }

    @Test
    public void rejectsKeyCodesThatAreNotOnes() {
        //every KeyboardEvent.code the DOM defines is alphanumeric, so nothing else has to be
        //considered by the platform key mappers below this
        assertNull(RemoteInputCommand.parse("{\"type\":\"keyboard\",\"action\":\"down\"}"));
        assertNull(RemoteInputCommand.parse(
                "{\"type\":\"keyboard\",\"action\":\"down\",\"code\":\"\"}"));
        assertNull(RemoteInputCommand.parse(
                "{\"type\":\"keyboard\",\"action\":\"down\",\"code\":\"Key A\"}"));
        assertNull(RemoteInputCommand.parse(
                "{\"type\":\"keyboard\",\"action\":\"down\",\"code\":\"KeyA-\"}"));
        assertNull(RemoteInputCommand.parse(
                "{\"type\":\"keyboard\",\"action\":\"down\",\"code\":\"KeyA_DROP\"}"));
        assertNull(RemoteInputCommand.parse(
                "{\"type\":\"keyboard\",\"action\":\"press\",\"code\":\"KeyA\"}"));

        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            tooLong.append('a');
        }
        assertNull(RemoteInputCommand.parse(
                "{\"type\":\"keyboard\",\"action\":\"down\",\"code\":\"" + tooLong + "\"}"));
    }

    @Test
    public void theTouchProtocolIsUnchangedByTheDesktopAdditions() {
        //the Android build speaks exactly these shapes and must keep working untouched
        assertNotNull(RemoteInputCommand.parse(
                "{\"type\":\"touch\",\"action\":\"down\",\"x\":0.1,\"y\":0.2}"));
        assertNotNull(RemoteInputCommand.parse(
                "{\"type\":\"touch\",\"action\":\"move\",\"x\":0.1,\"y\":0.2}"));
        assertNotNull(RemoteInputCommand.parse(
                "{\"type\":\"touch\",\"action\":\"up\",\"x\":0.1,\"y\":0.2}"));
        assertNotNull(RemoteInputCommand.parse("{\"type\":\"touch\",\"action\":\"cancel\"}"));
        assertNotNull(RemoteInputCommand.parse("{\"type\":\"key\",\"action\":\"back\"}"));
        assertNotNull(RemoteInputCommand.parse("{\"type\":\"text\",\"text\":\"hi\"}"));
        assertNotNull(RemoteInputCommand.parse("{\"type\":\"edit\",\"action\":\"enter\"}"));
        assertNotNull(RemoteInputCommand.parse("{\"type\":\"status\"}"));

        //a touch that carried coordinates is still distinguishable from one that did not
        assertTrue(RemoteInputCommand.parse(
                "{\"type\":\"touch\",\"action\":\"down\",\"x\":0,\"y\":0}").hasPosition);
        assertFalse(RemoteInputCommand.parse(
                "{\"type\":\"touch\",\"action\":\"cancel\"}").hasPosition);
    }

    @Test
    public void mapsNormalizedCoordinatesInsideTheScreen() {
        assertEquals(0f, RemoteInputCommand.toPixels(0f, 1080), 0.0001f);
        assertEquals(539.5f, RemoteInputCommand.toPixels(0.5f, 1080), 0.0001f);
        //the last pixel, never the exclusive edge: a gesture outside the display is rejected
        assertEquals(1079f, RemoteInputCommand.toPixels(1f, 1080), 0.0001f);
    }

    @Test
    public void coordinateMappingSurvivesDegenerateScreenSizes() {
        assertEquals(0f, RemoteInputCommand.toPixels(0.5f, 0), 0.0001f);
        assertEquals(0f, RemoteInputCommand.toPixels(0.5f, 1), 0.0001f);
    }
}
