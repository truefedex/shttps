package com.phlox.simpleserver.remotecontrol;

import java.util.EnumSet;

/**
 * Somewhere a {@link RemoteInputCommand} can be carried out - the device whose screen is being
 * shared, driven by whatever input mechanism that platform offers. On Android that is an
 * accessibility service dispatching gestures; on the desktop it is synthesized mouse and keyboard
 * events.
 * <p>
 * The methods take an opaque {@code client} token rather than anything typed. It is only ever
 * compared by identity: it says which connection is currently holding the device, so that a second
 * client can be turned away while one is mid-stroke, and so that a client which disappears without
 * lifting its finger - or its Ctrl key - can be cleaned up in {@link #releaseClient}.
 * <p>
 * Coordinates are normalized to {@code 0..1} of the shared screen. The client sees a downscaled -
 * and possibly rotated - video and cannot know the real resolution, so the mapping to pixels is
 * the implementation's job, against the display size read at injection time.
 * <p>
 * An implementation exists only while remote control is actually available: the supplier the
 * handler holds returns null whenever the option is switched off or the platform's input
 * permission has been revoked, which is re-checked for every message.
 * <p>
 * Everything below {@link #inputFamilies()} is a {@code default} method. A touch-only
 * implementation written before the desktop existed keeps compiling and keeps advertising exactly
 * what it always did; the client is told which families it may use and never sends the others.
 */
public interface RemoteInputTarget {
    /** How an attempt to put text into the focused field turned out. */
    enum TextResult {
        OK,
        /** Nothing editable has input focus, so there is nowhere to put the text. */
        NO_TEXT_FIELD,
        /** The focused app refused the edit. */
        FAILED,
        /** The platform offers no way to perform this edit at all. */
        UNSUPPORTED,
        /**
         * The operating system has not given this process permission to inject input at all.
         * The twin of {@link InputResult#NO_PERMISSION}, and it matters on this path too: typing
         * from a phone's on-screen keyboard is the one kind of remote control that never touches
         * {@link InputResult}.
         */
        NO_PERMISSION
    }

    /** How an attempt to carry out a pointer, wheel or key event turned out. */
    enum InputResult {
        OK,
        /** Another client is holding the device, so this one gets no turn. */
        BUSY,
        /** This target does not carry out that kind of event at all. */
        UNSUPPORTED,
        /**
         * The operating system has not given this process permission to inject input at all -
         * on macOS the Accessibility privacy grant, which the user has to make in System Settings.
         *
         * Separate from {@link #FAILED} because the two need different things said to whoever is
         * sitting in the browser: one is a window that could not be reached this time, the other
         * is a switch nobody has turned on yet, and only the second is worth walking to the
         * machine for.
         */
        NO_PERMISSION,
        /** The platform refused it - on Windows typically a window running elevated. */
        FAILED
    }

    /** A kind of command, named the way it appears in the {@code type} field on the wire. */
    enum InputFamily {
        TOUCH("touch"),
        GLOBAL_ACTION("key"),
        TEXT("text"),
        EDIT("edit"),
        POINTER("mouse"),
        WHEEL("wheel"),
        KEYBOARD("keyboard");

        private final String wireName;

        InputFamily(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    /**
     * Which command families this target actually carries out, so that the page offers a phone's
     * Back button or a PC's scroll wheel and not the other way round.
     * <p>
     * The default is what an accessibility injector does, so an implementation written before this
     * method existed keeps advertising exactly what it always did.
     */
    default EnumSet<InputFamily> inputFamilies() {
        return EnumSet.of(InputFamily.TOUCH, InputFamily.GLOBAL_ACTION,
                InputFamily.TEXT, InputFamily.EDIT);
    }

    /**
     * Starts a stroke and claims the device for {@code client}.
     *
     * @return false if another client is already holding it, which the caller reports as "busy"
     */
    boolean touchDown(Object client, float normalizedX, float normalizedY);

    /** Continues the stroke. Ignored unless {@code client} is the current owner. */
    void touchMove(Object client, float normalizedX, float normalizedY);

    /** Ends the stroke and releases the device. {@code cancel} abandons it instead of completing it. */
    void touchUp(Object client, float normalizedX, float normalizedY, boolean cancel);

    /** Performs a system-level action such as Back or Home. */
    void performGlobalAction(RemoteInputCommand.GlobalAction action);

    /** Types text into whatever currently has input focus. */
    TextResult inputText(String text);

    /** Deletes the character before the caret. */
    TextResult backspace();

    /** Acts on the focused field the way the Enter key would. */
    TextResult enter();

    /**
     * Moves the pointer, pressing nothing.
     * <p>
     * Alone among these, a move does <b>not</b> claim the device, and a move from a client that is
     * not the owner is dropped and answered {@link InputResult#OK}. A desktop needs continuous
     * hovering for tooltips and hover states, so if a move claimed the device then whoever wiggled
     * their mouse would take control from whoever was working - and answering {@code BUSY} instead
     * would send the loser a status frame per animation frame.
     */
    default InputResult pointerMove(Object client, float normalizedX, float normalizedY) {
        return InputResult.UNSUPPORTED;
    }

    /** Presses a mouse button at the given position and claims the device for {@code client}. */
    default InputResult pointerDown(Object client, RemoteInputCommand.PointerButton button,
                                    float normalizedX, float normalizedY) {
        return InputResult.UNSUPPORTED;
    }

    /**
     * Releases a mouse button. {@code hasPosition} is false when the client did not say where -
     * the release then happens wherever the pointer already is.
     */
    default InputResult pointerUp(Object client, RemoteInputCommand.PointerButton button,
                                  boolean hasPosition, float normalizedX, float normalizedY,
                                  boolean cancel) {
        return InputResult.UNSUPPORTED;
    }

    /**
     * Scrolls, in wheel notches, using the sign the DOM uses: a positive {@code notchesY} scrolls
     * the content down. When {@code hasPosition} is true the pointer moves there first, because a
     * desktop delivers the wheel to whatever sits under the cursor.
     */
    default InputResult scroll(Object client, float notchesX, float notchesY,
                               boolean hasPosition, float normalizedX, float normalizedY) {
        return InputResult.UNSUPPORTED;
    }

    /**
     * Presses or releases one physical key, named by its {@code KeyboardEvent.code}. Claims the
     * device: a keystroke with no button held is still input.
     */
    default InputResult keyEvent(Object client, boolean down, String code) {
        return InputResult.UNSUPPORTED;
    }

    /**
     * Releases every key and button {@code client} is holding, without giving up its turn. Sent by
     * a page that lost focus, so that a modifier held when the tab went away does not stay pressed
     * on the remote machine. A call from a client that is not the owner does nothing.
     */
    default InputResult releaseHeldKeys(Object client) {
        return InputResult.UNSUPPORTED;
    }

    /**
     * Drops anything {@code client} was holding. Called when a connection closes, so that a client
     * that vanished mid-stroke does not leave a finger pressed - or a Ctrl key held, which on a
     * desktop is enough to make the machine unusable - and lock everyone else out.
     */
    void releaseClient(Object client);
}
