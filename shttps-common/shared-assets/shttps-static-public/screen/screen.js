/*
 * Live view of the device screen.
 *
 * The server pushes the screen over a WebSocket as fragmented MP4 and this page feeds it to a
 * Media Source Extensions buffer. What arrives, in order:
 *   1. a text frame describing the stream: {"type":"init","codec":"avc1.42c01f","width":..,"height":..}
 *   2. one binary frame with the fMP4 initialization segment
 *   3. binary frames with media segments, one video frame each, starting at a key frame
 * A new text frame means the stream was reinitialized and everything starts over.
 *
 * The same socket carries remote control the other way: touches, system buttons and text are sent
 * as JSON text frames. The server answers with {"type":"control","enabled":..,"reason":".."} when
 * something can not be done, which is what the CONTROL button and the hint next to it show.
 */

var STREAM_PATH = "/api/screen/stream";

/**
 * How far behind the newest received frame playback aims to sit. Everything that arrives is played
 * as soon as it can be: this is a live view, not a recording, so there is nothing to gain from
 * holding a reserve - a hole in the stream is skipped rather than waited out anyway.
 */
var TARGET_LATENCY_SECONDS = 0.15;
/** Further behind than this and catching up by playing faster would take too long: jump instead. */
var MAX_LATENCY_SECONDS = 0.6;
/**
 * How much faster than real time to play while catching up. Small enough to be invisible - jumping
 * would be seen as a stutter, and drifting a whole second behind is what this avoids.
 */
var CATCH_UP_PLAYBACK_RATE = 1.1;
/** How much already played video to keep buffered before evicting it. */
var MAX_BUFFER_BEHIND_SECONDS = 8.0;
var RECONNECT_DELAY_MIN_MS = 1000;
var RECONNECT_DELAY_MAX_MS = 15000;
var MAX_RECONNECT_ATTEMPTS = 10;
/** Close code the server uses when there is no capture session to subscribe to. */
var CLOSE_CODE_NOT_ACTIVE = 1008;
/** Close code the server uses when the capture session ended. */
var CLOSE_CODE_GOING_AWAY = 1001;
/** Close code the server uses when this user may not watch the screen at all. */
var CLOSE_CODE_FORBIDDEN = 4403;

var video = null;
var socket = null;
var mediaSource = null;
var mediaSourceUrl = null;
var sourceBuffer = null;
/** Queued appendBuffer/remove calls - a SourceBuffer can only run one at a time. */
var pendingOperations = [];
/** The binary frame right after the description frame is the initialization segment. */
var expectInitSegment = false;
var streamInfo = null;
var reconnectAttempts = 0;
var reconnectTimer = null;
/** Set when reconnecting makes no sense until the user asks for it. */
var stoppedByServer = false;
var playbackTimer = null;

/**
 * Whether the device offers remote control at all - the server tells us on connect. Control needs
 * no switch of its own: when the device allows it, the picture is live and touches go through.
 */
var controlAvailable = false;
/**
 * Set when the server answered that this account may only watch. Unlike everything else that takes
 * control away, this does not change while the page is open, so there is no point in asking again.
 */
var controlForbidden = false;
var inputLayer = null;
var textField = null;
/**
 * True where there is no physical keyboard, which is where the on-screen text field is the only way
 * to type. Everywhere else the keys pressed on this machine are forwarded directly.
 */
var usesOnScreenKeyboard = false;
/** The pointer that is currently driving the device; only one finger is sent at a time. */
var activePointerId = null;
/** Newest position waiting to be sent - moves are coalesced to one per animation frame. */
var pendingMove = null;
var moveScheduled = false;
var lastSentMove = null;
/** Ignore moves smaller than this fraction of the screen (about two pixels on a phone). */
var MOVE_MIN_DELTA = 0.002;
var controlHintTimer = null;
var controlProbeTimer = null;
/** How often a page that was told "no control" asks again whether that is still true. */
var CONTROL_PROBE_INTERVAL_MS = 10000;
var composing = false;

var receivedBytes = 0;
var receivedSegments = 0;
var statsWindowStart = 0;
var statsBytes = 0;
var statsSegments = 0;
var lastStatsText = "";

