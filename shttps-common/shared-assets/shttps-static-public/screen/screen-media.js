/*
 * Media Source Extensions: the buffer the arriving fMP4 is fed to, and keeping playback at the live edge.
 *
 * Part of the remote screen page - see screen.js for the protocol this all serves. The pages
 * share one global scope, so the split is by subject only: nothing here is a module.
 */

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

  //a new stream is a new timeline, and possibly a new link
  resetTargetLatency();
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
  //on a link uneven enough to need a large reserve, that reserve must not itself count as lag
  if (behind > Math.max(MAX_LATENCY_SECONDS, targetLatency * 3)) {
    jumpToLiveEdge(ranges, last);
  } else if (behind > targetLatency + CATCH_UP_SLACK_SECONDS) {
    setPlaybackRate(CATCH_UP_PLAYBACK_RATE);
  } else if (behind <= targetLatency) {
    setPlaybackRate(1);
  }
}

function jumpToLiveEdge(ranges, last) {
  video.currentTime = Math.max(ranges.start(last), ranges.end(last) - targetLatency);
  setPlaybackRate(1);
}

// --- target latency --------------------------------------------------------------------------

/**
 * Records how punctual a media segment was.
 * <p>
 * Every segment says where it belongs on the media timeline, and that timeline runs at the pace of
 * the capture. So the time of arrival minus the decode time is the same number for every segment
 * of a perfectly even stream, and how far it wanders is exactly how much video has to be in hand
 * for playback not to run dry. Measuring it this way, rather than from the gaps between arrivals,
 * is what keeps a stream that legitimately slows down - a still screen - from looking like a bad
 * link.
 */
function noteSegmentArrival(data) {
  var decodeTime = readDecodeTime(data);
  if (decodeTime < 0) {
    return;
  }
  var offset = performance.now() / 1000 - decodeTime;
  if (arrivalOffsets.length &&
      Math.abs(offset - arrivalOffsets[arrivalOffsets.length - 1]) > ARRIVAL_DISCONTINUITY_SECONDS) {
    //the stream paused or a backlog was dropped: what came before no longer compares
    arrivalOffsets = [];
  }
  arrivalOffsets.push(offset);
  if (arrivalOffsets.length > ARRIVAL_WINDOW) {
    arrivalOffsets.shift();
  }
  if (++arrivalsSinceUpdate >= 30) {
    arrivalsSinceUpdate = 0;
    updateTargetLatency();
  }
}

/**
 * Sets the target to what the last few seconds of arrivals needed: the lateness that 95% of the
 * segments stayed within, plus a frame of margin. Raised at once and lowered slowly - being caught
 * short is a visible freeze, while a reserve kept a little too long is only a few milliseconds.
 */
function updateTargetLatency() {
  if (arrivalOffsets.length < 30) {
    return;
  }
  var sorted = arrivalOffsets.slice().sort(function (a, b) { return a - b; });
  //the earliest arrival is the one that was not held up at all
  var spread = sorted[Math.floor((sorted.length - 1) * 0.95)] - sorted[0];
  var wanted = Math.min(MAX_TARGET_LATENCY_SECONDS,
    Math.max(MIN_TARGET_LATENCY_SECONDS, spread * 1.25 + TARGET_LATENCY_MARGIN_SECONDS));
  targetLatency = wanted > targetLatency ? wanted : targetLatency + (wanted - targetLatency) * 0.1;
}

function resetTargetLatency() {
  targetLatency = INITIAL_TARGET_LATENCY_SECONDS;
  arrivalOffsets = [];
  arrivalsSinceUpdate = 0;
}

/**
 * @return the decode time of a media segment in seconds - moof/traf/tfdt - or -1 if this is not
 * one. Walks the boxes rather than reading a fixed offset, so a muxer that grows a box does not
 * silently turn this into noise.
 */
function readDecodeTime(data) {
  var moof = findBox(data, 0, data.length, "moof");
  var traf = moof && findBox(data, moof.start, moof.end, "traf");
  var tfdt = traf && findBox(data, traf.start, traf.end, "tfdt");
  if (!tfdt || tfdt.end - tfdt.start < 8) {
    return -1;
  }
  var version = data[tfdt.start];
  var at = tfdt.start + 4;
  var value;
  if (version === 1) {
    if (tfdt.end - at < 8) {
      return -1;
    }
    value = readU32(data, at) * 4294967296 + readU32(data, at + 4);
  } else {
    value = readU32(data, at);
  }
  return value / MEDIA_TIMESCALE;
}

/** @return the payload range of the first box of that type within [start, end), or null */
function findBox(data, start, end, type) {
  var offset = start;
  while (offset + 8 <= end) {
    var size = readU32(data, offset);
    if (size < 8 || offset + size > end) {
      return null;
    }
    if (String.fromCharCode(data[offset + 4], data[offset + 5], data[offset + 6],
        data[offset + 7]) === type) {
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

function setPlaybackRate(rate) {
  if (video.playbackRate !== rate) {
    video.playbackRate = rate;
  }
}
