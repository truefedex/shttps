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

var receivedBytes = 0;
var receivedSegments = 0;
var statsWindowStart = 0;
var statsBytes = 0;
var statsSegments = 0;
var lastStatsText = "";

function onPageLoad() {
  setupMainMenu({
    "mm-back-to-files": "/?forceContents=true",
    "mm-status": "/shttps-static-public/status/index.html"
  });
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
  updateRecordUi();
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
  controlUnsupportedByPlatform = false;
  inputFamilies = {};
  inputFamiliesKnown = false;
  sawDesktopPlatform = false;

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
      //what the far end looks like, not what it accepts - the control frame says that. Remembered
      //only to make sense of an old server, which sends no family list at all.
      sawDesktopPlatform = info.platform === "desktop";
      //after starting the new source, not before: tearing the old one down clears this flag
      startMediaSource(info);
      expectInitSegment = true;
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
    //kept so that recording can be started at any time, not only when a stream begins
    latestInitSegment = data.slice();
    updateRecordUi();
  } else {
    receivedSegments++;
    statsSegments++;
    if (recordState === RECORD_WAITING || recordState === RECORD_RUNNING) {
      captureSegment(data);
    }
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