function onPageLoad() {
  video = document.getElementById("screen-video");

  video.addEventListener("waiting", syncPlayback);
  video.addEventListener("timeupdate", syncPlayback);
  //nothing here has an audio track, but muted is what lets autoplay start without a gesture
  video.muted = true;

  //often enough to keep the delay near the target; eviction is cheap and the stats throttle
  //themselves to once a second
  playbackTimer = setInterval(function () {
    syncPlayback();
    evictOldBuffer();
    updateStats();
  }, 100);

  setupControlInput();
  connect();
}

// --- connection ------------------------------------------------------------------------------

function connect() {
  if (reconnectTimer !== null) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
  closeSocket();
  //the new connection announces its own control status; until then nothing is known about it
  controlForbidden = false;

  setConnectionState("", "Connecting…");
  showMessage("Connecting…", "", false);

  var protocol = location.protocol === "https:" ? "wss:" : "ws:";
  try {
    socket = new WebSocket(protocol + "//" + location.host + STREAM_PATH);
  } catch (e) {
    onDisconnected(0, String(e));
    return;
  }
  //must be set before any frame can arrive: reading a Blob is asynchronous, which would lose the
  //ordering this protocol relies on
  socket.binaryType = "arraybuffer";
  socket.onmessage = onSocketMessage;
  socket.onclose = function (event) {
    onDisconnected(event.code, event.reason);
  };
  socket.onerror = function () {
    //the close event that follows carries the details
  };
}

function closeSocket() {
  if (socket === null) {
    return;
  }
  var old = socket;
  socket = null;
  old.onmessage = null;
  old.onclose = null;
  old.onerror = null;
  try {
    old.close();
  } catch (e) {
    //already gone
  }
}

function onSocketMessage(event) {
  if (typeof event.data === "string") {
    var info;
    try {
      info = JSON.parse(event.data);
    } catch (e) {
      return;
    }
    if (info.type === "init") {
      streamInfo = info;
      expectInitSegment = true;
      startMediaSource(info);
    } else if (info.type === "control") {
      onControlStatus(info);
    }
    return;
  }

  var data = new Uint8Array(event.data);
  receivedBytes += data.length;
  statsBytes += data.length;
  if (expectInitSegment) {
    expectInitSegment = false;
  } else {
    receivedSegments++;
    statsSegments++;
  }
  queueOperation({ type: "append", data: data });
  pumpOperations();
}

function onDisconnected(code, reason) {
  closeSocket();
  releaseMediaSource();
  //nothing can be controlled without a connection; the server re-announces control when we return
  setControlAvailable(false);

  if (code === CLOSE_CODE_FORBIDDEN) {
    //nothing to retry: this does not change until someone edits the account on the device
    stoppedByServer = true;
    setConnectionState("state-error", "No access");
    showMessage("Not allowed to watch this screen",
      "This account does not have the right to view the device screen. It can be granted in the " +
      "server app, in the permissions of this user or of its role.", false);
    return;
  }
  if (code === CLOSE_CODE_NOT_ACTIVE) {
    stoppedByServer = true;
    setConnectionState("state-error", "Not sharing");
    showMessage("Screen sharing is not active",
      "The server is running, but it has no screen capture session. Android ends screen capture " +
      "whenever the device screen locks, and it can only be granted again on the device itself - " +
      "there is a notification on it to resume with one tap. This also happens when the server " +
      "was started without the capture prompt, after a reboot or from the widget.", true);
    return;
  }
  if (code === CLOSE_CODE_GOING_AWAY) {
    stoppedByServer = true;
    setConnectionState("state-error", "Stopped");
    showMessage("Screen sharing stopped",
      reason || "The device stopped sharing its screen.", true);
    return;
  }

  reconnectAttempts++;
  if (reconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
    setConnectionState("state-error", "Disconnected");
    showMessage("Connection lost",
      "Could not reconnect to the server. It may have been stopped, or the session may have " +
      "expired and need a new login.", true);
    return;
  }

  var delay = Math.min(RECONNECT_DELAY_MAX_MS,
    RECONNECT_DELAY_MIN_MS * Math.pow(2, reconnectAttempts - 1));
  setConnectionState("state-error", "Reconnecting…");
  showMessage("Connection lost",
    "Reconnecting in " + Math.round(delay / 1000) + " s…", false);
  reconnectTimer = setTimeout(connect, delay);
}

