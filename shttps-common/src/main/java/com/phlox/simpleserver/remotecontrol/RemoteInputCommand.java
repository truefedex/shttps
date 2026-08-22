package com.phlox.simpleserver.remotecontrol;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * One input command sent by a remote client over the screen stream WebSocket.
 * <p>
 * Commands are JSON text frames. The first four describe a touch screen and are what an Android
 * device is driven with:
 * <ul>
 *     <li>{@code {"type":"touch","action":"down|move|up|cancel","x":0.51,"y":0.32}}</li>
 *     <li>{@code {"type":"key","action":"back|home|recents|notifications"}}</li>
 *     <li>{@code {"type":"text","text":"hello"}}</li>
 *     <li>{@code {"type":"edit","action":"backspace|enter"}}</li>
 * </ul>
 * The rest describe a mouse and a keyboard, and are what a desktop is driven with:
 * <ul>
 *     <li>{@code {"type":"mouse","action":"move|down|up|cancel","button":"left","x":0.5,"y":0.3}}</li>
 *     <li>{@code {"type":"wheel","dx":0,"dy":-3,"x":0.5,"y":0.3}}</li>
 *     <li>{@code {"type":"keyboard","action":"down|up","code":"KeyA"}}</li>
 *     <li>{@code {"type":"keyboard","action":"reset"}}</li>
 * </ul>
 * The two sets are deliberately separate rather than one unified pointer command: the touch shapes
 * are what the Android build already speaks, and a client only sends what the target advertised it
 * accepts (see {@link RemoteInputTarget#inputFamilies()}).
 * <p>
 * Touch, mouse and wheel coordinates are normalized to {@code 0..1} of the shared screen. The
 * client can not know the device resolution - what it sees is a downscaled video that may also have
 * been re-sized by a rotation - so the mapping to pixels happens at the target, against the display
 * geometry read at injection time.
 * <p>
 * This class is deliberately free of Android types so that the parsing and the coordinate math can
 * be tested without a device.
 */
public class RemoteInputCommand {
    public enum Type {
        TOUCH, KEY, TEXT, EDIT,
        /**
         * A client asking whether it may control the device. Sent on its own initiative, because a
         * page that has been told "no" sends no input anymore and would otherwise never find out
         * that the user granted the service in the meantime.
         */
        STATUS,
        MOUSE, WHEEL, KEYBOARD
    }

    public enum TouchAction { DOWN, MOVE, UP, CANCEL }

    /** The system-level actions offered to a remote client. */
    public enum GlobalAction { BACK, HOME, RECENTS, NOTIFICATIONS }

    public enum EditAction { BACKSPACE, ENTER }

    public enum PointerAction { MOVE, DOWN, UP, CANCEL }

    public enum PointerButton { LEFT, RIGHT, MIDDLE, BACK, FORWARD }

    public enum KeyAction {
        DOWN, UP,
        /**
         * Release everything this client is holding, without giving up its turn. The page sends it
         * when it loses focus or keyboard capture, so that a modifier held while the tab went away
         * does not stay pressed on the remote machine.
         */
        RESET
    }

    /** Anything longer is a client error - a WebSocket message here is capped at 4 KB anyway. */
    private static final int MAX_TEXT_LENGTH = 1024;

    /**
     * A single wheel message is a gesture, not a journey. Clamping keeps a client that reports its
     * deltas in some unit we did not anticipate from scrolling a document to its end in one frame.
     */
    private static final int MAX_WHEEL_NOTCHES = 20;

    /**
     * Every {@code KeyboardEvent.code} the DOM defines is alphanumeric - {@code KeyA},
     * {@code Digit1}, {@code NumpadDecimal}, {@code IntlBackslash}, {@code F13}. Restricting the
     * field to that alphabet means neither this parser nor any platform key mapper below it ever
     * has to consider a hostile string.
     */
    private static final int MAX_KEY_CODE_LENGTH = 24;

    public final Type type;
    /** Only for {@link Type#TOUCH}. */
    public final TouchAction touchAction;
    /** Only for {@link Type#KEY}. */
    public final GlobalAction globalAction;
    /** Only for {@link Type#EDIT}. */
    public final EditAction editAction;
    /** Only for {@link Type#MOUSE}. */
    public final PointerAction pointerAction;
    /** Only for {@link Type#MOUSE}; a message that names no button means the left one. */
    public final PointerButton pointerButton;
    /** Only for {@link Type#KEYBOARD}. */
    public final KeyAction keyAction;
    /** A {@code KeyboardEvent.code}, only for {@link Type#KEYBOARD} down and up. */
    public final String keyCode;
    /** Normalized to 0..1, meaningful for {@link Type#TOUCH}, {@link Type#MOUSE}, {@link Type#WHEEL}. */
    public final float x;
    public final float y;
    /**
     * Whether {@link #x}/{@link #y} were actually carried. A release or a cancel may omit them - it
     * ends wherever the pointer happens to be - and without this flag the zeros left behind are
     * indistinguishable from a genuine touch in the top left corner.
     */
    public final boolean hasPosition;
    /** Wheel notches in DOM sign: positive dy scrolls the content down. Only for {@link Type#WHEEL}. */
    public final float wheelX;
    public final float wheelY;
    /** Only for {@link Type#TEXT}. */
    public final String text;

    private RemoteInputCommand(Type type, TouchAction touchAction, GlobalAction globalAction,
                               EditAction editAction, PointerAction pointerAction,
                               PointerButton pointerButton, KeyAction keyAction, String keyCode,
                               float x, float y, boolean hasPosition, float wheelX, float wheelY,
                               String text) {
        this.type = type;
        this.touchAction = touchAction;
        this.globalAction = globalAction;
        this.editAction = editAction;
        this.pointerAction = pointerAction;
        this.pointerButton = pointerButton;
        this.keyAction = keyAction;
        this.keyCode = keyCode;
        this.x = x;
        this.y = y;
        this.hasPosition = hasPosition;
        this.wheelX = wheelX;
        this.wheelY = wheelY;
        this.text = text;
    }

    private static RemoteInputCommand touch(TouchAction action, float x, float y, boolean hasPosition) {
        return new RemoteInputCommand(Type.TOUCH, action, null, null, null, null, null, null,
                x, y, hasPosition, 0, 0, null);
    }

    private static RemoteInputCommand key(GlobalAction action) {
        return new RemoteInputCommand(Type.KEY, null, action, null, null, null, null, null,
                0, 0, false, 0, 0, null);
    }

    private static RemoteInputCommand text(String text) {
        return new RemoteInputCommand(Type.TEXT, null, null, null, null, null, null, null,
                0, 0, false, 0, 0, text);
    }

    private static RemoteInputCommand edit(EditAction action) {
        return new RemoteInputCommand(Type.EDIT, null, null, action, null, null, null, null,
                0, 0, false, 0, 0, null);
    }

    private static RemoteInputCommand status() {
        return new RemoteInputCommand(Type.STATUS, null, null, null, null, null, null, null,
                0, 0, false, 0, 0, null);
    }

    private static RemoteInputCommand mouse(PointerAction action, PointerButton button,
                                            float x, float y, boolean hasPosition) {
        return new RemoteInputCommand(Type.MOUSE, null, null, null, action, button, null, null,
                x, y, hasPosition, 0, 0, null);
    }

    private static RemoteInputCommand wheel(float wheelX, float wheelY,
                                            float x, float y, boolean hasPosition) {
        return new RemoteInputCommand(Type.WHEEL, null, null, null, null, null, null, null,
                x, y, hasPosition, wheelX, wheelY, null);
    }

    private static RemoteInputCommand keyboard(KeyAction action, String code) {
        return new RemoteInputCommand(Type.KEYBOARD, null, null, null, null, null, action, code,
                0, 0, false, 0, 0, null);
    }

    /**
     * @return the parsed command, or null for anything that is not one - including the plain text
     * commands the stream endpoint had before input existed, which the caller handles itself
     */
    public static RemoteInputCommand parse(String message) {
        if (message == null) {
            return null;
        }
        String trimmed = message.trim();
        if (trimmed.isEmpty() || trimmed.charAt(0) != '{') {
            return null;
        }
        JSONObject json;
        try {
            json = new JSONObject(trimmed);
        } catch (JSONException e) {
            return null;
        }
        switch (json.optString("type", "")) {
            case "touch":
                return parseTouch(json);
            case "key": {
                GlobalAction action = parseGlobalAction(json.optString("action", ""));
                return action == null ? null : key(action);
            }
            case "text": {
                String text = json.optString("text", "");
                if (text.isEmpty() || text.length() > MAX_TEXT_LENGTH) {
                    return null;
                }
                return text(text);
            }
            case "edit": {
                EditAction action = parseEditAction(json.optString("action", ""));
                return action == null ? null : edit(action);
            }
            case "status":
                return status();
            case "mouse":
                return parseMouse(json);
            case "wheel":
                return parseWheel(json);
            case "keyboard":
                return parseKeyboard(json);
            default:
                return null;
        }
    }

    private static RemoteInputCommand parseTouch(JSONObject json) {
        TouchAction action = parseTouchAction(json.optString("action", ""));
        if (action == null) {
            return null;
        }
        double x = json.optDouble("x", Double.NaN);
        double y = json.optDouble("y", Double.NaN);
        if (Double.isNaN(x) || Double.isNaN(y)) {
            //a cancel carries no position: it ends wherever the stroke happens to be
            if (action != TouchAction.CANCEL) {
                return null;
            }
            return touch(action, 0, 0, false);
        }
        return touch(action, clamp01(x), clamp01(y), true);
    }

    private static RemoteInputCommand parseMouse(JSONObject json) {
        PointerAction action = parsePointerAction(json.optString("action", ""));
        if (action == null) {
            return null;
        }
        //an unnamed button is the left one, but a named one we do not recognise is an error:
        //silently turning someone's typo into a left click is how the wrong thing gets clicked
        PointerButton button = PointerButton.LEFT;
        if (json.has("button")) {
            button = parsePointerButton(json.optString("button", ""));
            if (button == null) {
                return null;
            }
        }
        double x = json.optDouble("x", Double.NaN);
        double y = json.optDouble("y", Double.NaN);
        if (Double.isNaN(x) || Double.isNaN(y)) {
            //a release or a cancel happens wherever the pointer already is; a move or a press has
            //to say where
            if (action != PointerAction.UP && action != PointerAction.CANCEL) {
                return null;
            }
            return mouse(action, button, 0, 0, false);
        }
        return mouse(action, button, clamp01(x), clamp01(y), true);
    }

    private static RemoteInputCommand parseWheel(JSONObject json) {
        double dx = json.optDouble("dx", 0);
        double dy = json.optDouble("dy", 0);
        if (!isFinite(dx) || !isFinite(dy)) {
            return null;
        }
        if (dx == 0 && dy == 0) {
            //nothing to do, and letting it through would claim the turn for no reason
            return null;
        }
        double x = json.optDouble("x", Double.NaN);
        double y = json.optDouble("y", Double.NaN);
        boolean hasPosition = !Double.isNaN(x) && !Double.isNaN(y);
        return wheel(clampNotches(dx), clampNotches(dy),
                hasPosition ? clamp01(x) : 0, hasPosition ? clamp01(y) : 0, hasPosition);
    }

    private static RemoteInputCommand parseKeyboard(JSONObject json) {
        KeyAction action = parseKeyAction(json.optString("action", ""));
        if (action == null) {
            return null;
        }
        if (action == KeyAction.RESET) {
            return keyboard(action, null);
        }
        String code = json.optString("code", "");
        return isKeyCode(code) ? keyboard(action, code) : null;
    }

    private static TouchAction parseTouchAction(String action) {
        switch (action) {
            case "down": return TouchAction.DOWN;
            case "move": return TouchAction.MOVE;
            case "up": return TouchAction.UP;
            case "cancel": return TouchAction.CANCEL;
            default: return null;
        }
    }

    private static PointerAction parsePointerAction(String action) {
        switch (action) {
            case "move": return PointerAction.MOVE;
            case "down": return PointerAction.DOWN;
            case "up": return PointerAction.UP;
            case "cancel": return PointerAction.CANCEL;
            default: return null;
        }
    }

    private static PointerButton parsePointerButton(String button) {
        switch (button) {
            case "left": return PointerButton.LEFT;
            case "right": return PointerButton.RIGHT;
            case "middle": return PointerButton.MIDDLE;
            case "back": return PointerButton.BACK;
            case "forward": return PointerButton.FORWARD;
            default: return null;
        }
    }

    private static KeyAction parseKeyAction(String action) {
        switch (action) {
            case "down": return KeyAction.DOWN;
            case "up": return KeyAction.UP;
            case "reset": return KeyAction.RESET;
            default: return null;
        }
    }

    private static GlobalAction parseGlobalAction(String action) {
        switch (action) {
            case "back": return GlobalAction.BACK;
            case "home": return GlobalAction.HOME;
            case "recents": return GlobalAction.RECENTS;
            case "notifications": return GlobalAction.NOTIFICATIONS;
            //deliberately no lock screen or power: they end the capture session, so the button
            //would kill the very stream it is being pressed through
            default: return null;
        }
    }

    private static EditAction parseEditAction(String action) {
        switch (action) {
            case "backspace": return EditAction.BACKSPACE;
            case "enter": return EditAction.ENTER;
            default: return null;
        }
    }

    /** @see #MAX_KEY_CODE_LENGTH */
    static boolean isKeyCode(String code) {
        if (code == null || code.isEmpty() || code.length() > MAX_KEY_CODE_LENGTH) {
            return false;
        }
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            boolean alphanumeric = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9');
            if (!alphanumeric) {
                return false;
            }
        }
        return true;
    }

    /** {@code Double.isFinite} is Java 8, but this module also has to build for older Android. */
    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    static float clampNotches(double notches) {
        if (notches < -MAX_WHEEL_NOTCHES) return -MAX_WHEEL_NOTCHES;
        if (notches > MAX_WHEEL_NOTCHES) return MAX_WHEEL_NOTCHES;
        return (float) notches;
    }

    /** Rounding on the client can put a touch at the very edge slightly outside the picture. */
    static float clamp01(double value) {
        if (value < 0) return 0;
        if (value > 1) return 1;
        return (float) value;
    }

    /**
     * Maps a normalized coordinate to a pixel of a screen of the given size. The result stays
     * within the screen - a gesture that reaches outside the display bounds is rejected by the
     * system.
     */
    public static float toPixels(float normalized, int screenSize) {
        if (screenSize <= 1) {
            return 0;
        }
        float pixels = normalized * (screenSize - 1);
        if (pixels < 0) return 0;
        if (pixels > screenSize - 1) return screenSize - 1;
        return pixels;
    }
}
