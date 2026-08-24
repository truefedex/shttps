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
/**
 * True when the machine being shared has no remote control to offer at all, as opposed to having
 * it switched off. Told apart because "switched off" is worth a hint and worth asking about again,
 * while "there is no such thing here" is neither.
 *
 * Only ever set for a server old enough not to send the input family list at all, which is always
 * a desktop one from before desktop control existed. A server that does send the list says what it
 * has, empty included, and an empty list means "not right now" rather than "never".
 */
var controlUnsupportedByPlatform = false;
/**
 * Which kinds of input the machine accepts, keyed by the "type" of the command that carries them:
 * a phone offers touch and its own Back button, a PC offers a mouse, a wheel and real keys. The
 * server sends the list with every control frame.
 */
var inputFamilies = {};
/** Whether the server has told us at all - false against one built before families existed. */
var inputFamiliesKnown = false;
/** What the init frame said the far end is. Only used to interpret an old server's silence. */
var sawDesktopPlatform = false;
var inputLayer = null;
var textField = null;
var captureButton = null;
/**
 * Whether this machine's keys are being forwarded instead of acted on locally. Off by default and
 * toggled deliberately: while it is on the page swallows Ctrl+C, F5 and the rest, which is the
 * whole point and also why there has to be an obvious way out.
 */
var keyboardCaptured = false;
/** Mouse buttons currently held down on the remote machine, so they can all be released at once. */
var heldButtons = [];
/** Accumulated wheel notches waiting to be sent, coalesced like moves are. */
var pendingWheel = null;
var wheelScheduled = false;
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
/**
 * The same for a mouse, where it has to be far smaller: a finger cannot aim at a single pixel but a
 * pointer can, and a dead zone of two pixels makes precise work on a remote desktop impossible.
 */
var MOUSE_MOVE_MIN_DELTA = 0.0005;
/** Releases keyboard capture. Deliberately awkward, so that nothing types it by accident. */
var RELEASE_CAPTURE_KEY = "Escape";

/** @return whether the machine accepts a kind of input, e.g. "mouse" or "touch". */
function hasInput(name) {
  return inputFamilies[name] === true;
}

/** True while the pointer should be driving a mouse rather than a touch screen. */
function inMouseMode() {
  return hasInput("mouse");
}
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
  //a recording belongs to the stream it was started on: a new encoder session brings its own
  //parameter sets and a timeline back at zero, neither of which can be appended to this file
  finishRecording("Recording stopped: the stream restarted");
  latestInitSegment = null;
  updateRecordUi();

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

// --- recording -------------------------------------------------------------------------------

/*
 * What arrives over the socket is already a complete MP4 stream, so recording it is a matter of
 * keeping the bytes rather than re-encoding what the video element shows. Two things have to be
 * fixed up on the way, both in the fragment headers:
 *
 *   - the decode times count from the start of the capture session, which may be hours before this
 *     recording, and they skip whatever the server dropped for a client that fell behind. The
 *     samples are laid out back to back instead, so the file starts at zero and has no gap.
 *   - the movie header declares no duration, because a live stream has no end. It is filled in
 *     from the recorded samples when the file is put together.
 */

/** trun sample_flags of a frame that decodes on its own - where a recording has to start. */
var SAMPLE_FLAGS_KEY_FRAME = 0x02000000;
/** trun flags the server writes: data offset | sample duration | sample size | sample flags. */
var TRUN_FLAGS = 0x000701;
/** The stream's timescale: decode times and sample durations are microseconds. */
var MEDIA_TIMESCALE = 1000000;
/** Largest value the 32 bit duration fields of the movie header can hold. */
var MAX_U32 = 4294967295;
/**
 * Everything recorded is held in memory until it is saved. At the bitrates this stream runs at
 * (about 15 MB per minute at 2 Mbit/s) this is upwards of half an hour, and stopping at a limit is
 * friendlier than letting the tab be killed.
 */