// --- media source ----------------------------------------------------------------------------

function startMediaSource(info) {
  if (!window.MediaSource) {
    stoppedByServer = true;
    closeSocket();
    setConnectionState("state-error", "Unsupported");
    showMessage("Not supported by this browser",
      "Watching the screen needs Media Source Extensions, which this browser does not provide.",
      false);
    return;
  }
  var mimeType = 'video/mp4; codecs="' + info.codec + '"';
  if (!MediaSource.isTypeSupported(mimeType)) {
    stoppedByServer = true;
    closeSocket();
    setConnectionState("state-error", "Unsupported");
    showMessage("Not supported by this browser", "This browser can not play " + mimeType, false);
    return;
  }

  releaseMediaSource();

  mediaSource = new MediaSource();
  mediaSourceUrl = URL.createObjectURL(mediaSource);
  video.src = mediaSourceUrl;
  mediaSource.addEventListener("sourceopen", function () {
    if (mediaSource === null) {
      return;
    }
    try {
      sourceBuffer = mediaSource.addSourceBuffer(mimeType);
      //segments carry their own decode times, so let them place themselves on the timeline.
      //Has to be set before the first append, setting it while updating throws
      sourceBuffer.mode = "segments";
    } catch (e) {
      onMediaError("Could not start decoding: " + e);
      return;
    }
    sourceBuffer.addEventListener("updateend", pumpOperations);
    sourceBuffer.addEventListener("error", function () {
      onMediaError("The browser rejected the video stream.");
    });
    pumpOperations();
  }, { once: true });
}

function releaseMediaSource() {
  pendingOperations = [];
  expectInitSegment = false;
  sourceBuffer = null;
  mediaSource = null;
  if (mediaSourceUrl !== null) {
    URL.revokeObjectURL(mediaSourceUrl);
    mediaSourceUrl = null;
  }
  if (video) {
    video.removeAttribute("src");
    video.load();
  }
}

function queueOperation(operation) {
  pendingOperations.push(operation);
}

/**
 * Runs the queued SourceBuffer operations one by one - appends and removes share the same buffer
 * and calling either one while it is busy throws.
 */
function pumpOperations() {
  if (sourceBuffer === null || sourceBuffer.updating) {
    return;
  }
  if (mediaSource === null || mediaSource.readyState !== "open") {
    return;
  }
  var operation = pendingOperations.shift();
  if (!operation) {
    return;
  }
  try {
    if (operation.type === "append") {
      sourceBuffer.appendBuffer(operation.data);
    } else {
      sourceBuffer.remove(operation.start, operation.end);
    }
  } catch (e) {
    if (e.name === "QuotaExceededError") {
      //the buffer is full: throw away what was already played and try the same append again
      pendingOperations.unshift(operation);
      if (!dropPlayedBuffer()) {
        //nothing to free, drop the incoming data instead of getting stuck
        pendingOperations.shift();
      }
      return;
    }
    onMediaError("Could not decode the stream: " + e);
    return;
  }
  hideMessage();
  setConnectionState("state-live", "Live");
  reconnectAttempts = 0;
  startPlaybackIfNeeded();
}

/**
 * @return true if something was removed
 */
function dropPlayedBuffer() {
  if (sourceBuffer === null || sourceBuffer.updating || !sourceBuffer.buffered.length) {
    return false;
  }
  var start = sourceBuffer.buffered.start(0);
  var end = Math.min(video.currentTime - 1, sourceBuffer.buffered.end(0));
  if (end <= start) {
    return false;
  }
  try {
    sourceBuffer.remove(start, end);
    return true;
  } catch (e) {
    return false;
  }
}

function evictOldBuffer() {
  if (sourceBuffer === null || !sourceBuffer.buffered.length || pendingOperations.length) {
    return;
  }
  var start = sourceBuffer.buffered.start(0);
  var end = video.currentTime - MAX_BUFFER_BEHIND_SECONDS;
  if (end - start < 1) {
    return;
  }
  queueOperation({ type: "remove", start: start, end: end });
  pumpOperations();
}

