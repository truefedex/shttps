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
