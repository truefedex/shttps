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