function onMediaError(message) {
  //a fresh stream is the only way back: ask the server to start one over from a key frame
  releaseMediaSource();
  setConnectionState("state-error", "Playback error");
  showMessage("Playback error", message, true);
  closeSocket();
  stoppedByServer = true;
}

// --- playback --------------------------------------------------------------------------------

function startPlaybackIfNeeded() {
  if (!video.paused) {
    return;
  }
  var promise = video.play();
  if (promise && promise.catch) {
    promise.catch(function () {
      //autoplay was refused even though the video is muted
      showMessage("Ready to play", "Playback needs a tap to start on this browser.", false);
      var button = document.getElementById("screen-retry");
      button.textContent = "PLAY";
      button.style.display = "";
      button.onclick = function () {
        button.onclick = null;
        button.textContent = "RETRY";
        video.play();
        hideMessage();
      };
    });
  }
}

/**
 * Keeps playback near the live edge and steps over holes in the buffer.
 * <p>
 * A hole appears whenever the server drops a backlog for a client that fell behind and resumes at
 * the next key frame. The browser only skips very small gaps on its own and would sit at the edge
 * of the first range forever.
 * <p>
 * Small delays are worked off by playing slightly faster rather than by seeking: a jump is visible
 * as a stutter, while a 10% higher rate is not, and on a live view the delay would otherwise just
 * stay for as long as the page is open.
 */
function syncPlayback() {
  if (!video || !video.buffered.length) {
    return;
  }
  var ranges = video.buffered;
  var last = ranges.length - 1;
  var time = video.currentTime;

  var current = -1;
  for (var i = 0; i < ranges.length; i++) {
    if (time >= ranges.start(i) - 0.05 && time <= ranges.end(i) + 0.05) {
      current = i;
      break;
    }
  }

  if (current === -1) {
    //outside everything we have: either the very first segments, or a late join whose timeline
    //starts well after zero. Join at the live edge - the older frames in the buffer are history
    jumpToLiveEdge(ranges, last);
    return;
  }

  if (current < last && time >= ranges.end(current) - 0.05) {
    video.currentTime = ranges.start(current + 1);
    return;
  }

  var behind = ranges.end(last) - time;
  if (behind > MAX_LATENCY_SECONDS) {
    jumpToLiveEdge(ranges, last);
  } else if (behind > TARGET_LATENCY_SECONDS * 2) {
    setPlaybackRate(CATCH_UP_PLAYBACK_RATE);
  } else if (behind <= TARGET_LATENCY_SECONDS) {
    setPlaybackRate(1);
  }
}

function jumpToLiveEdge(ranges, last) {
  video.currentTime = Math.max(ranges.start(last), ranges.end(last) - TARGET_LATENCY_SECONDS);
  setPlaybackRate(1);
}

function setPlaybackRate(rate) {
  if (video.playbackRate !== rate) {
    video.playbackRate = rate;
  }
}

// --- remote control --------------------------------------------------------------------------

function setupControlInput() {
  inputLayer = document.getElementById("screen-input");
  textField = document.getElementById("control-text");

  //a device that can not hover and points coarsely is a touch screen: no keys to forward, so the
  //text field is the only way to type. Anything else gets its real key presses sent instead
  usesOnScreenKeyboard = !!(window.matchMedia &&
    window.matchMedia("(hover: none) and (pointer: coarse)").matches);
  textField.classList.toggle("hidden", !usesOnScreenKeyboard);
  if (!usesOnScreenKeyboard) {
    document.addEventListener("keydown", onDocumentKeyDown);
  }

  inputLayer.addEventListener("pointerdown", onPointerDown);
  inputLayer.addEventListener("pointermove", onPointerMove);
  inputLayer.addEventListener("pointerup", onPointerUp);
  inputLayer.addEventListener("pointercancel", onPointerCancel);
  //a right click or a long press would otherwise pop up the browser's own menu over the device
  inputLayer.addEventListener("contextmenu", function (e) {
    e.preventDefault();
  });

  //a hidden page stops getting animation frames, so a drag in progress would freeze with the
  //finger still down on the device - end it instead of leaving it to the server's watchdog
  document.addEventListener("visibilitychange", function () {
    if (document.hidden) {
      endActiveStroke(true);
    }
  });

  //IMEs fire input events for half-typed words; only the committed text is worth sending
  textField.addEventListener("compositionstart", function () {
    composing = true;
  });
  textField.addEventListener("compositionend", function () {
    composing = false;
    flushTypedText();
  });
  textField.addEventListener("input", function () {
    if (!composing) {
      flushTypedText();
    }
  });
  textField.addEventListener("keydown", function (e) {
    if (e.key === "Enter") {
      sendControl({ type: "edit", action: "enter" });
      e.preventDefault();
    } else if (e.key === "Backspace") {
      //the field is emptied after every send, so there is nothing local left to delete
      sendControl({ type: "edit", action: "backspace" });
      e.preventDefault();
    }
  });
}

