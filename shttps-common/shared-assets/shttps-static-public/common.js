let SMALL_UI_MAX_SCREEN_SIZE = 500;
let smallScreen = window.innerWidth < SMALL_UI_MAX_SCREEN_SIZE;

function iOS() {
  return ['iPad','iPhone','iPod'].includes(navigator.platform)
  // iPad on iOS 13 detection
  || (navigator.userAgent.includes("Mac") && "ontouchend" in document)
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
  var timeoutId;
  
  element.addEventListener('touchstart', function(e) {
    timeoutId = setTimeout(function() {
      timeoutId = null;
      e.stopPropagation();
      callback(e);
    }, 500);
  });

  element.addEventListener('contextmenu', function(e) {
    e.preventDefault();
  });

  element.addEventListener('touchend', function () {
    if (timeoutId) clearTimeout(timeoutId);
  });

  element.addEventListener('touchmove', function () {
    if (timeoutId) clearTimeout(timeoutId);
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
    // For mobile, set fullscreen mode first
    contextMenu.classList.add("menu-fullscreen");
    contextMenu.style.left = "0";
    contextMenu.style.top = "0";
    contextMenu.style.width = "100%";
    contextMenu.style.height = "100%";
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