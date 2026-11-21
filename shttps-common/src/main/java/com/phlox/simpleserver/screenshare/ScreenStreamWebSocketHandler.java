package com.phlox.simpleserver.screenshare;

import com.phlox.server.request.Request;
import com.phlox.server.request.RequestContext;
import com.phlox.server.utils.SHTTPSLoggerProxy;
import com.phlox.server.websocket.WebSocketCloseCodes;
import com.phlox.server.websocket.WebSocketRequestHandler;
import com.phlox.server.websocket.WebSocketSession;
import com.phlox.simpleserver.SHTTPSConfig;
import com.phlox.simpleserver.auth.AuthManager;
import com.phlox.simpleserver.auth.User;
import com.phlox.simpleserver.remotecontrol.RemoteInputCommand;
import com.phlox.simpleserver.remotecontrol.RemoteInputTarget;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * WebSocket endpoint that pushes the device screen as a fragmented MP4 stream, ready to be fed to
 * a Media Source Extensions {@code SourceBuffer} in a browser.
 * <p>
 * What a client receives, in order:
 * <ol>
 *     <li>one text frame with the stream description, e.g.
 *     {@code {"type":"init","codec":"avc1.42c01f","width":1280,"height":720}} - the codec string
 *     goes into {@code video/mp4; codecs="..."}</li>
 *     <li>one binary frame with the fMP4 initialization segment</li>
 *     <li>binary frames with media segments, one video frame each, starting at a key frame</li>
 * </ol>
 * The client may send the text command {@code request-key-frame} to get a fresh decoding start
 * point, which is what it needs after recovering from an error.
 * <p>
 * The same socket carries remote control the other way round: JSON text frames described by
 * {@link RemoteInputCommand} are handed to the {@link RemoteInputTarget}, which exists only while
 * the user keeps the accessibility service granted and the option enabled. Whenever control is
 * unavailable or an action can not be carried out, the client is told with a
 * {@code {"type":"control","enabled":..,"reason":".."}} frame, so its UI can explain itself instead
 * of silently doing nothing.
 * <p>
 * Each connection gets its own sending thread with a bounded queue in front of it, so a client on
 * a slow link can never hold up the encoder - and through it every other viewer. When a client
 * falls too far behind, everything queued for it is dropped and its stream resumes at the next key
 * frame, which leaves a gap in the browser's buffered range that the player is expected to skip.
 * <p>
 * Watching needs {@link User.SystemRights#VIEW_SCREEN} and controlling additionally
 * {@link User.SystemRights#CONTROL_SCREEN}. Both are read once, out of the context of the handshake
 * request, and kept for the life of the connection: evaluating them means a database lookup for the
 * user's role, which is not something to do per touch event. A change of rights therefore only
 * takes effect when the client reconnects, while the screen sharing and remote control options in
 * the app keep being obeyed the moment they are switched off.
 * <p>
 * With {@link SHTTPSConfig#isScreenShareConfirmationEnabled()} on and a {@link ScreenAccessApprover}
 * given, a new viewer additionally has to be let in by the person at the device. The connection is
 * held open meanwhile without blocking its thread - so pings are still answered and a client that
 * gives up is noticed - and all the client gets is {@code {"type":"approval","state":"pending"}}.
 * An answer of allow starts the stream as above, "view only" starts it with control withheld for
 * this connection, and a refusal or no answer within {@link #ACCESS_REQUEST_TIMEOUT_SECONDS} closes
 * it with {@link #CLOSE_CODE_ACCESS_DENIED} or {@link #CLOSE_CODE_ACCESS_TIMEOUT}. An approval is
 * remembered for the same address and account for {@link #ACCESS_GRACE_MILLIS} after their last
 * connection ends, which is what keeps the page's own reconnects from asking all over again.
 */
public class ScreenStreamWebSocketHandler extends WebSocketRequestHandler {
    public static final String PATH = "/api/screen/stream";

    private static final SHTTPSLoggerProxy.Logger logger =
            SHTTPSLoggerProxy.getLogger(ScreenStreamWebSocketHandler.class);

    /**
     * Close code for a client whose account may not watch the screen. Private use range (4000-4999),
     * so it can not be confused with anything the protocol itself sends, and the web UI can tell
     * "you are not allowed" apart from "nothing is being shared".
     */
    public static final int CLOSE_CODE_FORBIDDEN = 4403;
    /** The person at the device refused this viewer. */
    public static final int CLOSE_CODE_ACCESS_DENIED = 4401;
    /** Nobody at the device answered the request in time. */
    public static final int CLOSE_CODE_ACCESS_TIMEOUT = 4408;

    /** How long a viewer waits for the person at the device to let them in. */
    public static final int ACCESS_REQUEST_TIMEOUT_SECONDS = 60;
    /**
     * How long an approval outlives the last connection it let in. The page reconnects on its own
     * after a dropped connection and whenever capture restarts, and the user who just let someone
     * in should not be asked about each of those.
     */
    public static final long ACCESS_GRACE_MILLIS = 2 * 60 * 1000;

    /** Runs the access request timeouts; one daemon thread for all of them, idle almost always. */
    private static final ScheduledThreadPoolExecutor TIMEOUTS = createTimeoutExecutor();

    private static final String ATTRIBUTE_PUMP = "screenStreamPump";
    private static final String ATTRIBUTE_MAY_VIEW = "screenMayView";
    private static final String ATTRIBUTE_MAY_CONTROL = "screenMayControl";
    /**
     * The input target this session last actually used. Remembered because the supplier can start
     * returning null - the user switches remote control off - between the last event and the
     * disconnect, and {@link #onClose} still has to let go of whatever was being held.
     */
    private static final String ATTRIBUTE_INPUT_TARGET = "screenInputTarget";
    /** The name of the account the handshake was made with; absent with authentication off. */
    private static final String ATTRIBUTE_USER_NAME = "screenUserName";
    /** Set when the person at the device let this client watch but not control. */
    private static final String ATTRIBUTE_VIEW_ONLY = "screenViewOnly";
    private static final String ATTRIBUTE_STATE = "screenConnectionState";
    /**
     * Frames a client may fall behind before its backlog is thrown away. Everything queued here is
     * delay the viewer will see, and on a live view stale frames are worth less than catching up:
     * half a second at 30 fps, and less than that on a static screen, where frames are rarer.
     */
    private static final int MAX_QUEUED_MESSAGES = 15;
    private static final String COMMAND_REQUEST_KEY_FRAME = "request-key-frame";

    /** Remote control is switched off in the app, or the accessibility service is not granted. */
    private static final String REASON_DISABLED = "disabled";
    /** The user may watch the screen, but not touch it. */
    private static final String REASON_FORBIDDEN = "forbidden";
    /** The person at the device let this client watch, but not control. */
    private static final String REASON_VIEW_ONLY = "view-only";
    /** Another client is holding the device right now. */
    private static final String REASON_BUSY = "busy";
    private static final String REASON_NO_TEXT_FIELD = "no-text-field";
    private static final String REASON_TEXT_FAILED = "text-failed";
    private static final String REASON_UNSUPPORTED = "unsupported";
    /** The platform refused the event - on Windows typically a window running elevated. */
    private static final String REASON_INPUT_FAILED = "input-failed";
    /** The platform has not been given permission to be controlled at all. */
    private static final String REASON_INPUT_PERMISSION = "input-permission";
    private static final String REASON_OK = "ok";

    private final SHTTPSConfig config;
    private final AuthManager authManager;
    private final Supplier<ScreenCaptureSource> screenCaptureSourceSupplier;
    private final Supplier<RemoteInputTarget> remoteInputTargetSupplier;
    private final @Nullable ScreenAccessApprover accessApprover;
    private final AccessGrants accessGrants = new AccessGrants();

    /** Where answers are carried out. Package-private so tests can run them inline. */
    Executor decisionExecutor = task -> {
        Thread thread = new Thread(task, "ScreenAccessDecision");
        thread.setDaemon(true);
        thread.start();
    };
    /** Package-private so tests need not wait a minute for a timeout. */
    long accessRequestTimeoutMillis = ACCESS_REQUEST_TIMEOUT_SECONDS * 1000L;
    /** Package-private so tests can move time past the grace period. */
    LongSupplier clock = System::currentTimeMillis;

    /**
     * @param config                       consulted for the auth mode: with authentication switched
     *                                     off there are no users to have rights
     * @param authManager                  what the handshake's user and their rights come from
     * @param screenCaptureSourceSupplier looked up per connection, because the capture session
     *                                    comes and goes independently of the server
     * @param remoteInputTargetSupplier   looked up per message and null whenever remote control is
     *                                    unavailable - the user may revoke the platform's input
     *                                    permission at any moment
     */
    public ScreenStreamWebSocketHandler(SHTTPSConfig config, AuthManager authManager,
                                        Supplier<ScreenCaptureSource> screenCaptureSourceSupplier,
                                        Supplier<RemoteInputTarget> remoteInputTargetSupplier) {
        this(config, authManager, screenCaptureSourceSupplier, remoteInputTargetSupplier, null);
    }

    /**
     * @param accessApprover asks the person at the device about each new viewer while the
     *                       confirmation option is on; null for a host that can not ask anyone,
     *                       which then lets viewers in the way it always did
     */
    public ScreenStreamWebSocketHandler(SHTTPSConfig config, AuthManager authManager,
                                        Supplier<ScreenCaptureSource> screenCaptureSourceSupplier,
                                        Supplier<RemoteInputTarget> remoteInputTargetSupplier,
                                        @Nullable ScreenAccessApprover accessApprover) {
        this.config = config;
        this.authManager = authManager;
        this.screenCaptureSourceSupplier = screenCaptureSourceSupplier;
        this.remoteInputTargetSupplier = remoteInputTargetSupplier;
        this.accessApprover = accessApprover;
        //this endpoint only pushes; incoming traffic is the occasional short command
        options.maxFramePayloadLength = 4 * 1024;
        options.maxMessagePayloadLength = 4 * 1024;
    }

    /**
     * A WebSocket handshake is not subject to the same origin policy the way an XHR is, so a page
     * on another origin could otherwise open an authenticated connection with the user's cookies
     * and watch the screen. Clients that are not browsers send no Origin header at all.
     */
    @Override
    protected boolean isOriginAllowed(@NotNull Request request, @Nullable String origin) {
        if (origin == null || origin.isEmpty()) {
            return true;
        }
        String host = request.headers.get(Request.HEADER_HOST);
        if (host == null) {
            return false;
        }
        int schemeEnd = origin.indexOf("://");
        if (schemeEnd < 0) {
            return false;
        }
        return origin.substring(schemeEnd + 3).equalsIgnoreCase(host.trim());
    }

    /**
     * Takes the rights of the user who made the handshake along: the request context they come from
     * is gone by the time the first frame arrives.
     */
    @Override
    protected void onSessionCreated(RequestContext context, WebSocketSession session) {
        EnumSet<User.SystemRights> rights = resolveSystemRights(context, session);
        session.getAttributes().put(ATTRIBUTE_MAY_VIEW,
                rights.contains(User.SystemRights.VIEW_SCREEN));
        session.getAttributes().put(ATTRIBUTE_MAY_CONTROL,
                rights.contains(User.SystemRights.CONTROL_SCREEN));
    }

    /**
     * @return what the user of this request may do. With authentication switched off everything is
     * allowed - the same as everywhere else in the app, where the screen is then guarded by the
     * sharing option and the consent dialog on the device alone. An unauthenticated request under
     * any other auth mode gets nothing.
     */
    private @NotNull EnumSet<User.SystemRights> resolveSystemRights(RequestContext context,
                                                                    WebSocketSession session) {
        if (config.getAuthMode().equals(SHTTPSConfig.AuthMode.NONE)) {
            return EnumSet.allOf(User.SystemRights.class);
        }
        User user = authManager == null ? null : authManager.getAuthenticatedUser(context);
        if (user == null) {
            return EnumSet.noneOf(User.SystemRights.class);
        }
        //for the access prompt, which has to say who is asking
        if (user.identity != null) {
            session.getAttributes().put(ATTRIBUTE_USER_NAME, user.identity);
        }
        try {
            //reading the rights of a user with a role means a database lookup, which can fail
            return authManager.getUserRightsEvaluator().userSystemRights(user);
        } catch (Exception e) {
            //whatever went wrong, this is not the place to guess in the user's favour
            logger.stackTrace(e);
            return EnumSet.noneOf(User.SystemRights.class);
        }
    }

    private static boolean isAllowed(WebSocketSession session, String attribute) {
        return Boolean.TRUE.equals(session.getAttributes().get(attribute));
    }

    @Override
    public void onOpen(WebSocketSession session) {
        if (!isAllowed(session, ATTRIBUTE_MAY_VIEW)) {
            session.close(CLOSE_CODE_FORBIDDEN, "Not allowed to view the screen");
            return;
        }
        ScreenCaptureSource source = screenCaptureSourceSupplier.get();
        if (source == null || !source.isActive()) {
            session.close(WebSocketCloseCodes.POLICY_VIOLATION, "Screen sharing is not active");
            return;
        }
        ConnectionState state = new ConnectionState();
        session.getAttributes().put(ATTRIBUTE_STATE, state);
        if (accessApprover == null || !config.isScreenShareConfirmationEnabled()) {
            startStreaming(session, state, source);
            return;
        }

        //what letting this client in would hand over, judged now, the way the first control
        //status would judge it
        boolean includesControl = isAllowed(session, ATTRIBUTE_MAY_CONTROL) &&
                remoteInputTargetSupplier.get() != null;
        String userName = (String) session.getAttributes().get(ATTRIBUTE_USER_NAME);
        state.grantKey = AccessGrants.key(session.getRemoteAddress(), userName);
        ScreenAccessRequest.Decision remembered =
                accessGrants.lookup(state.grantKey, includesControl, clock.getAsLong());
        if (remembered != null) {
            logger.d("Screen viewer " + session.getRemoteAddress() + " let in again without asking");
            admit(session, state, includesControl, remembered, source);
            return;
        }
        requestAccess(session, state, userName, includesControl);
    }

    /**
     * Puts the question to the person at the device and returns without waiting for the answer:
     * the connection thread has to go on reading, or the client would neither get its pings
     * answered nor be noticed leaving.
     */
    private void requestAccess(WebSocketSession session, ConnectionState state,
                               @Nullable String userName, boolean includesControl) {
        try {
            //before anything can answer, so that nothing of the stream can overtake it
            session.sendText("{\"type\":\"approval\",\"state\":\"pending\",\"timeout\":" +
                    (accessRequestTimeoutMillis / 1000) + "}");
        } catch (IOException e) {
            //the read loop is about to find out as well, and nothing is pending yet to clean up
            logger.d("Screen viewer " + session.getRemoteAddress() + " went away: " + e.getMessage());
            return;
        }
        ScreenAccessRequest request = new ScreenAccessRequest(session.getRemoteAddress(), userName,
                includesControl, (answered, decision) -> {
            cancelTimeout(state);
            finishRequest(answered);
            //never on the answering thread: that is usually a UI thread, and both starting a
            //capture subscription and closing a socket can take a while
            decisionExecutor.execute(() -> onAnswered(session, state, includesControl, decision));
        });
        state.pendingRequest = request;
        state.timeout = TIMEOUTS.schedule(() -> {
            if (request.settle()) {
                finishRequest(request);
                logger.i("Nobody answered whether " + session.getRemoteAddress() +
                        " may watch the screen");
                decisionExecutor.execute(() -> session.close(CLOSE_CODE_ACCESS_TIMEOUT, "No answer"));
            }
        }, accessRequestTimeoutMillis, TimeUnit.MILLISECONDS);
        try {
            accessApprover.onAccessRequested(request);
        } catch (Exception e) {
            //nobody can be asked, and not asking is not the same as being told yes
            logger.stackTrace(e);
            if (request.settle()) {
                cancelTimeout(state);
                finishRequest(request);
                session.close(CLOSE_CODE_ACCESS_DENIED, "Could not ask for access");
            }
        }
    }

    private void onAnswered(WebSocketSession session, ConnectionState state,
                            boolean includesControl, ScreenAccessRequest.Decision decision) {
        if (decision == ScreenAccessRequest.Decision.DENY) {
            logger.i("Screen viewer " + session.getRemoteAddress() + " was refused");
            session.close(CLOSE_CODE_ACCESS_DENIED, "Access denied");
            return;
        }
        accessGrants.remember(state.grantKey, includesControl, decision, clock.getAsLong());
        //the capture may have stopped while the question was on screen
        ScreenCaptureSource source = screenCaptureSourceSupplier.get();
        if (source == null || !source.isActive()) {
            session.close(WebSocketCloseCodes.POLICY_VIOLATION, "Screen sharing is not active");
            return;
        }
        logger.i("Screen viewer " + session.getRemoteAddress() + " was let in" +
                (decision == ScreenAccessRequest.Decision.VIEW_ONLY && includesControl
                        ? " to watch only" : ""));
        admit(session, state, includesControl, decision, source);
    }

    private void admit(WebSocketSession session, ConnectionState state, boolean includesControl,
                       ScreenAccessRequest.Decision decision, ScreenCaptureSource source) {
        if (decision == ScreenAccessRequest.Decision.VIEW_ONLY && includesControl) {
            session.getAttributes().put(ATTRIBUTE_MAY_CONTROL, false);
            session.getAttributes().put(ATTRIBUTE_VIEW_ONLY, true);
        }
        startStreaming(session, state, source);
    }

    /**
     * Subscribes the client to the stream. Done under the connection's lock: once the client is
     * let in by the person at the device this runs on another thread than the connection's, and a
     * pump subscribed after {@link #onClose} has run would never be unsubscribed - its own failure
     * path closes the socket, but only onClose takes it off the source.
     */
    private void startStreaming(WebSocketSession session, ConnectionState state,
                                ScreenCaptureSource source) {
        StreamPump pump;
        synchronized (state) {
            if (state.closed) {
                return;
            }
            pump = new StreamPump(session, source);
            //publish and start before subscribing: an exception after the manager holds a reference
            //would leave the encoder running for a connection nobody will ever close
            session.getAttributes().put(ATTRIBUTE_PUMP, pump);
            pump.start();
            if (!source.addStreamListener(pump)) {
                //the capture stopped between the check above and here
                session.getAttributes().remove(ATTRIBUTE_PUMP);
                pump.shutdown();
                session.close(WebSocketCloseCodes.POLICY_VIOLATION, "Screen sharing is not active");
                return;
            }
            if (state.grantKey != null) {
                //an approval does not run out while a connection it let in is still open
                accessGrants.connectionOpened(state.grantKey);
                state.holdsGrant = true;
            }
        }
        //queued after the init segment the subscription just produced, so the client learns whether
        //it may offer control as soon as it has a picture
        boolean mayControl = isAllowed(session, ATTRIBUTE_MAY_CONTROL);
        RemoteInputTarget target = remoteInputTargetSupplier.get();
        boolean controlAvailable = mayControl && target != null;
        sendControlStatus(pump, controlAvailable, controlAvailable ? REASON_OK :
                (mayControl ? REASON_DISABLED : notAllowedReason(session)), target);
    }

    /** Why a client that may not control was told so: its account, or the person at the device. */
    private static String notAllowedReason(WebSocketSession session) {
        return isAllowed(session, ATTRIBUTE_VIEW_ONLY) ? REASON_VIEW_ONLY : REASON_FORBIDDEN;
    }

    private static void cancelTimeout(ConnectionState state) {
        ScheduledFuture<?> timeout = state.timeout;
        if (timeout != null) {
            timeout.cancel(false);
        }
    }

    private void finishRequest(ScreenAccessRequest request) {
        try {
            accessApprover.onAccessRequestFinished(request);
        } catch (Exception e) {
            logger.stackTrace(e);
        }
    }

    @Override
    public void onTextMessage(WebSocketSession session, String message) {
        if (COMMAND_REQUEST_KEY_FRAME.equals(message.trim())) {
            StreamPump pump = (StreamPump) session.getAttributes().get(ATTRIBUTE_PUMP);
            if (pump != null) {
                pump.source.requestKeyFrame();
            }
            return;
        }
        RemoteInputCommand command = RemoteInputCommand.parse(message);
        if (command == null) {
            return;
        }
        StreamPump pump = (StreamPump) session.getAttributes().get(ATTRIBUTE_PUMP);
        if (pump == null) {
            return;
        }
        if (!isAllowed(session, ATTRIBUTE_MAY_CONTROL)) {
            //also the answer to the client's periodic "may I control now?" probe
            sendControlStatus(pump, false, notAllowedReason(session), null);
            return;
        }
        //re-read for every message: the option can be switched off and the accessibility service
        //revoked while this connection is open
        RemoteInputTarget injector = remoteInputTargetSupplier.get();
        if (injector == null) {
            sendControlStatus(pump, false, REASON_DISABLED, null);
            return;
        }
        //so that onClose can release this one even after the supplier has stopped offering it
        session.getAttributes().put(ATTRIBUTE_INPUT_TARGET, injector);
        switch (command.type) {
            case TOUCH:
                handleTouch(session, pump, injector, command);
                break;
            case KEY:
                injector.performGlobalAction(command.globalAction);
                clearControlStatus(pump);
                break;
            case TEXT:
                reportTextResult(pump, injector, injector.inputText(command.text));
                break;
            case EDIT:
                reportTextResult(pump, injector,
                        command.editAction == RemoteInputCommand.EditAction.BACKSPACE
                                ? injector.backspace() : injector.enter());
                break;
            case STATUS:
                //the client is asking whether control has become available again
                sendControlStatus(pump, true, REASON_OK, injector);
                break;
            case MOUSE:
                handleMouse(session, pump, injector, command);
                break;
            case WHEEL:
                reportInputResult(pump, injector, injector.scroll(session,
                        command.wheelX, command.wheelY, command.hasPosition, command.x, command.y));
                break;
            case KEYBOARD:
                reportInputResult(pump, injector,
                        command.keyAction == RemoteInputCommand.KeyAction.RESET
                                ? injector.releaseHeldKeys(session)
                                : injector.keyEvent(session,
                                        command.keyAction == RemoteInputCommand.KeyAction.DOWN,
                                        command.keyCode));
                break;
        }
    }

    private void handleMouse(WebSocketSession session, StreamPump pump, RemoteInputTarget injector,
                             RemoteInputCommand command) {
        //the session object is the ownership token here too, for the same reason as in handleTouch
        RemoteInputTarget.InputResult result;
        switch (command.pointerAction) {
            case MOVE:
                result = injector.pointerMove(session, command.x, command.y);
                //a move is the one event that says nothing about whether this client is being
                //listened to: a target answers OK both when it moved the pointer and when it
                //dropped the move because someone else holds the machine. Letting that OK clear
                //the dedup state would have a bystander's hovering re-announce "busy" over and
                //over, so only an actual complaint is reported here.
                if (result != RemoteInputTarget.InputResult.OK) {
                    reportInputResult(pump, injector, result);
                }
                return;
            case DOWN:
                result = injector.pointerDown(session, command.pointerButton, command.x, command.y);
                break;
            case UP:
                result = injector.pointerUp(session, command.pointerButton, command.hasPosition,
                        command.x, command.y, false);
                break;
            case CANCEL:
                result = injector.pointerUp(session, command.pointerButton, command.hasPosition,
                        command.x, command.y, true);
                break;
            default:
                return;
        }
        reportInputResult(pump, injector, result);
    }

    private void handleTouch(WebSocketSession session, StreamPump pump, RemoteInputTarget injector,
                             RemoteInputCommand command) {
        switch (command.touchAction) {
            case DOWN:
                //the session object is the ownership token: it is what identifies this client and
                //what onClose has at hand to release the device again
                if (injector.touchDown(session, command.x, command.y)) {
                    clearControlStatus(pump);
                } else {
                    sendControlStatus(pump, true, REASON_BUSY, injector);
                }
                break;
            case MOVE:
                injector.touchMove(session, command.x, command.y);
                break;
            case UP:
                injector.touchUp(session, command.x, command.y, false);
                break;
            case CANCEL:
                injector.touchUp(session, command.x, command.y, true);
                break;
        }
    }

    private void reportTextResult(StreamPump pump, RemoteInputTarget target,
                                  RemoteInputTarget.TextResult result) {
        switch (result) {
            case OK:
                clearControlStatus(pump);
                break;
            case NO_TEXT_FIELD:
                sendControlStatus(pump, true, REASON_NO_TEXT_FIELD, target);
                break;
            case UNSUPPORTED:
                sendControlStatus(pump, true, REASON_UNSUPPORTED, target);
                break;
            case NO_PERMISSION:
                sendControlStatus(pump, true, REASON_INPUT_PERMISSION, target);
                break;
            case FAILED:
            default:
                sendControlStatus(pump, true, REASON_TEXT_FAILED, target);
                break;
        }
    }

    private void reportInputResult(StreamPump pump, RemoteInputTarget target,
                                   RemoteInputTarget.InputResult result) {
        switch (result) {
            case OK:
                clearControlStatus(pump);
                break;
            case BUSY:
                sendControlStatus(pump, true, REASON_BUSY, target);
                break;
            case UNSUPPORTED:
                sendControlStatus(pump, true, REASON_UNSUPPORTED, target);
                break;
            case NO_PERMISSION:
                sendControlStatus(pump, true, REASON_INPUT_PERMISSION, target);
                break;
            case FAILED:
            default:
                sendControlStatus(pump, true, REASON_INPUT_FAILED, target);
                break;
        }
    }

    /**
     * Tells the client what it may do, which kinds of input this device accepts, and why something
     * did not happen. Repeats are suppressed - a client that keeps dragging with no service granted
     * would otherwise get one frame per event.
     * <p>
     * This frame, and not the init segment's, is where the input families are advertised. The init
     * frame is written from inside the capture subscription, before the handler has resolved
     * anything about control, and it is written again whenever capture reinitialises - so a
     * capability list there would be a snapshot taken at the wrong moment. This one is sent
     * immediately after it in {@link #onOpen} and refreshed whenever the answer changes.
     *
     * @param target what the families are read from; null, or control being disabled, leaves the
     *               list out entirely, which is how a client learns it may offer nothing
     */
    private void sendControlStatus(StreamPump pump, boolean enabled, String reason,
                                   RemoteInputTarget target) {
        StringBuilder status = new StringBuilder(96);
        status.append("{\"type\":\"control\",\"enabled\":").append(enabled)
                .append(",\"reason\":\"").append(reason).append('"');
        appendInputFamilies(status, enabled ? target : null);
        status.append('}');
        String rendered = status.toString();
        //the first status of a client let in by the person at the device is sent from the thread
        //that carried out the answer, which may overlap with the connection thread's own
        synchronized (pump) {
            if (rendered.equals(pump.lastControlStatus)) {
                return;
            }
            pump.lastControlStatus = rendered;
        }
        pump.enqueueText(rendered);
    }

    /**
     * Always writes the array, empty when there is nothing on offer.
     * <p>
     * Its presence rather than its contents is what tells a client that this server knows about
     * input families at all. A build from before they existed sends no such field, and the page
     * needs to tell "control is off right now, ask again later" apart from "this server will never
     * have any" - which it cannot do if silence means both.
     */
    private static void appendInputFamilies(StringBuilder out, RemoteInputTarget target) {
        EnumSet<RemoteInputTarget.InputFamily> families = null;
        if (target != null) {
            try {
                families = target.inputFamilies();
            } catch (Exception e) {
                //a target that can not answer is one the client should offer nothing for, but it
                //must not cost the viewer their picture
                logger.stackTrace(e);
            }
        }
        out.append(",\"input\":[");
        if (families != null) {
            boolean first = true;
            for (RemoteInputTarget.InputFamily family : families) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append('"').append(family.wireName()).append('"');
            }
        }
        out.append(']');
    }

    /** After something worked, the next failure is worth reporting again. */
    private void clearControlStatus(StreamPump pump) {
        synchronized (pump) {
            pump.lastControlStatus = null;
        }
    }

    @Override
    public void onClose(WebSocketSession session, int code, String reason) {
        ConnectionState state = (ConnectionState) session.getAttributes().get(ATTRIBUTE_STATE);
        StreamPump pump;
        if (state != null) {
            boolean heldGrant;
            synchronized (state) {
                state.closed = true;
                pump = (StreamPump) session.getAttributes().remove(ATTRIBUTE_PUMP);
                heldGrant = state.holdsGrant;
                state.holdsGrant = false;
            }
            //a client that leaves while the question is still on screen takes the question along
            ScreenAccessRequest pending = state.pendingRequest;
            if (pending != null && pending.settle()) {
                cancelTimeout(state);
                finishRequest(pending);
            }
            if (heldGrant) {
                accessGrants.connectionClosed(state.grantKey, clock.getAsLong());
            }
        } else {
            pump = (StreamPump) session.getAttributes().remove(ATTRIBUTE_PUMP);
        }
        if (pump != null) {
            pump.detach();
        }
        //a client that disappears mid-stroke would otherwise leave a finger pressed on the device -
        //or, on a desktop, a Ctrl key held, which is enough to make the machine unusable - and lock
        //every other viewer out of control for good.
        //
        //Both the target this session actually used and the one on offer right now are released:
        //the option can be switched off between the last event and the disconnect, and asking the
        //supplier alone would then get null and let go of nothing.
        RemoteInputTarget used =
                (RemoteInputTarget) session.getAttributes().remove(ATTRIBUTE_INPUT_TARGET);
        RemoteInputTarget current = remoteInputTargetSupplier.get();
        releaseQuietly(used, session);
        if (current != null && current != used) {
            releaseQuietly(current, session);
        }
    }

    /** A close is the last chance to let go of the device; nothing here may stop that happening. */
    private void releaseQuietly(RemoteInputTarget target, WebSocketSession session) {
        if (target == null) {
            return;
        }
        try {
            target.releaseClient(session);
        } catch (Exception e) {
            logger.stackTrace(e);
        }
    }

    private static ScheduledThreadPoolExecutor createTimeoutExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "ScreenAccessTimeout");
            thread.setDaemon(true);
            return thread;
        });
        //answered requests are the rule, and their minute long timeouts should not pile up
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    /** What the threads of one connection share. Guarded by itself where it says so. */
    private static final class ConnectionState {
        /** Set by onClose; guarded. */
        boolean closed;
        /** Whether this connection counts towards keeping its approval alive; guarded. */
        boolean holdsGrant;
        /** Address and account this connection is approved under, null when nobody was asked. */
        volatile String grantKey;
        volatile ScreenAccessRequest pendingRequest;
        volatile ScheduledFuture<?> timeout;
    }

    /**
     * Approvals given recently, so a client that reconnects - which the page does by itself after
     * any hiccup - is not asked about again. Kept for the life of the handler, which is one run of
     * the server. Refusals are not remembered: the person at the device may well change their mind.
     */
    private static final class AccessGrants {
        private static final class Grant {
            /** Whether the request that was answered would have included control. */
            boolean coversControl;
            ScreenAccessRequest.Decision decision;
            int openConnections;
            long expiresAt;
        }

        private final Map<String, Grant> grants = new HashMap<>();

        static String key(String address, @Nullable String userName) {
            return address + '\n' + (userName == null ? "" : userName);
        }

        /**
         * @return the remembered answer, or null if this client has to be asked. An approval to
         * watch is never stretched into one to control: a client that was let in while control was
         * not on offer is asked again once it is.
         */
        synchronized @Nullable ScreenAccessRequest.Decision lookup(String key, boolean includesControl,
                                                                  long now) {
            purge(now);
            Grant grant = grants.get(key);
            if (grant == null || (includesControl && !grant.coversControl)) {
                return null;
            }
            return grant.decision;
        }

        synchronized void remember(String key, boolean includesControl,
                                   ScreenAccessRequest.Decision decision, long now) {
            Grant grant = grants.get(key);
            if (grant == null) {
                grant = new Grant();
                grants.put(key, grant);
            }
            grant.coversControl = includesControl;
            grant.decision = decision;
            grant.expiresAt = now + ACCESS_GRACE_MILLIS;
        }

        synchronized void connectionOpened(String key) {
            Grant grant = grants.get(key);
            if (grant != null) {
                grant.openConnections++;
            }
        }

        synchronized void connectionClosed(String key, long now) {
            Grant grant = grants.get(key);
            if (grant != null) {
                grant.openConnections = Math.max(0, grant.openConnections - 1);
                grant.expiresAt = now + ACCESS_GRACE_MILLIS;
            }
        }

        private void purge(long now) {
            for (Iterator<Grant> it = grants.values().iterator(); it.hasNext(); ) {
                Grant grant = it.next();
                if (grant.openConnections == 0 && now >= grant.expiresAt) {
                    it.remove();
                }
            }
        }
    }

    /**
     * Moves segments from the encoder thread to one client's socket.
     * <p>
     * Everything the encoder hands over is queued and written by this pump's own thread: a socket
     * write can block for as long as the client's link needs, and the encoder callback must never
     * pay for that. All frames of this connection are written from here, which also keeps the
     * blocking close frame off the manager's threads.
     */
    private static final class StreamPump implements ScreenStreamListener, Runnable {
        final ScreenCaptureSource source;
        /**
         * Last control status frame sent to this client, to suppress repeats. Only touched from the
         * connection thread, which is where all the incoming messages are handled.
         */
        String lastControlStatus;

        private final WebSocketSession session;
        private final Thread thread;
        //guards the queue and all the flags below
        private final ArrayDeque<Message> queue = new ArrayDeque<>();
        private boolean running = true;
        private boolean stopRequested = false;
        //set after dropping a backlog: segments before the next key frame are of no use anymore
        private boolean waitingForKeyFrame = false;

        StreamPump(WebSocketSession session, ScreenCaptureSource source) {
            this.session = session;
            this.source = source;
            this.thread = new Thread(this, "ScreenStream-" + session.getRemoteAddress());
            this.thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        /** Unsubscribes from the stream and stops the sending thread. */
        void detach() {
            source.removeStreamListener(this);
            shutdown();
        }

        void shutdown() {
            synchronized (queue) {
                running = false;
                queue.clear();
                queue.notifyAll();
            }
        }

        /** Queues a text frame for this client; writing happens on the pump's own thread. */
        void enqueueText(String text) {
            synchronized (queue) {
                if (!running || stopRequested) {
                    return;
                }
                queue.add(Message.text(text));
                queue.notifyAll();
            }
        }

        @Override
        public void run() {
            try {
                while (true) {
                    Message message;
                    synchronized (queue) {
                        while (running && !stopRequested && queue.isEmpty()) {
                            queue.wait();
                        }
                        if (!running) {
                            return;
                        }
                        if (stopRequested) {
                            break;
                        }
                        message = queue.poll();
                    }
                    if (message.text != null) {
                        session.sendText(message.text);
                    } else {
                        session.sendBinary(message.binary);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException e) {
                logger.d("Screen stream to " + session.getRemoteAddress() + " failed: " + e.getMessage());
                synchronized (queue) {
                    running = false;
                }
                //wakes the connection thread out of its read, which then unsubscribes us
                session.close(WebSocketCloseCodes.ABNORMAL_CLOSURE, "");
                return;
            }
            session.close(WebSocketCloseCodes.GOING_AWAY, "Screen sharing stopped");
        }

        @Override
        public void onInitSegment(byte[] initSegment, String codec, int width, int height) {
            //the platform tells the browser which controls make sense for this machine - a phone
            //has a Back button, a PC has a right mouse button. Omitted when the source does not
            //say, which is what a client written before this field expects anyway
            String platform = source.getPlatform();
            String info = "{\"type\":\"init\",\"codec\":\"" + codec + "\",\"width\":" + width +
                    ",\"height\":" + height +
                    (platform == null ? "" : ",\"platform\":\"" + platform + "\"") + "}";
            synchronized (queue) {
                if (!running) {
                    return;
                }
                //an init segment starts a new stream, anything still queued belongs to the old one
                queue.clear();
                waitingForKeyFrame = false;
                queue.add(Message.text(info));
                queue.add(Message.binary(initSegment, false));
                queue.notifyAll();
            }
        }

        @Override
        public void onMediaSegment(byte[] segment, boolean keyFrame, long presentationTimeUs) {
            boolean fellBehind = false;
            boolean needKeyFrame = false;
            synchronized (queue) {
                if (!running || stopRequested) {
                    return;
                }
                if (waitingForKeyFrame) {
                    if (!keyFrame) {
                        return;
                    }
                    waitingForKeyFrame = false;
                }
                if (queue.size() >= MAX_QUEUED_MESSAGES) {
                    //the client can not keep up: drop its backlog instead of letting the encoder
                    //thread block here, and pick the stream up again at a key frame
                    queue.removeIf(message -> message.droppable);
                    fellBehind = true;
                    waitingForKeyFrame = !keyFrame;
                    needKeyFrame = waitingForKeyFrame;
                }
                if (!waitingForKeyFrame) {
                    queue.add(Message.binary(segment, true));
                    queue.notifyAll();
                }
            }
            if (fellBehind) {
                logger.d("Screen stream client " + session.getRemoteAddress() +
                        " fell behind, dropped its queued segments");
                if (needKeyFrame) {
                    source.requestKeyFrame();
                }
            }
        }

        @Override
        public void onStreamStopped() {
            //the manager already unsubscribed us; let the sending thread do the closing, writing a
            //close frame here could block whoever stopped the capture - including the main thread
            synchronized (queue) {
                stopRequested = true;
                queue.notifyAll();
            }
        }
    }

    private static final class Message {
        final String text;
        final byte[] binary;
        /** Media segments may be thrown away when a client falls behind, nothing else may. */
        final boolean droppable;

        private Message(String text, byte[] binary, boolean droppable) {
            this.text = text;
            this.binary = binary;
            this.droppable = droppable;
        }

        static Message text(String text) {
            return new Message(text, null, false);
        }

        static Message binary(byte[] binary, boolean droppable) {
            return new Message(null, binary, droppable);
        }
    }
}