/** Sends what was typed and empties the field, so it never accumulates a shadow copy of the text. */
function flushTypedText() {
  var value = textField.value;
  if (!value) {
    return;
  }
  textField.value = "";
  sendControl({ type: "text", text: value });
}

function onControlStatus(info) {
  controlForbidden = info.reason === "forbidden";
  setControlAvailable(!!info.enabled);
  showControlHint(info.reason);
}

function setControlAvailable(available) {
  if (controlAvailable && !available) {
    //never leave a finger pressed on a device we are losing control of
    endActiveStroke(true);
  }
  controlAvailable = available;
  document.getElementById("control-buttons").classList.toggle("hidden", !available);
  inputLayer.classList.toggle("hidden", !available);
  if (!available) {
    textField.value = "";
  }
  updateControlProbe();
}

/**
 * While control is unavailable nothing is sent to the device, so the server has no occasion to tell
 * us that the user has granted it in the meantime. Ask now and then instead of leaving the page
 * dead until it is reloaded.
 */
function updateControlProbe() {
  if (controlAvailable || controlForbidden || socket === null) {
    if (controlProbeTimer !== null) {
      clearInterval(controlProbeTimer);
      controlProbeTimer = null;
    }
    return;
  }
  if (controlProbeTimer === null) {
    controlProbeTimer = setInterval(function () {
      if (socket !== null && socket.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify({ type: "status" }));
      }
    }, CONTROL_PROBE_INTERVAL_MS);
  }
}

function onGlobalActionClick(action) {
  sendControl({ type: "key", action: action });
}

/**
 * Forwards the keys pressed on this machine to the device.
 * <p>
 * Accessibility can only put text into whatever field has input focus on the device, so ordinary
 * characters travel as text and the few keys that are not characters travel as their own commands.
 * Shortcuts the browser itself owns (Ctrl+C, F5, Ctrl+Shift+I, ...) are deliberately left alone -
 * the only combinations claimed here are the ones for the device's own navigation.
 */
function onDocumentKeyDown(e) {
  if (!controlAvailable || e.isComposing) {
    return;
  }
  var tag = e.target && e.target.tagName;
  if (tag === "INPUT" || tag === "TEXTAREA") {
    //typing into a field of this page, not into the device
    return;
  }

  if (e.key === "Escape" || (e.key === "Backspace" && (e.ctrlKey || e.metaKey))) {
    sendControl({ type: "key", action: "back" });
    e.preventDefault();
    return;
  }
  if (e.ctrlKey && e.altKey && (e.key === "h" || e.key === "H")) {
    sendControl({ type: "key", action: "home" });
    e.preventDefault();
    return;
  }
  if (e.ctrlKey && e.altKey && (e.key === "r" || e.key === "R")) {
    sendControl({ type: "key", action: "recents" });
    e.preventDefault();
    return;
  }

  if (e.ctrlKey || e.metaKey || e.altKey) {
    //someone else's shortcut, leave it to the browser
    return;
  }

  if (e.key === "Backspace") {
    sendControl({ type: "edit", action: "backspace" });
    e.preventDefault();
    return;
  }
  if (e.key === "Enter") {
    sendControl({ type: "edit", action: "enter" });
    e.preventDefault();
    return;
  }
  if (e.key === "Tab") {
    //nothing on this page is worth tabbing to, and losing focus would stop the keys arriving
    e.preventDefault();
    return;
  }
  if (e.key.length === 1) {
    //a printable character - Space included, which would scroll the page if left alone
    sendControl({ type: "text", text: e.key });
    e.preventDefault();
  }
}

