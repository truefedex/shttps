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
var INITIAL_TARGET_LATENCY_SECONDS = 0.1;
/**
 * The target is not a constant: it follows how unevenly frames actually arrive, see
 * updateTargetLatency. On a local network that is a few milliseconds and the target sits at the
 * floor, about two frames; on a link that delivers in bursts it rises to what that link needs, and
 * no further than the ceiling - beyond it the picture is smoother as a slide show than as video.
 */
var MIN_TARGET_LATENCY_SECONDS = 0.06;
var MAX_TARGET_LATENCY_SECONDS = 0.4;
/** Room on top of the measured unevenness, a little over one frame at 30 fps. */
var TARGET_LATENCY_MARGIN_SECONDS = 0.04;
/** Further behind than this and catching up by playing faster would take too long: jump instead. */
var MAX_LATENCY_SECONDS = 0.5;
/**
 * How much faster than real time to play while catching up. Small enough to be invisible - jumping
 * would be seen as a stutter, and drifting a whole second behind is what this avoids.
 */
var CATCH_UP_PLAYBACK_RATE = 1.15;
/**
 * How far past the target playback may fall before it is sped up. More than a frame: the distance
 * to the live edge moves by one frame with every arrival, and a threshold inside that would have
 * the playback rate flipping ten times a second.
 */
var CATCH_UP_SLACK_SECONDS = 0.05;
/**
 * How much already played video to keep buffered before evicting it. Has to stay well above the
 * server's key frame interval (GOP_SECONDS, 8 s): removing a range also removes everything up to
 * the next key frame, since those frames can no longer be decoded, and that key frame must not be
 * ahead of what is playing.
 */
var MAX_BUFFER_BEHIND_SECONDS = 20.0;
/** The timescale FragmentedMp4Muxer writes: decode times in a segment are microseconds. */
var MEDIA_TIMESCALE = 1000000;
/** Arrivals the target latency is judged from - about ten seconds of a 30 fps stream. */
var ARRIVAL_WINDOW = 300;
/**
 * A frame arriving this much later than the ones before it is not an uneven link, it is the stream
 * having paused - a still Android screen produces no frames at all - and says nothing about how
 * much reserve playback needs.
 */
var ARRIVAL_DISCONTINUITY_SECONDS = 0.5;
var RECONNECT_DELAY_MIN_MS = 1000;
var RECONNECT_DELAY_MAX_MS = 15000;
var MAX_RECONNECT_ATTEMPTS = 10;
/** Close code the server uses when there is no capture session to subscribe to. */
var CLOSE_CODE_NOT_ACTIVE = 1008;
/** Close code the server uses when the capture session ended. */
var CLOSE_CODE_GOING_AWAY = 1001;
/** Close code the server uses when this user may not watch the screen at all. */
var CLOSE_CODE_FORBIDDEN = 4403;
/** Close code the server uses when the person at the device refused to let us in. */
var CLOSE_CODE_ACCESS_DENIED = 4401;
/** Close code the server uses when nobody at the device answered our request in time. */
var CLOSE_CODE_ACCESS_TIMEOUT = 4408;

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
/** How far behind the newest frame playback aims to sit right now. */
var targetLatency = INITIAL_TARGET_LATENCY_SECONDS;
/** For each recent segment, when it arrived minus where it sits on the media timeline. */
var arrivalOffsets = [];
var arrivalsSinceUpdate = 0;

var QUALITY_STORAGE_KEY = "shttps.screen.quality";
var QUALITY_LEVELS = ["low", "medium", "high"];
/** How long a window has to hold still before its new size is worth restarting an encoder for. */
var VIEWPORT_SETTLE_MS = 1500;
/** What this viewer asked for: "auto" or one of QUALITY_LEVELS. Remembered across visits. */
var requestedQuality = loadQualityPreference();
/** The last {"type":"stream"} frame, or null while the server has not said it offers a choice. */
var streamStatus = null;
var viewportTimer = null;
var lastSentViewport = "";

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
  setupFrameDecoder();

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

  window.addEventListener("resize", scheduleViewportReport);
  //the arrow is placed in page coordinates, which a change of layout moves under it
  window.addEventListener("resize", updateRemoteCursor);
  document.addEventListener("fullscreenchange", updateRemoteCursor);
  document.addEventListener("fullscreenchange", scheduleViewportReport);

  setupControlInput();
  updateRecordUi();
  connect();
}

