package com.phlox.simpleserver.remotecontrol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

/**
 * The desktop needed a mouse, a wheel and real keys, which the touch protocol the Android build
 * speaks has none of. Those arrived as {@code default} methods so that an implementation written
 * before they existed - {@code RemoteInputInjector} in the Android app, which is not built here -
 * keeps compiling and keeps behaving exactly as it did.
 * <p>
 * {@link TouchOnlyTarget} below is that implementation in miniature: it implements only the seven
 * methods the interface had originally. If someone makes one of the new methods abstract, this
 * class stops compiling, which is the point of it.
 */
public class RemoteInputTargetDefaultsTest {

    /** Implements exactly what {@code RemoteInputTarget} required before the desktop existed. */
    private static final class TouchOnlyTarget implements RemoteInputTarget {
        @Override
        public boolean touchDown(Object client, float normalizedX, float normalizedY) {
            return true;
        }

        @Override
        public void touchMove(Object client, float normalizedX, float normalizedY) {}

        @Override
        public void touchUp(Object client, float normalizedX, float normalizedY, boolean cancel) {}

        @Override
        public void performGlobalAction(RemoteInputCommand.GlobalAction action) {}

        @Override
        public TextResult inputText(String text) {
            return TextResult.OK;
        }

        @Override
        public TextResult backspace() {
            return TextResult.OK;
        }

        @Override
        public TextResult enter() {
            return TextResult.OK;
        }

        @Override
        public void releaseClient(Object client) {}
    }

    @Test
    public void aTouchOnlyTargetAdvertisesOnlyTheTouchFamilies() {
        //what the page uses to decide it may show Back and Home and must not show a scroll wheel
        assertEquals(
                EnumSet.of(RemoteInputTarget.InputFamily.TOUCH,
                        RemoteInputTarget.InputFamily.GLOBAL_ACTION,
                        RemoteInputTarget.InputFamily.TEXT,
                        RemoteInputTarget.InputFamily.EDIT),
                new TouchOnlyTarget().inputFamilies());
    }

    @Test
    public void aTouchOnlyTargetReportsEveryDesktopEventUnsupported() {
        TouchOnlyTarget target = new TouchOnlyTarget();
        Object client = new Object();

        assertEquals(RemoteInputTarget.InputResult.UNSUPPORTED,
                target.pointerMove(client, 0.5f, 0.5f));
        assertEquals(RemoteInputTarget.InputResult.UNSUPPORTED,
                target.pointerDown(client, RemoteInputCommand.PointerButton.LEFT, 0.5f, 0.5f));
        assertEquals(RemoteInputTarget.InputResult.UNSUPPORTED,
                target.pointerUp(client, RemoteInputCommand.PointerButton.LEFT, true, 0.5f, 0.5f,
                        false));
        assertEquals(RemoteInputTarget.InputResult.UNSUPPORTED,
                target.scroll(client, 0f, -3f, true, 0.5f, 0.5f));
        assertEquals(RemoteInputTarget.InputResult.UNSUPPORTED,
                target.keyEvent(client, true, "KeyA"));
        assertEquals(RemoteInputTarget.InputResult.UNSUPPORTED,
                target.releaseHeldKeys(client));
    }

    @Test
    public void everyFamilyHasTheWireNameTheClientMatchesOn() {
        //these strings are the "type" field of the command they permit, and the page keys its UI
        //on exactly them - renaming one silently switches a piece of the toolbar off
        assertEquals("touch", RemoteInputTarget.InputFamily.TOUCH.wireName());
        assertEquals("key", RemoteInputTarget.InputFamily.GLOBAL_ACTION.wireName());
        assertEquals("text", RemoteInputTarget.InputFamily.TEXT.wireName());
        assertEquals("edit", RemoteInputTarget.InputFamily.EDIT.wireName());
        assertEquals("mouse", RemoteInputTarget.InputFamily.POINTER.wireName());
        assertEquals("wheel", RemoteInputTarget.InputFamily.WHEEL.wireName());
        assertEquals("keyboard", RemoteInputTarget.InputFamily.KEYBOARD.wireName());
    }
}