/**
 * Turns a position on the page into a fraction of the device screen. The video keeps its aspect
 * ratio inside the element (object-fit: contain), so the picture is generally smaller than the
 * element and touches in the black bars around it belong to nothing.
 */
function toNormalized(clientX, clientY) {
  var rect = video.getBoundingClientRect();
  if (!video.videoWidth || !video.videoHeight || !rect.width || !rect.height) {
    return null;
  }
  var scale = Math.min(rect.width / video.videoWidth, rect.height / video.videoHeight);
  var pictureWidth = video.videoWidth * scale;
  var pictureHeight = video.videoHeight * scale;
  var x = (clientX - (rect.left + (rect.width - pictureWidth) / 2)) / pictureWidth;
  var y = (clientY - (rect.top + (rect.height - pictureHeight) / 2)) / pictureHeight;
  if (x < 0 || x > 1 || y < 0 || y > 1) {
    return null;
  }
  return { x: x, y: y };
}

function onPointerDown(e) {
  if (!controlAvailable || activePointerId !== null) {
    return;
  }
  var point = toNormalized(e.clientX, e.clientY);
  if (point === null) {
    return;
  }
  activePointerId = e.pointerId;
  lastSentMove = point;
  pendingMove = null;
  try {
    //keeps the events coming even when the finger slides outside the video
    inputLayer.setPointerCapture(e.pointerId);
  } catch (ignored) {
    //not supported here, moves outside the element are simply lost
  }
  sendControl({ type: "touch", action: "down", x: point.x, y: point.y });
  e.preventDefault();
}

function onPointerMove(e) {
  if (e.pointerId !== activePointerId) {
    return;
  }
  var point = toNormalized(e.clientX, e.clientY);
  if (point !== null) {
    //only the newest position matters: sending every event would just queue up behind the device
    pendingMove = point;
    scheduleMoveFlush();
  }
  e.preventDefault();
}

function scheduleMoveFlush() {
  if (moveScheduled) {
    return;
  }
  moveScheduled = true;
  requestAnimationFrame(function () {
    moveScheduled = false;
    flushMove();
  });
}

function flushMove() {
  if (pendingMove === null || activePointerId === null) {
    return;
  }
  var point = pendingMove;
  pendingMove = null;
  if (lastSentMove !== null &&
      Math.abs(point.x - lastSentMove.x) < MOVE_MIN_DELTA &&
      Math.abs(point.y - lastSentMove.y) < MOVE_MIN_DELTA) {
    return;
  }
  lastSentMove = point;
  sendControl({ type: "touch", action: "move", x: point.x, y: point.y });
}

function onPointerUp(e) {
  if (e.pointerId !== activePointerId) {
    return;
  }
  var point = toNormalized(e.clientX, e.clientY) || lastSentMove;
  releasePointer(e.pointerId);
  if (point !== null) {
    sendControl({ type: "touch", action: "up", x: point.x, y: point.y });
  } else {
    sendControl({ type: "touch", action: "cancel" });
  }
  e.preventDefault();
}

function onPointerCancel(e) {
  if (e.pointerId !== activePointerId) {
    return;
  }
  releasePointer(e.pointerId);
  sendControl({ type: "touch", action: "cancel" });
}

function endActiveStroke(sendCancel) {
  if (activePointerId === null) {
    return;
  }
  var pointerId = activePointerId;
  releasePointer(pointerId);
  if (sendCancel) {
    sendControl({ type: "touch", action: "cancel" });
  }
}

function releasePointer(pointerId) {
  activePointerId = null;
  pendingMove = null;
  lastSentMove = null;
  try {
    inputLayer.releasePointerCapture(pointerId);
  } catch (ignored) {
    //the capture was never taken, or the pointer is already gone
  }
}