// --- stream quality --------------------------------------------------------------------------

function loadQualityPreference() {
  try {
    var stored = window.localStorage.getItem(QUALITY_STORAGE_KEY);
    if (stored === "auto" || QUALITY_LEVELS.indexOf(stored) >= 0) {
      return stored;
    }
  } catch (e) {
    //storage can be switched off; the choice then simply lasts for this visit
  }
  return "auto";
}

/**
 * How many pixels the picture can actually occupy here, which the server uses to avoid encoding a
 * larger one than any window is there to show. Device pixels, not CSS ones: on a high density
 * display a 1280 wide window has 2560 of them and a 1280 wide picture is visibly soft in it.
 */
function viewportPixels() {
  var container = document.getElementById("screen-container");
  var ratio = window.devicePixelRatio || 1;
  var width = container ? container.clientWidth : window.innerWidth;
  var height = container ? container.clientHeight : window.innerHeight;
  return [Math.round(width * ratio), Math.round(height * ratio)];
}

function streamQuery() {
  var size = viewportPixels();
  lastSentViewport = size.join("x");
  //cursor=1: this page can draw the remote mouse pointer itself, see screen-cursor.js
  return "?quality=" + encodeURIComponent(requestedQuality) + "&vw=" + size[0] + "&vh=" + size[1] +
    "&cursor=1";
}

function sendStreamRequest() {
  //only to a server that has said it listens: an older one would take this for a malformed
  //input command, harmlessly, but there is nothing to gain from sending it
  if (streamStatus === null || socket === null || socket.readyState !== WebSocket.OPEN) {
    return;
  }
  var size = viewportPixels();
  lastSentViewport = size.join("x");
  socket.send(JSON.stringify({ type: "stream", quality: requestedQuality, viewport: size }));
}

function scheduleViewportReport() {
  if (viewportTimer !== null) {
    clearTimeout(viewportTimer);
  }
  viewportTimer = setTimeout(function () {
    viewportTimer = null;
    if (viewportPixels().join("x") !== lastSentViewport) {
      sendStreamRequest();
    }
  }, VIEWPORT_SETTLE_MS);
}

function onQualityChange() {
  requestedQuality = document.getElementById("quality-select").value;
  try {
    window.localStorage.setItem(QUALITY_STORAGE_KEY, requestedQuality);
  } catch (e) {
    //see loadQualityPreference
  }
  sendStreamRequest();
}

/** The server said what the stream carries now, and with that, that there is a choice at all. */
function onStreamStatus(info) {
  streamStatus = info;
  var select = document.getElementById("quality-select");
  select.classList.remove("hidden");
  //the server's own limit: levels above it would be accepted and then quietly not given
  var max = QUALITY_LEVELS.indexOf(info.max);
  for (var i = 0; i < select.options.length; i++) {
    var level = QUALITY_LEVELS.indexOf(select.options[i].value);
    select.options[i].disabled = max >= 0 && level > max;
  }
  if (info.requested) {
    select.value = info.requested;
  }
}

