let SMALL_UI_MAX_SCREEN_SIZE = 500;
let smallScreen = window.innerWidth < SMALL_UI_MAX_SCREEN_SIZE;

const deviceHasPointer = window.matchMedia('(hover: hover)').matches

function iOS() {
  return ['iPad','iPhone','iPod'].includes(navigator.platform)
  // iPad on iOS 13 detection
  || (navigator.userAgent.includes("Mac") && "ontouchend" in document)
}

function isAndroid() {
  return /Android/i.test(navigator.userAgent);
}

function debounce(func, time){
  var time = time || 100; // 100 by default if no param
  var timer;
  return function(event){
      if(timer) clearTimeout(timer);
      timer = setTimeout(func, time, event);
  };
}

function onLongPress(element, callback) {  
  // On Android, use contextmenu event directly (it fires on long press)
  if (isAndroid()) {    
    element.addEventListener('contextmenu', function(e) {
      e.preventDefault();
      e.stopPropagation();
      if (e.clientX == null) {
        let touch = e.touches && e.touches[0];
        e.clientX = touch ? touch.clientX : 0;
        e.clientY = touch ? touch.clientY : 0;
      }
      callback(e);
    });
    return;
  }
  
  // On iOS and other platforms, use touch-based timeout approach
  var timeoutId;
  
  element.addEventListener('touchstart', function(e) {
    timeoutId = setTimeout(function() {
      timeoutId = null;
      e.stopPropagation();
      if (e.clientX == null && e.touches && e.touches[0]) {
        e.clientX = e.touches[0].clientX;
        e.clientY = e.touches[0].clientY;
      }
      callback(e);
    }, 500);
  });

  element.addEventListener('contextmenu', function(e) {
    e.preventDefault();
  });

  element.addEventListener('touchend', function () {
    if (timeoutId) {
      clearTimeout(timeoutId);
      timeoutId = null;
    }
  });

  element.addEventListener('touchmove', function () {
    if (timeoutId) {
      clearTimeout(timeoutId);
      timeoutId = null;
    }
  });
}

function displayContextMenu(x, y, contextMenu) {
  let anchorRct = {left: x, top: y, right: x, bottom: y};
  displayContextMenuWithAnchorRect(anchorRct, contextMenu);
}

function displayContextMenuWithAnchor(anchorElement, contextMenu) {
  let anchorRct = anchorElement.getBoundingClientRect();
  displayContextMenuWithAnchorRect(anchorRct, contextMenu);
}