function sendControl(message) {
  if (!controlAvailable || socket === null || socket.readyState !== WebSocket.OPEN) {
    return;
  }
  try {
    socket.send(JSON.stringify(message));
  } catch (e) {
    //the close handler deals with a broken socket
  }
}

function showControlHint(reason) {
  var texts = {
    "disabled": "Remote control is switched off on the device",
    "forbidden": "This account is only allowed to watch, not to control the device",
    "busy": "Another client is controlling the device right now",
    "no-text-field": "No text field is focused on the device",
    "text-failed": "The app on the device refused the text",
    "unsupported": "The device's Android version does not support this action"
  };
  var text = texts[reason] || "";
  var hint = document.getElementById("control-hint");
  hint.textContent = text;
  if (controlHintTimer !== null) {
    clearTimeout(controlHintTimer);
    controlHintTimer = null;
  }
  if (text) {
    controlHintTimer = setTimeout(function () {
      controlHintTimer = null;
      hint.textContent = "";
    }, 5000);
  }
}

// --- UI --------------------------------------------------------------------------------------

function setConnectionState(cssClass, text) {
  var element = document.getElementById("connection-state");
  element.className = "connection-state" + (cssClass ? " " + cssClass : "");
  element.textContent = text;
}

function showMessage(title, text, showRetry) {
  document.getElementById("screen-message").classList.remove("hidden");
  document.getElementById("screen-message-title").textContent = title;
  document.getElementById("screen-message-text").textContent = text || "";
  var button = document.getElementById("screen-retry");
  button.style.display = showRetry ? "" : "none";
}

function hideMessage() {
  document.getElementById("screen-message").classList.add("hidden");
}

function updateStats() {
  var now = Date.now();
  if (statsWindowStart === 0) {
    statsWindowStart = now;
    return;
  }
  var elapsed = now - statsWindowStart;
  if (elapsed < 1000) {
    return;
  }

  var parts = [];
  if (video.videoWidth) {
    parts.push(video.videoWidth + "×" + video.videoHeight);
  } else if (streamInfo) {
    parts.push(streamInfo.width + "×" + streamInfo.height);
  }
  parts.push(Math.round(statsSegments * 1000 / elapsed) + " fps");
  parts.push(formatBitrate(statsBytes * 8 * 1000 / elapsed));
  if (video.buffered.length) {
    var behind = video.buffered.end(video.buffered.length - 1) - video.currentTime;
    parts.push(Math.max(0, Math.round(behind * 1000)) + " ms behind");
  }

  statsWindowStart = now;
  statsBytes = 0;
  statsSegments = 0;

  var text = parts.join(" · ");
  if (text !== lastStatsText) {
    lastStatsText = text;
    document.getElementById("screen-stats").textContent = text;
  }
}

function formatBitrate(bitsPerSecond) {
  if (bitsPerSecond >= 1000000) {
    return (bitsPerSecond / 1000000).toFixed(1) + " Mbit/s";
  }
  return Math.round(bitsPerSecond / 1000) + " kbit/s";
}

function onRetryClick() {
  stoppedByServer = false;
  reconnectAttempts = 0;
  connect();
}

function onFullscreenClick() {
  var container = document.getElementById("screen-container");
  if (document.fullscreenElement) {
    document.exitFullscreen();
  } else if (container.requestFullscreen) {
    container.requestFullscreen();
  } else if (video.webkitEnterFullscreen) {
    //iOS only allows the video element itself to go fullscreen
    video.webkitEnterFullscreen();
  }
}

function onMenuClick(e) {
  var menuButton = document.getElementById("menu-button");
  var mainMenu = document.getElementById("main-menu");
  var mmBackToFiles = document.getElementById("mm-back-to-files");
  if (mmBackToFiles) {
    mmBackToFiles.onclick = function () {
      window.location.href = "/?forceContents=true";
    }
  }
  var mmStatus = document.getElementById("mm-status");
  if (mmStatus) {
    mmStatus.onclick = function () {
      window.location.href = "/shttps-static-public/status/index.html";
    }
  }
  var rect = menuButton.getBoundingClientRect();
  displayContextMenuWithAnchorRect(rect, mainMenu);
  e.stopPropagation();
  e.preventDefault();
}