function hideQualityChoice() {
  streamStatus = null;
  //until a server says otherwise the pointer is part of the picture
  setCursorOverlay(false);
  document.getElementById("quality-select").classList.add("hidden");
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
  hideQualityChoice();

  setConnectionState("", "Connecting…");
  showMessage("Connecting…", "", false);

  var protocol = location.protocol === "https:" ? "wss:" : "ws:";
  try {
    //what this viewer wants goes into the handshake, so that the stream starts as that rather
    //than as the default and then restarts a moment later
    socket = new WebSocket(protocol + "//" + location.host + STREAM_PATH + streamQuery());
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
      //whatever was playing belongs to the stream that just ended. Which of the two ways the new
      //one is shown is decided when its initialization segment arrives, since the decoder's
      //configuration is inside it
      closeFrameDecoder();
      releaseMediaSource();
      //after the tear down, not before: it clears this flag
      expectInitSegment = true;
    } else if (info.type === "control") {
      onControlStatus(info);
    } else if (info.type === "stream") {
      onStreamStatus(info);
      setCursorOverlay(info.cursor === "overlay");
    } else if (info.type === "cursor") {
      onRemoteCursor(info);
    } else if (info.type === "approval" && info.state === "pending") {
      //the connection stays open while the person at the device decides; the stream follows if
      //they say yes, and the message goes away with its first frame
      setConnectionState("", "Waiting for approval");
      showMessage("Waiting for approval",
        "The device asks its owner whether to let you see the screen. This page continues as " +
        "soon as they allow it" + (info.timeout ? " - the question expires after " +
        info.timeout + " s." : "."), false);
    }
    return;
  }

  var data = new Uint8Array(event.data);
  receivedBytes += data.length;
  statsBytes += data.length;
  if (expectInitSegment) {
    expectInitSegment = false;
    //decoded here if the browser lets us, played by the video element otherwise. Starting the
    //latter tears down what was there, which is why the segment is only remembered afterwards
    var decoding = startFrameDecoder(streamInfo, data);
    if (!decoding) {
      startMediaSource(streamInfo);
    }
    //kept so that recording can be started at any time, not only when a stream begins
    latestInitSegment = data.slice();
    updateRecordUi();
    if (!decoding) {
      queueOperation({ type: "append", data: data });
      pumpOperations();
    }
    return;
  }
  receivedSegments++;
  statsSegments++;
  noteSegmentArrival(data);
  if (recordState === RECORD_WAITING || recordState === RECORD_RUNNING) {
    captureSegment(data);
  }
  if (usingFrameDecoder()) {
    decodeSegment(data);
  } else {
    queueOperation({ type: "append", data: data });
    pumpOperations();
  }
}

/** The element the picture is in: the canvas while frames are decoded here, the video otherwise. */
function pictureElement() {
  return usingFrameDecoder() ? canvas : video;
}

function pictureWidth() {
  return usingFrameDecoder() ? canvas.width : video.videoWidth;
}

function pictureHeight() {
  return usingFrameDecoder() ? canvas.height : video.videoHeight;
}

function onDisconnected(code, reason) {
  closeSocket();
  closeFrameDecoder();
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
  if (code === CLOSE_CODE_ACCESS_DENIED) {
    //reconnecting on our own would only ask again and again
    stoppedByServer = true;
    setConnectionState("state-error", "Denied");
    showMessage("Access denied",
      "The owner of the device declined to let you see its screen.", true);
    return;
  }
  if (code === CLOSE_CODE_ACCESS_TIMEOUT) {
    stoppedByServer = true;
    setConnectionState("state-error", "No answer");
    showMessage("No answer from the device",
      "Nobody at the device answered the request to see its screen in time. Retry to ask again.",
      true);
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
  if (pictureWidth()) {
    parts.push(pictureWidth() + "×" + pictureHeight());
  } else if (streamInfo) {
    parts.push(streamInfo.width + "×" + streamInfo.height);
  }
  if (streamStatus && streamStatus.requested === "auto" && streamStatus.quality) {
    //on auto the menu says nothing about where it currently stands
    parts.push(streamStatus.quality);
  }
  parts.push(Math.round(statsSegments * 1000 / elapsed) + " fps");
  parts.push(formatBitrate(statsBytes * 8 * 1000 / elapsed));
  if (usingFrameDecoder()) {
    //there is no reserve of video to be behind in; what can pile up is undecoded frames
    parts.push("direct" + (frameDecoder.decodeQueueSize ? ", " + frameDecoder.decodeQueueSize +
      " queued" : ""));
  } else if (video.buffered.length) {
    var behind = video.buffered.end(video.buffered.length - 1) - video.currentTime;
    //the target next to it is what tells "the link is uneven, so this is deliberate" apart from
    //"playback has fallen behind"
    parts.push(Math.max(0, Math.round(behind * 1000)) + " ms behind (target " +
      Math.round(targetLatency * 1000) + ")");
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
