/*
 * WebCodecs: decoding the arriving frames directly and painting them on a canvas.
 *
 * Part of the remote screen page - see screen.js for the protocol this all serves. The pages
 * share one global scope, so the split is by subject only: nothing here is a module.
 *
 * Media Source Extensions is a player: it wants video in hand before it shows any, and the page
 * spends its effort keeping that reserve small (screen-media.js). A decoder has no such notion - a
 * frame goes in, a picture comes out, and it is on screen at the next repaint. For a screen someone
 * is steering a mouse across, that reserve is most of the delay there is to remove.
 *
 * The server does nothing different for this. The same fMP4 segments arrive; the decoder's
 * configuration is the avcC record inside the initialization segment and each frame is the payload
 * of a segment's mdat, which is already in the length prefixed form the decoder takes.
 *
 * Two things keep this from simply replacing MSE. A browser only offers VideoDecoder to a page from
 * a secure origin - https, or localhost - and this server is very often reached as
 * http://192.168.x.x. And a decoder that fails has no way to recover that is worth trusting. So MSE
 * stays as the path that always works: used when there is no decoder to be had, and switched to,
 * for the rest of the visit, the first time the decoder reports an error.
 */

/**
 * Frames the decoder may have waiting before the page stops handing it more. A decoder that falls
 * behind would otherwise show an ever older screen; dropping to the next key frame costs a moment
 * of stillness and puts the picture back in the present.
 */
var MAX_DECODE_QUEUE = 6;

var canvas = null;
var canvasContext = null;
var frameDecoder = null;
/** Counts decoders, so that a callback from one that has been replaced can tell it is stale. */
var decoderGeneration = 0;
/** Set after anything that breaks the chain of frames: only a key frame can restart it. */
var decoderAwaitingKeyFrame = true;
/** The newest decoded frame not painted yet. Older ones are dropped, not queued. */
var pendingFrame = null;
var paintScheduled = false;
var paintedFrames = 0;
/** Set once the decoder has failed on this page: MSE from then on. */
var frameDecoderBroken = false;

function setupFrameDecoder() {
  canvas = document.getElementById("screen-canvas");
}

/** Whether this stream is being decoded here rather than played by the video element. */
function usingFrameDecoder() {
  return frameDecoder !== null;
}

function frameDecoderAvailable() {
  if (frameDecoderBroken || !canvas) {
    return false;
  }
  if (typeof VideoDecoder === "undefined" || typeof EncodedVideoChunk === "undefined") {
    return false;
  }
  //for comparing the two paths by hand, and as a way out should a browser's decoder misbehave
  //without ever admitting to an error
  return !/[?&]decoder=mse(&|$)/.test(location.search);
}

/**
 * @return true if a decoder was set up for this stream, false if MSE has to take it
 */
function startFrameDecoder(info, initSegment) {
  closeFrameDecoder();
  if (!frameDecoderAvailable()) {
    return false;
  }
  var description = findDecoderConfiguration(initSegment);
  if (description === null) {
    return false;
  }
  var generation = ++decoderGeneration;
  try {
    frameDecoder = new VideoDecoder({
      output: function (frame) {
        onDecodedFrame(generation, frame);
      },
      error: function (error) {
        onFrameDecoderError(generation, error);
      }
    });
    frameDecoder.configure({
      codec: info.codec,
      description: description,
      //without this a decoder may hold frames back to reorder them, which this stream never needs
      optimizeForLatency: true
    });
  } catch (e) {
    frameDecoder = null;
    return false;
  }
  decoderAwaitingKeyFrame = true;
  paintedFrames = 0;
  canvas.width = info.width;
  canvas.height = info.height;
  canvasContext = canvas.getContext("2d", { alpha: false, desynchronized: true });
  canvas.classList.remove("hidden");
  video.classList.add("hidden");
  return true;
}

function closeFrameDecoder() {
  decoderGeneration++;
  if (pendingFrame !== null) {
    pendingFrame.close();
    pendingFrame = null;
  }
  if (frameDecoder !== null) {
    try {
      frameDecoder.close();
    } catch (e) {
      //already closed by an error
    }
    frameDecoder = null;
  }
  if (canvas) {
    canvas.classList.add("hidden");
  }
  if (video) {
    video.classList.remove("hidden");
  }
}

