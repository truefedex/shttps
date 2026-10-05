package com.phlox.simpleserver.screenshare;

import org.jetbrains.annotations.NotNull;

/**
 * Asks the person at the device whether a new screen viewer may come in - a notification on
 * Android, a window next to the tray on a desktop. Consulted by {@link ScreenStreamWebSocketHandler}
 * while {@code SHTTPSConfig.isScreenShareConfirmationEnabled()} is on.
 * <p>
 * The connection waits open while the question is on screen, so neither method may block: show the
 * prompt and return, then call {@link ScreenAccessRequest#answer} whenever the user decides, from
 * whatever thread that happens on.
 */
public interface ScreenAccessApprover {

    /** Show a prompt for this request. Called on the connection's thread. */
    void onAccessRequested(@NotNull ScreenAccessRequest request);

    /**
     * The request is settled and its prompt, if still showing, should go: it was answered, nobody
     * answered in time, or the client left before anyone did. Called once per request, from any
     * thread - including from inside {@link ScreenAccessRequest#answer}.
     */
    void onAccessRequestFinished(@NotNull ScreenAccessRequest request);
}
