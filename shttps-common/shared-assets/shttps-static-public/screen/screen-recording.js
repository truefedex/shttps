/*
 * Recording what is being watched to an MP4 file, and the box surgery that needs.
 *
 * Part of the remote screen page - see screen.js for the protocol this all serves. The pages
 * share one global scope, so the split is by subject only: nothing here is a module.
 */

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
