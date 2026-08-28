/*
 * Remote control: turning this page's pointer, wheel and keyboard into commands for the far end.
 *
 * Part of the remote screen page - see screen.js for the protocol this all serves. The pages
 * share one global scope, so the split is by subject only: nothing here is a module.
 */

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
var keyboardToggle = null;
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

// --- remote control --------------------------------------------------------------------------

function setupControlInput() {
  inputLayer = document.getElementById("screen-input");
  textField = document.getElementById("control-text");
  captureButton = document.getElementById("keyboard-capture");
  keyboardToggle = document.getElementById("screen-keyboard-toggle");

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

  //the button itself does nothing but raise the keyboard already wired to the field below - a
  //user gesture is all focus() needs to work
  keyboardToggle.addEventListener("click", function () {
    textField.focus();
  });

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
      if (e.isComposing) {
        //an Android on-screen keyboard can raise exactly this event as an artifact of
        //committing a composed word rather than a real Enter press - preventDefault-ing it
        //would swallow the very character it was committing, so let it go through instead
        return;
      }
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
  //deferred rather than cleared right here: writing to .value from inside the very "input" event
  //it is answering is enough to desync an Android on-screen keyboard's idea of the cursor, which
  //then silently stops delivering anything typed after - letting this task finish first avoids it
  setTimeout(function () {
    textField.value = "";
  }, 0);
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
  keyboardToggle.classList.toggle("hidden",
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