/** Hands one media segment's frame to the decoder. */
function decodeSegment(data) {
  var sample = findBox(data, 0, data.length, "mdat");
  if (sample === null || sample.end <= sample.start) {
    return;
  }
  var keyFrame = sampleHasKeyFrame(data, sample.start, sample.end);
  if (!decoderAwaitingKeyFrame && frameDecoder.decodeQueueSize > MAX_DECODE_QUEUE) {
    //this machine cannot decode as fast as the frames come in
    decoderAwaitingKeyFrame = true;
    requestKeyFrame();
  }
  if (decoderAwaitingKeyFrame) {
    if (!keyFrame) {
      return;
    }
    decoderAwaitingKeyFrame = false;
  }
  try {
    frameDecoder.decode(new EncodedVideoChunk({
      type: keyFrame ? "key" : "delta",
      timestamp: Math.round(readDecodeTime(data) * 1000000),
      data: data.subarray(sample.start, sample.end)
    }));
  } catch (e) {
    onFrameDecoderError(decoderGeneration, e);
  }
}

function onDecodedFrame(generation, frame) {
  if (generation !== decoderGeneration) {
    frame.close();
    return;
  }
  //only the newest is worth painting; a frame is video memory until it is closed
  if (pendingFrame !== null) {
    pendingFrame.close();
  }
  pendingFrame = frame;
  if (!paintScheduled) {
    paintScheduled = true;
    requestAnimationFrame(paintFrame);
  }
}

function paintFrame() {
  paintScheduled = false;
  var frame = pendingFrame;
  pendingFrame = null;
  if (frame === null) {
    return;
  }
  if (canvasContext !== null && usingFrameDecoder()) {
    if (canvas.width !== frame.displayWidth || canvas.height !== frame.displayHeight) {
      canvas.width = frame.displayWidth;
      canvas.height = frame.displayHeight;
    }
    canvasContext.drawImage(frame, 0, 0, canvas.width, canvas.height);
    if (paintedFrames++ === 0) {
      hideMessage();
      setConnectionState("state-live", "Live");
      reconnectAttempts = 0;
    }
  }
  frame.close();
}

/**
 * A decoder that has reported an error is closed and stays closed. Rather than guess whether a new
 * one would fare better, the rest of this visit goes through MSE, which starts from the
 * initialization segment already in hand and a key frame asked for now.
 */
function onFrameDecoderError(generation, error) {
  if (generation !== decoderGeneration) {
    return;
  }
  if (window.console) {
    console.warn("Screen decoder failed, falling back to Media Source Extensions:", error);
  }
  frameDecoderBroken = true;
  closeFrameDecoder();
  if (streamInfo !== null && latestInitSegment !== null) {
    //starting the media source forgets the segment, as it does for a stream that restarted
    var initSegment = latestInitSegment;
    startMediaSource(streamInfo);
    latestInitSegment = initSegment;
    updateRecordUi();
    queueOperation({ type: "append", data: initSegment.slice() });
    pumpOperations();
    requestKeyFrame();
  }
}

function requestKeyFrame() {
  if (socket !== null && socket.readyState === WebSocket.OPEN) {
    socket.send("request-key-frame");
  }
}

/**
 * @return the avcC record of an initialization segment - what a decoder calls its description -
 * or null if there is none. Looked up by walking moov/trak/mdia/minf/stbl/stsd, whose one entry
 * is the avc1 sample entry with avcC inside it.
 */
function findDecoderConfiguration(initSegment) {
  var box = { start: 0, end: initSegment.length };
  var path = ["moov", "trak", "mdia", "minf", "stbl", "stsd"];
  for (var i = 0; i < path.length && box !== null; i++) {
    box = findBox(initSegment, box.start, box.end, path[i]);
  }
  if (box === null) {
    return null;
  }
  //stsd: version and flags, then the entry count, then the entries
  var entry = findBox(initSegment, box.start + 8, box.end, "avc1");
  if (entry === null) {
    return null;
  }
  //a visual sample entry has 78 bytes of fixed fields in front of its child boxes
  var record = findBox(initSegment, entry.start + 78, entry.end, "avcC");
  if (record === null) {
    return null;
  }
  return initSegment.slice(record.start, record.end);
}

/** Whether a sample - NAL units, each with a four byte length in front - holds an IDR slice. */
function sampleHasKeyFrame(data, start, end) {
  var offset = start;
  while (offset + 5 <= end) {
    var length = readU32(data, offset);
    if ((data[offset + 4] & 0x1F) === 5) {
      return true;
    }
    offset += 4 + length;
  }
  return false;
}