var MAX_RECORDING_BYTES = 512 * 1024 * 1024;

var RECORD_IDLE = 0;
/** Recording was asked for, but no key frame has arrived yet to start it at. */
var RECORD_WAITING = 1;
var RECORD_RUNNING = 2;
/** Stopped with something worth saving: waiting for the user to download or discard it. */
var RECORD_DONE = 3;

var recordState = RECORD_IDLE;
/** Initialization segment of the stream currently playing, kept so recording can start any time. */
var latestInitSegment = null;
/** The one belonging to the recording - the stream may be reinitialized while it waits to be saved. */
var recordInitSegment = null;
var recordChunks = [];
var recordBytes = 0;
/** Sum of the sample durations written so far, which is also the next fragment's decode time. */
var recordDurationUs = 0;
var recordStartedAt = 0;
var recordTimer = null;

function onRecordClick() {
  if (latestInitSegment === null) {
    showHint("There is no picture to record yet");
    return;
  }
  recordInitSegment = latestInitSegment;
  recordChunks = [];
  recordBytes = 0;
  recordDurationUs = 0;
  setRecordState(RECORD_WAITING);
  //a recording has to start at a key frame; ask for one instead of waiting for the encoder's own
  requestKeyFrame();
  recordStartedAt = Date.now();
  updateRecordTime();
  recordTimer = setInterval(updateRecordTime, 500);
}

function onRecordStopClick() {
  finishRecording("");
}

function onRecordDownloadClick() {
  downloadRecording();
  discardRecording();
}

function onRecordDiscardClick() {
  discardRecording();
}

/**
 * Unlike everything else sent up this socket, this one is a bare word rather than JSON, and it is
 * answered whether or not this account may control the device.
 */
function requestKeyFrame() {
  if (socket === null || socket.readyState !== WebSocket.OPEN) {
    return;
  }
  try {
    socket.send("request-key-frame");
  } catch (e) {
    //the close handler deals with a broken socket
  }
}

/**
 * Keeps one media segment, rewriting its decode time so that the recording's samples follow each
 * other without a gap.
 */
function captureSegment(data) {
  var boxes = findFragmentBoxes(data);
  if (boxes === null) {
    //not a fragment of the shape this knows how to rewrite; keeping it would produce a broken file
    return;
  }
  if (recordState === RECORD_WAITING) {
    if (readU32(data, boxes.flagsOffset) !== SAMPLE_FLAGS_KEY_FRAME) {
      return;
    }
    setRecordState(RECORD_RUNNING);
  }

  var copy = data.slice();
  writeU64(copy, boxes.decodeTimeOffset, recordDurationUs);
  recordDurationUs += readU32(data, boxes.durationOffset);
  recordChunks.push(copy);
  recordBytes += copy.length;

  if (recordBytes >= MAX_RECORDING_BYTES) {
    finishRecording("Recording stopped at " + Math.round(MAX_RECORDING_BYTES / (1024 * 1024)) +
      " MB - save it and start another one");
  }
}

/**
 * Ends a recording in progress, whatever asked for it - the user, the size limit, or the stream
 * being torn down. What was captured stays available to download; a recording that never saw its
 * first key frame has nothing to offer and is dropped.
 *
 * @param hint text to show next to the buttons when the recording was not stopped by the user
 */
function finishRecording(hint) {
  if (recordState !== RECORD_WAITING && recordState !== RECORD_RUNNING) {
    return;
  }
  stopRecordTimer();
  if (recordChunks.length === 0) {
    //stopped, or interrupted, while still waiting for a frame that decodes on its own
    discardRecording();
    showHint("Nothing was recorded: the stream had not reached a key frame yet");
    return;
  }
  setRecordState(RECORD_DONE);
  if (hint) {
    showHint(hint);
  }
}

