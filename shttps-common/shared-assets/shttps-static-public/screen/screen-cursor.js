/*
 * The remote mouse pointer, drawn by this page instead of being part of the picture.
 *
 * Part of the remote screen page - see screen.js for the protocol this all serves. The pages
 * share one global scope, so the split is by subject only: nothing here is a module.
 *
 * A pointer that is part of the video is as late as the video. So a server that can, and whose
 * viewers all said they know how (cursor=1 in the handshake), leaves it out and sends
 * {"type":"cursor","x":..,"y":..,"shape":"text","visible":true} whenever it moves or changes -
 * a few bytes that are not held up by encoding or decoding. The stream frame says which of the two
 * is in force: "cursor":"overlay" or "cursor":"picture".
 *
 * What is then shown depends on who is looking:
 *   - whoever is moving the pointer with a mouse of their own sees the browser's pointer, in the
 *     remote one's shape. It does not wait for the server at all, which is the whole point;
 *   - everybody else - someone only watching, someone on a touch screen - sees an arrow placed
 *     where the server says the pointer is.
 * The arrow also comes back for the first kind of viewer whenever the two disagree for longer than
 * a moment, because then someone else moved the pointer: a second viewer, or the person at the
 * machine.
 */

/** Shapes a server may name. Anything else is shown as the plain arrow rather than trusted. */
var CURSOR_SHAPES = ["default", "text", "pointer", "wait", "progress", "crosshair", "move", "help",
  "not-allowed", "ew-resize", "ns-resize", "nwse-resize", "nesw-resize"];
/**
 * How long after this viewer last moved the pointer the arrow stays away. While the mouse is
 * moving, what the server reports is always a little behind it, and an arrow chasing the
 * browser's own pointer across the screen is exactly what this file exists to get rid of.
 */
var LOCAL_POINTER_QUIET_MS = 400;
/** How far apart, as a fraction of the picture, the two pointers may be and still count as one. */
var POINTER_MATCH_DISTANCE = 0.02;

/** Whether the server keeps the pointer out of the picture right now. */
var cursorOverlay = false;
/** The last pointer report, or null before the first. */
var remoteCursor = null;
var localPointerInside = false;
var lastLocalPointerAt = 0;
var remoteCursorTimer = null;

function setCursorOverlay(active) {
  cursorOverlay = active;
  if (!active) {
    remoteCursor = null;
  }
  applyLocalCursor();
  updateRemoteCursor();
}

function onRemoteCursor(info) {
  remoteCursor = {
    x: Number(info.x),
    y: Number(info.y),
    shape: CURSOR_SHAPES.indexOf(info.shape) >= 0 ? info.shape : "default",
    visible: info.visible !== false
  };
  applyLocalCursor();
  updateRemoteCursor();
}

/**
 * Gives the browser's own pointer the remote one's shape while it stands in for it. With the
 * pointer in the picture the input layer hides the local one instead, see screen.css.
 */
function applyLocalCursor() {
  var layer = document.getElementById("screen-input");
  if (!layer) {
    return;
  }
  if (cursorOverlay && inMouseMode()) {
    layer.style.cursor = remoteCursor === null ? "default" :
      (remoteCursor.visible ? remoteCursor.shape : "none");
  } else {
    layer.style.cursor = "";
  }
}

/** Called by the control code for every move of this viewer's own pointer over the picture. */
function noteLocalPointer(inside) {
  localPointerInside = inside;
  if (!cursorOverlay) {
    return;
  }
  lastLocalPointerAt = performance.now();
  updateRemoteCursor();
  //and once more when the pointer has been still for long enough to compare the two
  if (remoteCursorTimer !== null) {
    clearTimeout(remoteCursorTimer);
  }
  remoteCursorTimer = setTimeout(function () {
    remoteCursorTimer = null;
    updateRemoteCursor();
  }, LOCAL_POINTER_QUIET_MS + 50);
}

/** Places the arrow, or hides it - see the top of this file for when which. */
function updateRemoteCursor() {
  var arrow = document.getElementById("remote-cursor");
  if (!arrow) {
    return;
  }
  var cursor = remoteCursor;
  var show = cursorOverlay && cursor !== null && cursor.visible &&
    cursor.x >= 0 && cursor.x <= 1 && cursor.y >= 0 && cursor.y <= 1;
  if (show && controlAvailable && inMouseMode() && localPointerInside) {
    var moving = performance.now() - lastLocalPointerAt < LOCAL_POINTER_QUIET_MS;
    var together = lastSentMove !== null &&
      Math.abs(cursor.x - lastSentMove.x) < POINTER_MATCH_DISTANCE &&
      Math.abs(cursor.y - lastSentMove.y) < POINTER_MATCH_DISTANCE;
    if (moving || together) {
      show = false;
    }
  }
  var area = show ? pictureArea() : null;
  if (area === null) {
    arrow.classList.add("hidden");
    return;
  }
  var container = document.getElementById("screen-container").getBoundingClientRect();
  arrow.style.left = (area.left - container.left + cursor.x * area.width) + "px";
  arrow.style.top = (area.top - container.top + cursor.y * area.height) + "px";
  arrow.classList.remove("hidden");
}