function displayContextMenuWithAnchorRect(anchorRct, contextMenu) {
  if (contextMenu.style.visibility == "visible") {
    hideContextMenu();
    return;
  }

  // First make menu invisible but in the DOM to calculate its size
  contextMenu.style.visibility = "hidden";
  contextMenu.style.display = "block";
  contextMenu.style.position = "fixed";

  if (smallScreen) {
    contextMenu.classList.add("menu-fullscreen");
    contextMenu.style.left = "0";
    contextMenu.style.top = "0";
    contextMenu.style.width = "100%";
    contextMenu.style.height = "100%";
    contextMenu.style.display = "flex";
    contextMenu.querySelector(".menu-close-btn").style.display = "block";
  } else {
    // For desktop, calculate position and size
    let x = anchorRct.left;
    let y = anchorRct.bottom;
    let contextMenuWidth = 0;
    let contextMenuHeight = 0;
    
    // Remove any existing width/height constraints
    contextMenu.style.removeProperty("width");
    contextMenu.style.removeProperty("height");
    
    // Get the natural size of the menu
    let rct = contextMenu.getBoundingClientRect();
    contextMenuWidth = rct.width;
    contextMenuHeight = rct.height;

    let rightMenuSpace = window.innerWidth - anchorRct.left;
    let leftMenuSpace = anchorRct.right;
    if (rightMenuSpace < leftMenuSpace) {
      if (leftMenuSpace < contextMenuWidth) {
        contextMenuWidth = leftMenuSpace;
      }
      x = anchorRct.right - contextMenuWidth;
    } else {
      if (rightMenuSpace < contextMenuWidth) {
        contextMenuWidth = rightMenuSpace;
      }
    }
    
    let bottomMenuSpace = window.innerHeight - anchorRct.bottom;
    let topMenuSpace = anchorRct.top;
    if (bottomMenuSpace < topMenuSpace) {
      if (topMenuSpace < contextMenuHeight) {
        contextMenuHeight = topMenuSpace;
      }
      y = anchorRct.top - contextMenuHeight;
    } else {
      if (bottomMenuSpace < contextMenuHeight) {
        contextMenuHeight = bottomMenuSpace;
      }
    }

    // Set the final position and size
    contextMenu.style.width = contextMenuWidth + "px";
    contextMenu.style.height = contextMenuHeight + "px";
    contextMenu.classList.remove("menu-fullscreen");
    contextMenu.style.left = x + "px";
    contextMenu.style.top = y + "px";
    contextMenu.querySelector(".menu-close-btn").style.display = "none";
  }

  // Now make it visible
  contextMenu.style.visibility = "visible";

  // Add click handler to prevent event propagation when context menu is open
  const clickHandler = (e) => {
    // Check if the click is inside the context menu
    const isClickInside = contextMenu.contains(e.target);
    
    if (!isClickInside) {
      e.preventDefault();
      e.stopPropagation();
      hideContextMenu();
    } else if (e.target.classList.contains('menu-option')) {
      // If clicking a menu option, close the menu after a short delay
      // to allow the click event to complete
      setTimeout(() => {
        hideContextMenu();
      }, 0);
    }
  };

  // Add contextmenu handler to close menu on right-click
  const contextMenuHandler = (e) => {
    // Only handle contextmenu events on desktop
    if (smallScreen) {
      e.preventDefault();
      return;
    }
    e.preventDefault();
    hideContextMenu();
  };

  // Add touch handlers for mobile
  let touchStartTime = 0;
  let touchStartTarget = null;
  let touchStartPosition = null;
  let isInitialTouch = true;

  const touchStartHandler = (e) => {
    // Only handle touch events on mobile
    if (!smallScreen) return;
    
    touchStartTime = Date.now();
    touchStartTarget = e.target;
    if (e.touches && e.touches[0]) {
      touchStartPosition = {
        x: e.touches[0].clientX,
        y: e.touches[0].clientY
      };
    }
  };

  const touchEndHandler = (e) => {
    // Only handle touch events on mobile
    if (!smallScreen) return;

    // If we don't have valid touch data, ignore the event
    if (!touchStartPosition || !e.changedTouches || !e.changedTouches[0]) {
      return;
    }

    // If this is the initial touch that opened the menu, ignore it
    if (isInitialTouch) {
      isInitialTouch = false;
      return;
    }

    // Get the touch end position
    const touchEndPosition = {
      x: e.changedTouches[0].clientX,
      y: e.changedTouches[0].clientY
    };

    // Calculate the distance moved
    const distanceMoved = Math.sqrt(
      Math.pow(touchEndPosition.x - touchStartPosition.x, 2) +
      Math.pow(touchEndPosition.y - touchStartPosition.y, 2)
    );

    // If the touch moved significantly, treat it as a drag and ignore
    if (distanceMoved > 10) {
      return;
    }

    // Check if the touch end position is inside the context menu
    const menuRect = contextMenu.getBoundingClientRect();
    const isTouchInside = (
      touchEndPosition.x >= menuRect.left &&
      touchEndPosition.x <= menuRect.right &&
      touchEndPosition.y >= menuRect.top &&
      touchEndPosition.y <= menuRect.bottom
    );

    if (!isTouchInside) {
      e.preventDefault();
      e.stopPropagation();
      hideContextMenu();
    }
  };

  // Add the handlers to document
  document.addEventListener('click', clickHandler, true);
  document.addEventListener('contextmenu', contextMenuHandler, true);
  document.addEventListener('touchstart', touchStartHandler, true);
  document.addEventListener('touchend', touchEndHandler, true);

  // Store the handlers on the context menu for cleanup
  contextMenu._clickHandler = clickHandler;
  contextMenu._contextMenuHandler = contextMenuHandler;
  contextMenu._touchStartHandler = touchStartHandler;
  contextMenu._touchEndHandler = touchEndHandler;
}