function discardRecording() {
  stopRecordTimer();
  recordChunks = [];
  recordBytes = 0;
  recordDurationUs = 0;
  recordInitSegment = null;
  setRecordState(RECORD_IDLE);
}

function stopRecordTimer() {
  if (recordTimer !== null) {
    clearInterval(recordTimer);
    recordTimer = null;
  }
}

function downloadRecording() {
  if (recordInitSegment === null || recordChunks.length === 0) {
    return;
  }
  var parts = [withDeclaredDuration(recordInitSegment, recordDurationUs)];
  for (var i = 0; i < recordChunks.length; i++) {
    parts.push(recordChunks[i]);
  }
  var url = URL.createObjectURL(new Blob(parts, { type: "video/mp4" }));
  var link = document.createElement("a");
  link.href = url;
  link.download = "screen-" + recordingFileTimestamp() + ".mp4";
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  //the save does not start synchronously: revoking right away cancels it in some browsers
  setTimeout(function () {
    URL.revokeObjectURL(url);
  }, 30000);
}

function recordingFileTimestamp() {
  var now = new Date();
  return now.getFullYear() + pad2(now.getMonth() + 1) + pad2(now.getDate()) + "-" +
    pad2(now.getHours()) + pad2(now.getMinutes()) + pad2(now.getSeconds());
}

function pad2(value) {
  return (value < 10 ? "0" : "") + value;
}

function updateRecordTime() {
  var seconds = Math.max(0, Math.floor((Date.now() - recordStartedAt) / 1000));
  var minutes = Math.floor(seconds / 60);
  var hours = Math.floor(minutes / 60);
  var text = (minutes - hours * 60) + ":" + pad2(seconds - minutes * 60);
  if (hours > 0) {
    text = hours + ":" + text;
  }
  document.getElementById("record-time").textContent = text;
}

function setRecordState(state) {
  recordState = state;
  updateRecordUi();
}

function updateRecordUi() {
  var recording = recordState === RECORD_WAITING || recordState === RECORD_RUNNING;
  var done = recordState === RECORD_DONE;
  toggleHidden("record-button", recording || done);
  toggleHidden("record-stop-button", !recording);
  toggleHidden("record-time", !recording);
  toggleHidden("record-download-button", !done);
  toggleHidden("record-discard-button", !done);
  //nothing to record until a stream is playing
  document.getElementById("record-button").disabled = latestInitSegment === null;
}

function toggleHidden(id, hidden) {
  document.getElementById(id).classList.toggle("hidden", hidden);
}

// --- MP4 boxes -------------------------------------------------------------------------------

/**
 * Locates the fields of a media segment that have to be read or rewritten: the fragment's decode
 * time, and the duration and flags of its single sample.
 * <p>
 * The boxes are walked rather than addressed by fixed offsets. The server writes a completely
 * regular layout today, but a silently broken recording is a bad way to find out that it changed.
 *
 * @return the byte offsets, or null if this is not a fragment of the expected shape
 */
function findFragmentBoxes(data) {
  var moof = findBox(data, 0, data.length, "moof");
  if (moof === null) {
    return null;
  }
  var traf = findBox(data, moof.start, moof.end, "traf");
  if (traf === null) {
    return null;
  }
  var tfdt = findBox(data, traf.start, traf.end, "tfdt");
  var trun = findBox(data, traf.start, traf.end, "trun");
  if (tfdt === null || trun === null) {
    return null;
  }
  //the decode time is 64 bit in version 1 of tfdt and 32 bit in version 0
  if (data[tfdt.start] !== 1) {
    return null;
  }
  //which per-sample fields a trun carries depends on its flags, and how many of them on the count
  if ((readU32(data, trun.start) & 0xffffff) !== TRUN_FLAGS || readU32(data, trun.start + 4) !== 1) {
    return null;
  }
  return {
    //version and flags, then the decode time
    decodeTimeOffset: tfdt.start + 4,
    //version and flags, sample count, data offset, then the sample's duration, size and flags
    durationOffset: trun.start + 12,
    flagsOffset: trun.start + 20
  };
}

