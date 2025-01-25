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
    contextMenu.style.visibility = "hidden";
    return;
  }

  if (smallScreen) {
    contextMenu.classList.add("menu-fullscreen");
    contextMenu.querySelector(".menu-close-btn").style.display = "block";
  } else {
    let x = anchorRct.left;
    let y = anchorRct.bottom;
    let contextMenuWidth = 0;
    let contextMenuHeight = 0;
    contextMenu.style.removeProperty("width");
    contextMenu.style.removeProperty("height");
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

    contextMenu.style.width = contextMenuWidth + "px";
    contextMenu.style.height = contextMenuHeight + "px";
    contextMenu.classList.remove("menu-fullscreen");
    contextMenu.style.left = x + "px";
    contextMenu.style.top = y + "px";
    contextMenu.querySelector(".menu-close-btn").style.display = "none";
  }

  contextMenu.style.visibility = "visible";
}

function hideContextMenu() {
  let contextMenus = document.querySelectorAll(".context-menu");
  contextMenus.forEach(contextMenu => {
    contextMenu.style.visibility = "hidden";
  });
}

window.addEventListener("click", e => {
  if (e.target.id != "context-menu" && !e.target.classList.contains("context-menu-item") && 
    !e.target.classList.contains("context-menu-item-separator")) {
    hideContextMenu();
  }
});