function hideContextMenu() {
  let contextMenus = document.querySelectorAll(".context-menu");
  contextMenus.forEach(contextMenu => {
    if (contextMenu.style.visibility === "visible") {
      // Remove the click handler
      if (contextMenu._clickHandler) {
        document.removeEventListener('click', contextMenu._clickHandler, true);
        contextMenu._clickHandler = null;
      }
      // Remove the contextmenu handler
      if (contextMenu._contextMenuHandler) {
        document.removeEventListener('contextmenu', contextMenu._contextMenuHandler, true);
        contextMenu._contextMenuHandler = null;
      }
      // Remove the touch handlers
      if (contextMenu._touchStartHandler) {
        document.removeEventListener('touchstart', contextMenu._touchStartHandler, true);
        contextMenu._touchStartHandler = null;
      }
      if (contextMenu._touchEndHandler) {
        document.removeEventListener('touchend', contextMenu._touchEndHandler, true);
        contextMenu._touchEndHandler = null;
      }
    }
    contextMenu.style.visibility = "hidden";
  });
}
/**
 * The single way every page talks to the server. Resolves with the parsed
 * response - JSON when the server sends JSON, text when it does not, null for
 * an empty body - and rejects with an Error whose message is the server's own
 * response text, so a caller writes the success path plus one catch.
 *
 * The body picks its own encoding, by the type the platform already has for it:
 * URLSearchParams for a form, FormData for multipart, a string for text/plain,
 * anything else for JSON. Note the JSON header is sent without a charset: some
 * handlers compare the content type for equality.
 *
 * Uploads still use XMLHttpRequest directly - they need upload progress, which
 * fetch does not report.
 */
async function api(method, url, body) {
  let options = { method: method };
  if (body != null) {
    if (body instanceof FormData || body instanceof URLSearchParams || body instanceof Blob) {
      options.body = body;//browser sets the Content-Type itself
    } else if (typeof body == "string") {
      options.headers = { "Content-Type": "text/plain" };
      options.body = body;
    } else {
      options.headers = { "Content-Type": "application/json" };
      options.body = JSON.stringify(body);
    }
  }
  let response = await fetch(url, options);
  let text = await response.text();
  if (!response.ok) {
    let error = new Error(text ? text : response.statusText);
    error.status = response.status;
    throw error;
  }
  if (!text) return null;
  let contentType = response.headers.get("Content-Type");
  return contentType != null && contentType.includes("json") ? JSON.parse(text) : text;
}

/**
 * Wires the ☰ button to this page's #main-menu. Entries are given as a map of
 * menu item id to either a URL to go to or a click handler. Ids the page did
 * not render are skipped, so a caller lists every entry it may have without
 * checking which of them the server actually put in the markup.
 */
function setupMainMenu(entries) {
  for (let id in entries) {
    let item = document.getElementById(id);
    if (item == null) continue;
    let action = entries[id];
    item.onclick = typeof action == "string" ? function () { window.location.href = action; } : action;
  }
  let menuButton = document.getElementById("menu-button");
  let mainMenu = document.getElementById("main-menu");
  if (menuButton == null || mainMenu == null) return;
  menuButton.onclick = function (e) {
    displayContextMenuWithAnchorRect(menuButton.getBoundingClientRect(), mainMenu);
    e.stopPropagation();
    e.preventDefault();
  };
}