/**
 * Copies an initialization segment with the duration of the recording written into it.
 * <p>
 * The muxer leaves the duration at zero because a live stream has no end. Browsers cope by scanning
 * the fragments, but most desktop and mobile players show such a file as 0:00 and refuse to seek in
 * it, which on a downloaded recording looks like a broken file.
 */
function withDeclaredDuration(initSegment, durationUs) {
  var copy = initSegment.slice();
  var moov = findBox(copy, 0, copy.length, "moov");
  if (moov === null) {
    return copy;
  }
  var movieTimescale = MEDIA_TIMESCALE;
  var mvhd = findBox(copy, moov.start, moov.end, "mvhd");
  if (mvhd !== null && copy[mvhd.start] === 0) {
    //version and flags, creation and modification time, timescale, then the duration
    movieTimescale = readU32(copy, mvhd.start + 12) || MEDIA_TIMESCALE;
    writeU32(copy, mvhd.start + 16, scaleDuration(durationUs, movieTimescale));
  }
  var trak = findBox(copy, moov.start, moov.end, "trak");
  if (trak === null) {
    return copy;
  }
  var tkhd = findBox(copy, trak.start, trak.end, "tkhd");
  if (tkhd !== null && copy[tkhd.start] === 0) {
    //a track's duration is expressed in the movie's timescale, not in the track's own
    writeU32(copy, tkhd.start + 20, scaleDuration(durationUs, movieTimescale));
  }
  var mdia = findBox(copy, trak.start, trak.end, "mdia");
  if (mdia === null) {
    return copy;
  }
  var mdhd = findBox(copy, mdia.start, mdia.end, "mdhd");
  if (mdhd !== null && copy[mdhd.start] === 0) {
    var mediaTimescale = readU32(copy, mdhd.start + 12) || MEDIA_TIMESCALE;
    writeU32(copy, mdhd.start + 16, scaleDuration(durationUs, mediaTimescale));
  }
  return copy;
}

/**
 * These header fields are 32 bits wide, which runs out after about 71 minutes of microseconds. A
 * recording that long keeps all of its frames; only the duration it declares stops growing.
 */
function scaleDuration(durationUs, timescale) {
  return Math.min(MAX_U32, Math.round(durationUs / MEDIA_TIMESCALE * timescale));
}

/**
 * Finds a box by its type among the boxes between two offsets.
 *
 * @return the range of the box contents, without its size and type, or null if there is no such box
 */
function findBox(data, start, end, type) {
  var offset = start;
  while (offset + 8 <= end) {
    var size = readU32(data, offset);
    //size 0 means "to the end of the file" and 1 means a 64 bit size follows - this stream uses
    //neither, and walking past a box of unknown length would read nonsense
    if (size < 8 || offset + size > end) {
      return null;
    }
    if (data[offset + 4] === type.charCodeAt(0) && data[offset + 5] === type.charCodeAt(1) &&
        data[offset + 6] === type.charCodeAt(2) && data[offset + 7] === type.charCodeAt(3)) {
      return { start: offset + 8, end: offset + size };
    }
    offset += size;
  }
  return null;
}

function readU32(data, offset) {
  return ((data[offset] << 24) | (data[offset + 1] << 16) | (data[offset + 2] << 8) |
    data[offset + 3]) >>> 0;
}

function writeU32(data, offset, value) {
  data[offset] = (value >>> 24) & 0xff;
  data[offset + 1] = (value >>> 16) & 0xff;
  data[offset + 2] = (value >>> 8) & 0xff;
  data[offset + 3] = value & 0xff;
}

function writeU64(data, offset, value) {
  //microseconds of recorded video stay far inside the range a double holds exactly
  var high = Math.floor(value / 4294967296);
  writeU32(data, offset, high);
  writeU32(data, offset + 4, value - high * 4294967296);
}

// --- remote control --------------------------------------------------------------------------

function setupControlInput() {
  inputLayer = document.getElementById("screen-input");
  textField = document.getElementById("control-text");
  captureButton = document.getElementById("keyboard-capture");

  //a device that can not hover and points coarsely is a touch screen: no keys to forward, so the
  //text field is the only way to type. Anything else gets its real key presses sent instead
  usesOnScreenKeyboard = !!(window.matchMedia &&
    window.matchMedia("(hover: none) and (pointer: coarse)").matches);
  textField.classList.toggle("hidden", !usesOnScreenKeyboard);
  if (!usesOnScreenKeyboard) {
    document.addEventListener("keydown", onDocumentKeyDown);
    //only a machine with real keys can forward them, and only a keyup can end a held modifier
    document.addEventListener("keyup", onDocumentKeyUp);
  }

  inputLayer.addEventListener("pointerdown", onPointerDown);
  inputLayer.addEventListener("pointermove", onPointerMove);
  inputLayer.addEventListener("pointerup", onPointerUp);
  inputLayer.addEventListener("pointercancel", onPointerCancel);
  //a wheel over a remote desktop scrolls it, not this page
  inputLayer.addEventListener("wheel", onWheel, { passive: false });
  //clicking the picture is the natural way to start driving it
  inputLayer.addEventListener("pointerdown", function () {
    if (hasInput("keyboard") && !keyboardCaptured) {
      setKeyboardCaptured(true);
    }
  });

  //a page that is no longer in front cannot see the keys come up, so anything held would stay
  //held on the remote machine - this is the first line of defence against a stuck modifier, the
  //server's watchdog being the last
  window.addEventListener("blur", function () {
    releaseEverythingHeld();
  });
  //a right click or a long press would otherwise pop up the browser's own menu over the device
  inputLayer.addEventListener("contextmenu", function (e) {
    e.preventDefault();
  });

  //a hidden page stops getting animation frames, so a drag in progress would freeze with the
  //finger still down on the device - end it instead of leaving it to the server's watchdog
  document.addEventListener("visibilitychange", function () {
    if (document.hidden) {
      endActiveStroke(true);
      releaseEverythingHeld();
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
  if (Array.isArray(info.input)) {
    //the authoritative answer, resent whenever it changes. Its presence alone says this server
    //knows about input families, so an empty list means "nothing right now", not "nothing ever".
    setInputFamilies(info.input);
  } else if (!inputFamiliesKnown && sawDesktopPlatform) {
    //a server from before families existed: the only one of those that streams a desktop is one
    //that never took input, so there is nothing to wait for
    controlUnsupportedByPlatform = true;
  }
  setControlAvailable(!!info.enabled);
  if (controlUnsupportedByPlatform && !info.enabled) {
    //nothing to explain and nothing to wait for: this machine has no remote control to grant
    return;
  }
  showControlHint(info.reason);
}

function setInputFamilies(list) {
  inputFamilies = {};
  for (var i = 0; i < list.length; i++) {
    inputFamilies[list[i]] = true;
  }
  inputFamiliesKnown = true;
  controlUnsupportedByPlatform = false;
}

function setControlAvailable(available) {
  if (controlAvailable && !available) {
    //never leave a finger pressed - or a Ctrl key held - on a machine we are losing control of
    endActiveStroke(true);
    setKeyboardCaptured(false);
  }
  controlAvailable = available;

  //each piece of the toolbar belongs to a kind of input, so a phone gets its Back button and a PC
  //gets the keyboard switch, and neither is offered what the other end cannot carry out
  document.getElementById("control-buttons")
    .classList.toggle("hidden", !(available && hasInput("key")));
  textField.classList.toggle("hidden",
    !(available && usesOnScreenKeyboard && hasInput("text")));
  //a phone has no keys of its own to forward, so it gets the text field above instead
  document.getElementById("desktop-controls").classList.toggle("hidden",
    !(available && hasInput("keyboard") && !usesOnScreenKeyboard));
  inputLayer.classList.toggle("hidden",
    !(available && (hasInput("touch") || hasInput("mouse"))));
  //the remote cursor is already drawn into the picture, so showing ours too gives two pointers
  //chasing each other across the screen
  inputLayer.classList.toggle("mouse-mode", inMouseMode());

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
  if (controlAvailable || controlForbidden || controlUnsupportedByPlatform || socket === null) {
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
 * Turns keyboard forwarding on or off, and says so plainly.
 *
 * While it is on this page stops being a web page: Ctrl+W, F5, Alt+Tab and the rest go to the other
 * machine instead of doing what they normally would here. That is the point of it, and it is also
 * why it is never on by default and why the banner naming the way out stays on screen the whole
 * time. A few chords - Ctrl+W and Ctrl+N among them - the browser keeps for itself whatever this
 * page asks, which is the other reason the button exists.
 */
function setKeyboardCaptured(captured) {
  var wanted = captured && controlAvailable && hasInput("keyboard");
  if (wanted === keyboardCaptured) {
    return;
  }
  keyboardCaptured = wanted;
  if (!keyboardCaptured) {
    //whatever was held is this page responsibility until it says otherwise
    sendControl({ type: "keyboard", action: "reset" });
  }
  captureButton.textContent = keyboardCaptured ? "RELEASE KEYBOARD" : "CAPTURE KEYBOARD";
  captureButton.classList.toggle("active", keyboardCaptured);
}

function onKeyboardCaptureClick() {
  setKeyboardCaptured(!keyboardCaptured);
}

/** The chord that gives this machine its keyboard back, checked before anything is forwarded. */
function isReleaseChord(e) {
  return e.key === RELEASE_CAPTURE_KEY && e.ctrlKey && e.altKey && e.shiftKey;
}

/**
 * Forwards the keys pressed on this machine to a remote desktop.
 *
 * Printable characters travel as text rather than as key presses, so that the two machines having
 * different keyboard layouts cannot garble what was typed. Anything with a modifier held travels as
 * the physical key instead, because that is what makes a shortcut compose correctly on the far side
 * - Ctrl and the key in the position of C, whatever that key produces there.
 */
function onRemoteKeyDown(e) {
  if (isReleaseChord(e)) {
    setKeyboardCaptured(false);
    e.preventDefault();
    return;
  }
  var modified = e.ctrlKey || e.altKey || e.metaKey;
  if (!modified && !e.isComposing && e.key.length === 1) {
    sendControl({ type: "text", text: e.key });
  } else {
    sendControl({ type: "keyboard", action: "down", code: e.code });
  }
  //everything, deliberately: a remote desktop that cannot be sent Ctrl+C is not much of one
  e.preventDefault();
}

function onRemoteKeyUp(e) {
  //text is sent whole, so only the keys that went down as keys have an up worth sending. Sending
  //an up for a key that never went down would be harmless, but a modifier release must never be
  //missed, and this way the two always pair.
  sendControl({ type: "keyboard", action: "up", code: e.code });
  e.preventDefault();
}

function onDocumentKeyUp(e) {
  if (!controlAvailable || !keyboardCaptured) {
    return;
  }
  var tag = e.target && e.target.tagName;
  if (tag === "INPUT" || tag === "TEXTAREA") {
    return;
  }
  onRemoteKeyUp(e);
}

/**
 * Forwards the keys pressed on this machine to the device.
 * <p>
 * Accessibility can only put text into whatever field has input focus on the device, so ordinary
 * characters travel as text and the few keys that are not characters travel as their own commands.
 * Shortcuts the browser itself owns (Ctrl+C, F5, Ctrl+Shift+I, ...) are deliberately left alone -
 * the only combinations claimed here are the ones for the device's own navigation.
 * <p>
 * A machine with a real keyboard takes the other branch entirely: there the whole keyboard is
 * forwarded, once the user has asked for it.
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

  if (hasInput("keyboard")) {
    if (keyboardCaptured) {
      onRemoteKeyDown(e);
    }
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

/** Which mouse button an event is about, in the names the protocol uses. */
function buttonName(e) {
  switch (e.button) {
    case 1: return "middle";
    case 2: return "right";
    case 3: return "back";
    case 4: return "forward";
    default: return "left";
  }
}

function onPointerDown(e) {
  if (!controlAvailable) {
    return;
  }
  var point = toNormalized(e.clientX, e.clientY);
  if (point === null) {
    return;
  }
  if (inMouseMode()) {
    var button = buttonName(e);
    if (heldButtons.indexOf(button) < 0) {
      heldButtons.push(button);
    }
    lastSentMove = point;
    pendingMove = null;
    capturePointer(e.pointerId);
    sendControl({ type: "mouse", action: "down", button: button, x: point.x, y: point.y });
    e.preventDefault();
    return;
  }
  //one finger at a time: the protocol carries a single stroke
  if (activePointerId !== null) {
    return;
  }
  activePointerId = e.pointerId;
  lastSentMove = point;
  pendingMove = null;
  capturePointer(e.pointerId);
  sendControl({ type: "touch", action: "down", x: point.x, y: point.y });
  e.preventDefault();
}

function capturePointer(pointerId) {
  try {
    //keeps the events coming even when the pointer slides outside the video
    inputLayer.setPointerCapture(pointerId);
  } catch (ignored) {
    //not supported here, moves outside the element are simply lost
  }
}

function onPointerMove(e) {
  //a mouse hovers with nothing pressed, and a desktop is full of things that react to it, so
  //every move is worth forwarding - unlike a touch screen, which only moves mid-stroke
  if (inMouseMode()) {
    if (!controlAvailable) {
      return;
    }
  } else if (e.pointerId !== activePointerId) {
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
  if (pendingMove === null) {
    return;
  }
  var mouse = inMouseMode();
  if (!mouse && activePointerId === null) {
    return;
  }
  var point = pendingMove;
  pendingMove = null;
  //a finger cannot aim at a single pixel but a pointer can, so the dead zone that keeps a touch
  //stroke from flooding the socket would make precise work on a remote desktop impossible
  var minDelta = mouse ? MOUSE_MOVE_MIN_DELTA : MOVE_MIN_DELTA;
  if (lastSentMove !== null &&
      Math.abs(point.x - lastSentMove.x) < minDelta &&
      Math.abs(point.y - lastSentMove.y) < minDelta) {
    return;
  }
  lastSentMove = point;
  sendControl({
    type: mouse ? "mouse" : "touch", action: "move", x: point.x, y: point.y
  });
}

function onPointerUp(e) {
  if (inMouseMode()) {
    var button = buttonName(e);
    var index = heldButtons.indexOf(button);
    if (index < 0) {
      return;
    }
    heldButtons.splice(index, 1);
    var where = toNormalized(e.clientX, e.clientY) || lastSentMove;
    if (heldButtons.length === 0) {
      forgetPointerCapture(e.pointerId);
    }
    if (where !== null) {
      sendControl({ type: "mouse", action: "up", button: button, x: where.x, y: where.y });
    } else {
      //released in the black bars around the picture: let it go wherever the pointer already is
      sendControl({ type: "mouse", action: "up", button: button });
    }
    e.preventDefault();
    return;
  }
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
  if (inMouseMode()) {
    releaseHeldButtons(e.pointerId);
    return;
  }
  if (e.pointerId !== activePointerId) {
    return;
  }
  releasePointer(e.pointerId);
  sendControl({ type: "touch", action: "cancel" });
}

function endActiveStroke(sendCancel) {
  if (inMouseMode()) {
    releaseHeldButtons(null);
    return;
  }
  if (activePointerId === null) {
    return;
  }
  var pointerId = activePointerId;
  releasePointer(pointerId);
  if (sendCancel) {
    sendControl({ type: "touch", action: "cancel" });
  }
}

/** Lifts every mouse button this page believes is down on the remote machine. */
function releaseHeldButtons(pointerId) {
  while (heldButtons.length > 0) {
    sendControl({ type: "mouse", action: "cancel", button: heldButtons.pop() });
  }
  forgetPointerCapture(pointerId);
  pendingMove = null;
}

/**
 * Everything the remote machine is holding on this page behalf, let go at once. Sent when the tab
 * stops being in front, because from then on the releases would never arrive - and a modifier left
 * pressed makes the other machine unusable until someone walks over to it.
 */
function releaseEverythingHeld() {
  if (!controlAvailable) {
    return;
  }
  if (heldButtons.length > 0) {
    releaseHeldButtons(null);
  }
  if (hasInput("keyboard")) {
    sendControl({ type: "keyboard", action: "reset" });
  }
}

function forgetPointerCapture(pointerId) {
  lastSentMove = null;
  if (pointerId === null) {
    return;
  }
  try {
    inputLayer.releasePointerCapture(pointerId);
  } catch (ignored) {
    //the capture was never taken, or the pointer is already gone
  }
}

function releasePointer(pointerId) {
  activePointerId = null;
  pendingMove = null;
  forgetPointerCapture(pointerId);
}

function onWheel(e) {
  if (!controlAvailable || !hasInput("wheel")) {
    return;
  }
  //whatever unit this browser reports, turn it into wheel notches: about 100 px to a notch in
  //pixel mode, three lines in line mode, and a page is roughly three notches
  var factor = 1 / 100;
  if (e.deltaMode === 1) {
    factor = 1 / 3;
  } else if (e.deltaMode === 2) {
    factor = 3;
  }
  if (pendingWheel === null) {
    pendingWheel = { dx: 0, dy: 0, x: undefined, y: undefined };
  }
  pendingWheel.dx += e.deltaX * factor;
  pendingWheel.dy += e.deltaY * factor;
  var point = toNormalized(e.clientX, e.clientY);
  if (point !== null) {
    pendingWheel.x = point.x;
    pendingWheel.y = point.y;
  }
  //the page itself must not scroll away underneath the picture
  e.preventDefault();
  scheduleWheelFlush();
}

function scheduleWheelFlush() {
  if (wheelScheduled) {
    return;
  }
  wheelScheduled = true;
  requestAnimationFrame(function () {
    wheelScheduled = false;
    flushWheel();
  });
}

function flushWheel() {
  var wheel = pendingWheel;
  pendingWheel = null;
  if (wheel === null) {
    return;
  }
  //a trackpad reports a great many tiny deltas; below a tenth of a notch there is nothing to send,
  //and the server rejects a wheel message that would move nothing anyway
  if (Math.abs(wheel.dx) < 0.1 && Math.abs(wheel.dy) < 0.1) {
    return;
  }
  sendControl({ type: "wheel", dx: wheel.dx, dy: wheel.dy, x: wheel.x, y: wheel.y });
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
    "unsupported": "That action is not available on this machine",
    "input-failed": "The window in front could not be reached - it may be running as administrator",
    "input-permission": "The device has not been allowed to be controlled - grant this app the " +
      "Accessibility permission in its system settings, then reconnect"
  };
  if (texts[reason]) {
    showHint(texts[reason]);
  }
  //a reason with nothing to say - "ok" on every (re)connect - must not wipe another message that
  //is still on screen; every hint times itself out anyway
}

/** Puts a short note next to the toolbar buttons, which clears itself after a few seconds. */
function showHint(text) {
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
