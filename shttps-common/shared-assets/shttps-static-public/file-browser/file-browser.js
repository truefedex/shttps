let files = [];
let uploadInProgress = false;
let selectedFiles = [];
let lastSelectedElement = null;
let currentPath = decodeURIComponent(window.location.pathname);
let currentSearchQuery = null;
let touchscreen = window.matchMedia("(any-pointer: coarse)").matches;
let allowEditing = false;

let resizeTimer;

function renderFileList() {
  let container = document.getElementById("files-container");
  container.innerHTML = "";
  let viewMode = localStorage.getItem("file-list-view-mode");
  if (!viewMode) {
    viewMode = "list";
  }
  if (smallScreen) {
    viewMode = "list";
  }
  let itemsPerRow = 1;
  let availableWidth = window.innerWidth - 20;//- page padding
  let itemsMarging = 5;
  if (viewMode == "list") {
    let minItemSize = 300;
    itemsPerRow = Math.floor(availableWidth / minItemSize);
  } else if (viewMode == "grid") {
    let minItemSize = 140;
    itemsPerRow = Math.floor(availableWidth / minItemSize);
  }
  if (itemsPerRow == 0) itemsPerRow = 1;
  let itemWidth = (availableWidth - (itemsMarging * (itemsPerRow - 1))) / itemsPerRow;
  let itemsInRow = 0;
  let columnsTemplate = "";
  for (let i = 0; i < itemsPerRow; i++) {
    columnsTemplate += "1fr ";//itemWidth+"px ";
  }
  container.style.gridTemplateColumns = columnsTemplate;
  container.dataset.viewMode = viewMode;

  switch (viewMode) {
    case "grid":
      container.style.gridAutoRows = itemWidth + "px";
      break;

    case "table":
      container.style.gridAutoRows = "auto";
      let captionDiv = document.createElement("div");
      captionDiv.classList.add("table-caption");

      let currentSort = localStorage.sort ? localStorage.sort : "default";
      let currentSortReversed = localStorage.sortReversed == "true";
      let sortMark = "";

      let fileNameCaptionDiv = document.createElement("div");
      if (currentSort == "default") {
        sortMark = currentSortReversed ? "↑" : "↓";
      } else sortMark = "";
      fileNameCaptionDiv.innerText = sortMark + " File Name";
      fileNameCaptionDiv.style.flexGrow = 1;
      fileNameCaptionDiv.addEventListener("click", function () { tableSortChange("default"); });
      captionDiv.appendChild(fileNameCaptionDiv);
      let modifiedCaptionDiv = document.createElement("div");
      if (currentSort == "modified") {
        sortMark = currentSortReversed ? "↑" : "↓";
      } else sortMark = "";
      modifiedCaptionDiv.innerText = sortMark + " Last Modified";
      modifiedCaptionDiv.style.width = "190px";
      modifiedCaptionDiv.addEventListener("click", function () { tableSortChange("modified"); });
      captionDiv.appendChild(modifiedCaptionDiv);
      let fileSizeCaptionDiv = document.createElement("div");
      if (currentSort == "size") {
        sortMark = currentSortReversed ? "↑" : "↓";
      } else sortMark = "";
      fileSizeCaptionDiv.innerText = sortMark + " Size";
      fileSizeCaptionDiv.style.width = "70px";
      fileSizeCaptionDiv.addEventListener("click", function () { tableSortChange("size"); });
      captionDiv.appendChild(fileSizeCaptionDiv);
      container.appendChild(captionDiv);
      break;

    default:
      container.style.gridAutoRows = "auto";
      break;
  }

  for (let i = 0; i < files.length; i++) {
    let file = files[i];
    
    // Create wrapper for file item (to support checkbox)
    let fileItemWrapper = document.createElement("div");
    fileItemWrapper.classList.add("file-item-wrapper");
    
    let a = document.createElement("a");
    a.dataset.directory = file.directory;
    let path = currentPath + (currentPath.endsWith("/") ? "" : "/") + 
      (file.relativePath != null ? file.relativePath : "") + file.name;
    if (file.directory) {
      path = path + "/";
    }
    let href = path.split("/").map(encodeURIComponent).join("/");
    a.setAttribute("href", href);
    a.setAttribute("title", file.name);
    
    // Add checkbox when deviceHasPointer == false
    if (!deviceHasPointer && allowEditing) {
      let checkbox = document.createElement("input");
      checkbox.type = "checkbox";
      checkbox.classList.add("file-checkbox");
      checkbox.dataset.href = href;
      checkbox.addEventListener('change', onCheckboxChange);
      checkbox.addEventListener('click', function(e) {
        e.stopPropagation(); // Prevent triggering file item click
      });
      fileItemWrapper.appendChild(checkbox);
    }
    
    a.addEventListener('click', onFileItemClick);
    
    // Set up event handlers based on deviceHasPointer
    if (deviceHasPointer) {
      // With pointer: right click shows context menu, double click opens
      a.addEventListener('contextmenu', function (e) {
        e.preventDefault();
        showContextMenu(e.pageX, e.pageY, href, file.directory);
      });
      a.addEventListener('dblclick', function (e) {
        e.preventDefault();
        onFileItemOpen(href, file.directory);
      });
    } else {
      // Without pointer: long press shows context menu
      onLongPress(a, function (e) {
        showContextMenu(e.pageX, e.pageY, href, file.directory);
      });
    }
    a.classList.add("file-item");

    if (viewMode == "grid") {
      a.classList.add("grid-card");
      let mediaWrapper = document.createElement("div");
      mediaWrapper.classList.add("grid-card-media");
      a.appendChild(mediaWrapper);

      let fileExt = file.name.includes(".") ? file.name.split('.').pop().toLowerCase() : "";
      if (!file.directory) {
        let needThumbnail = false;
        if (["jpg", "jpeg", "png", "gif", "bmp", "tga", "avi", "mp4", "3gp"].includes(fileExt)) {
          needThumbnail = true;
        }
        try {
          let f = new File(file.name);
          if (f.type.startsWith("image/") || f.type.startsWith("video/")) {
            needThumbnail = true;
          }
        } catch (error) { }
        if (needThumbnail) {
          let thumbnail = document.createElement("img");
          thumbnail.src = "/api/file/thumbnail?path=" + encodeURIComponent(path);
          thumbnail.classList.add("grid-file-thumbnail");
          thumbnail.addEventListener('error', function (e) {
            e.target.src = "/shttps-static-public/file-browser/broken-thumbnail.png";
            thumbnail.style["object-fit"] = "contain";
          });
          mediaWrapper.appendChild(thumbnail);
        } else {
          let fileEXTDiv = document.createElement("div");
          fileEXTDiv.innerText = fileExt ? fileExt : file.name.substring(0, 4).toUpperCase();
          fileEXTDiv.classList.add("grid-file-ext");
          mediaWrapper.appendChild(fileEXTDiv);
        }
      } else {
        let folderPlaceholder = document.createElement("div");
        folderPlaceholder.classList.add("grid-folder-placeholder");
        folderPlaceholder.innerText = "📁";
        mediaWrapper.appendChild(folderPlaceholder);
      }
      let fileNameOverlay = document.createElement("div");
      fileNameOverlay.innerText = file.name;
      fileNameOverlay.classList.add("grid-file-name");
      a.appendChild(fileNameOverlay);
    } else {
      let div = document.createElement("div");
      if (file.directory) div.classList.add("folder");
      switch (viewMode) {
        case "table":
          a.style.textDecoration = "none";
          div.style.display = "flex";
          let fileNameDiv = document.createElement("div");
          fileNameDiv.innerText = file.name;
          fileNameDiv.classList.add("table-file-name");
          div.appendChild(fileNameDiv);
          let modifiedDiv = document.createElement("div");
          modifiedDiv.innerText = new Date(file.modified).toLocaleString().replace(",", "");
          modifiedDiv.classList.add("table-file-date");
          div.appendChild(modifiedDiv);
          let sizeDiv = document.createElement("div");
          if (!file.directory) {
            sizeDiv.innerText = file.length;
          }
          sizeDiv.classList.add("table-file-size");
          div.appendChild(sizeDiv);
          break;
        default://list
          div.innerText = file.name;
          break;
      }
      a.appendChild(div);
    }
    fileItemWrapper.appendChild(a);
    container.appendChild(fileItemWrapper);
    itemsInRow++;
    if (itemsInRow >= itemsPerRow) {
      itemsInRow = 0;
    }
  }

  let showDropHint = allowEditing && !touchscreen && files.length < 2;
  let dropHint = document.getElementById("drop-hint");
  if (dropHint) dropHint.style.display = showDropHint ? "inline-block" : "none";
}

function tableSortChange(clickedSort) {
  let currentSort = localStorage.sort ? localStorage.sort : "default";
  let currentSortReversed = localStorage.sortReversed == "true";
  if (currentSort == clickedSort) {
    localStorage.sortReversed = !currentSortReversed;
  } else {
    localStorage.sort = clickedSort;
    localStorage.sortReversed = false;
  }
  loadPath(currentPath, currentSearchQuery);
}

function doUpload() {
  if (uploadInProgress) return;
  let input = document.getElementById("files");
  input.click();
}

function onUploadFilesSelected(input) {
  if (input.files.length == 0) return;
  startFilesUpload(currentPath, input.files);
  input.value = null;
}

function startFilesUpload(basePath, files, relativePaths, emptyDirs) {
  if (uploadInProgress || !allowEditing) return;
  let xhr = new XMLHttpRequest();
  let formData = new FormData();
  let lastSpeedUpdateTime = new Date();
  let lastSpeedLoaded = 0;
  let conflicts = new Set();
  for (let i = 0; i < files.length; i++) {
    let file = files[i];
    let nameWithRelativePath = relativePaths != null ? relativePaths[i] : file.name;
    formData.append("files[]", file, nameWithRelativePath);
    let fileElements = document.getElementById("files-container").childNodes;
    for (let j = 0; j < fileElements.length; j++) {
      let element = fileElements[j];
      // Handle both old structure (direct 'a' elements) and new structure (wrapped in div)
      let a = element.nodeName.toLowerCase() == 'a' ? element : element.querySelector('a');
      if (!a) continue;
      let href = a.getAttribute("href");
      let path = href.split("/").map(decodeURIComponent).join("/");
      let upperLevelFileNameElement = relativePaths != null ? relativePaths[i] : file.name;
      if (upperLevelFileNameElement.startsWith("/")) {
        upperLevelFileNameElement = upperLevelFileNameElement.substring(1);
      }
      if (upperLevelFileNameElement.includes("/")) {
        upperLevelFileNameElement = upperLevelFileNameElement.split("/")[0];
      }
      if (extractFileOrFolderName(path) == upperLevelFileNameElement) {
        conflicts.add(upperLevelFileNameElement);
      }
    }
  }

  if (emptyDirs != null) {
    for (let i = 0; i < emptyDirs.length; i++) {
      formData.append("emptyDirs[]", emptyDirs[i]);
    }
  }

  if (conflicts.size > 0 &&
    !confirm("Do you want to owerride following items: " + Array.from(conflicts).join(", ") + "?")) {
    return;
  }

  let button = document.getElementById("upload-button");
  let textElement = button.querySelector(".button__text");
  let speedElement = button.querySelector(".button__speed");
  textElement.textContent = "UPLOADING...";
  if (speedElement) {
    speedElement.textContent = "";
    speedElement.style.visibility = "hidden";
  }

  function formatSpeed(bytesPerSecond) {
    if (!isFinite(bytesPerSecond) || bytesPerSecond <= 0) return "";
    if (bytesPerSecond >= 1024 * 1024) {
      return (bytesPerSecond / (1024 * 1024)).toFixed(1) + " MB/s";
    }
    if (bytesPerSecond >= 1024) {
      return (bytesPerSecond / 1024).toFixed(1) + " KB/s";
    }
    return Math.max(bytesPerSecond, 1).toFixed(0) + " B/s";
  }

  xhr.upload.onprogress = function (event) {
    let now = new Date();
    let timeSinceUpdate = (now - lastSpeedUpdateTime) / 1000;
    if ((timeSinceUpdate >= 1) || event.loaded === event.total) {
      let bytesDiff = event.loaded - lastSpeedLoaded;
      let bytesPerSecond = timeSinceUpdate > 0 ? bytesDiff / timeSinceUpdate : 0;
      if (speedElement) {
        let speedText = formatSpeed(bytesPerSecond);
        if (speedText) {
          speedElement.textContent = speedText;
          speedElement.style.visibility = "visible";
        }
      }
      lastSpeedUpdateTime = now;
      lastSpeedLoaded = event.loaded;
    }
    let percent = event.loaded * 100 / event.total;
    button.querySelector(".button__progress").style.width = percent + "%";
  };

  xhr.onload = function () {
    textElement.textContent = "UPLOAD FILES";
    let progressElement = button.querySelector(".button__progress");
    progressElement.classList.add('notransition');
    progressElement.style.width = "0%";
    progressElement.offsetHeight; // Trigger a reflow, flushing the CSS changes
    progressElement.classList.remove('notransition');
    uploadInProgress = false;
    if (speedElement) {
      speedElement.textContent = "";
      speedElement.style.visibility = "hidden";
    }

    if (xhr.status == 204) {
      setTimeout(() => {//wait for server to process files
        loadPath(basePath);
      }, 500);
    } else {
      // Handle HTTP error status codes
      alert('Error while uploading files: ' + xhr.status + ' ' + xhr.statusText + '\n' + xhr.responseText);
      loadPath(basePath);
    }
  };

  xhr.onerror = function (e) {
    textElement.textContent = "UPLOAD FILES";
    let progressElement = button.querySelector(".button__progress");
    progressElement.classList.add('notransition');
    progressElement.style.width = "0%";
    progressElement.offsetHeight;
    progressElement.classList.remove('notransition');
    uploadInProgress = false;
    if (speedElement) {
      speedElement.textContent = "";
      speedElement.style.visibility = "hidden";
    }
    
    // Handle network-level errors
    alert('Network error while uploading files. Please check your connection.');
    loadPath(basePath);
  };

  xhr.open("PUT", "/api/file/upload?path=" + encodeURIComponent(basePath));
  xhr.send(formData);
  uploadInProgress = true;
}

function onCheckboxChange(e) {
  let checkbox = e.currentTarget;
  let href = checkbox.dataset.href;
  let fileItemWrapper = checkbox.closest(".file-item-wrapper");
  let a = fileItemWrapper ? fileItemWrapper.querySelector("a") : null;
  
  if (checkbox.checked) {
    if (selectedFiles.indexOf(href) === -1) {
      selectedFiles.push(href);
    }
    if (a) a.classList.add("selected-item");
  } else {
    let index = selectedFiles.indexOf(href);
    if (index > -1) {
      selectedFiles.splice(index, 1);
    }
    if (a) a.classList.remove("selected-item");
  }
  updateButtonStates();
}

function onFileItemOpen(href, isDirectory) {
  if (isDirectory) {
    let path = href.split("/").map(decodeURIComponent).join("/");
    if (path.endsWith("/../")) {
      let parts = path.split("/");
      parts.pop(); // remove empty string after trailing slash
      parts.pop(); // remove ".."
      parts.pop(); // remove current folder
      path = parts.join("/");
      if (!path.startsWith("/")) {
        path = "/" + path;
      }
      if (!path.endsWith("/")) {
        path = path + "/";
      }
    }
    loadPath(path);
  } else {
    window.location.href = href;
  }
}

function clearAllSelections(options = {}) {
  let { clearDom = true, updateButtons = true } = options;
  if (clearDom) {
    let container = document.getElementById("files-container");
    if (container) {
      let fileElements = container.childNodes;
      for (let i = 0; i < fileElements.length; i++) {
        let element = fileElements[i];
        if (!element || element.nodeType !== 1) continue;
        let elementA = element.nodeName.toLowerCase() == 'a' ? element : element.querySelector("a");
        if (!elementA) continue;
        elementA.classList.remove("selected-item");
        if (!deviceHasPointer) {
          let checkbox = element.querySelector(".file-checkbox");
          if (checkbox) checkbox.checked = false;
        }
      }
    }
  }
  selectedFiles = [];
  lastSelectedElement = null;
  if (updateButtons) {
    updateButtonStates();
  }
}

function toggleFileSelection(href, a, e) {
  if (a.textContent == "..") return;
  
  // Check for Ctrl/Cmd key (for multi-select toggle)
  let ctrlKey = e && (e.ctrlKey || e.metaKey);
  
  // Handle shift-click for range selection (only for pointer devices)
  if (deviceHasPointer && e && e.shiftKey && lastSelectedElement != null) {
    let fileElements = document.getElementById("files-container").childNodes;
    let startIndex = -1;
    let endIndex = -1;
    for (let i = 0; i < fileElements.length; i++) {
      let element = fileElements[i];
      if (element.nodeName.toLowerCase() != 'div') continue;
      let elementA = element.querySelector("a");
      if (!elementA) continue;
      if (elementA == a) {
        endIndex = i;
      }
      if (elementA == lastSelectedElement) {
        startIndex = i;
      }
    }
    if (startIndex != -1 && endIndex != -1) {
      if (startIndex > endIndex) {
        let tmp = startIndex;
        startIndex = endIndex;
        endIndex = tmp;
      }
      for (let i = startIndex; i <= endIndex; i++) {
        let element = fileElements[i];
        if (element.nodeName.toLowerCase() != 'div') continue;
        let elementA = element.querySelector("a");
        if (!elementA || elementA.textContent == "..") continue;
        let elementHref = elementA.getAttribute("href");
        let index = selectedFiles.indexOf(elementHref);
        if (index === -1) {
          selectedFiles.push(elementHref);
          elementA.classList.add("selected-item");
        }
      }
      lastSelectedElement = a;
      updateButtonStates();
      return;
    }
  }
  
  // Handle selection based on modifier keys
  if (deviceHasPointer && !ctrlKey) {
    // Without Ctrl: clear all selections and select only this item
    clearAllSelections({ updateButtons: false });
    selectedFiles.push(href);
    a.classList.add("selected-item");
  } else {
    // With Ctrl (or touch device): toggle this item
    let index = selectedFiles.indexOf(href);
    if (index > -1) {
      selectedFiles.splice(index, 1);
      a.classList.remove("selected-item");
      if (!deviceHasPointer) {
        let wrapper = a.closest(".file-item-wrapper");
        let checkbox = wrapper ? wrapper.querySelector(".file-checkbox") : null;
        if (checkbox) checkbox.checked = false;
      }
    } else {
      selectedFiles.push(href);
      a.classList.add("selected-item");
      if (!deviceHasPointer) {
        let wrapper = a.closest(".file-item-wrapper");
        let checkbox = wrapper ? wrapper.querySelector(".file-checkbox") : null;
        if (checkbox) checkbox.checked = true;
      }
    }
  }
  lastSelectedElement = a;
  updateButtonStates();
}

function updateButtonStates() {
  let renameBtn = document.getElementById("rename-button");
  if (renameBtn) {
    renameBtn.disabled = selectedFiles.length != 1;
    document.getElementById("cut-button").disabled = selectedFiles.length == 0;
    document.getElementById("copy-button").disabled = selectedFiles.length == 0;
    document.getElementById("delete-button").disabled = selectedFiles.length == 0;
    document.getElementById("zip-button").disabled = selectedFiles.length == 0;
    let pasteBtn = document.getElementById("paste-button");
    let clipboardJson = sessionStorage.getItem("clipboard");
    pasteBtn.disabled = clipboardJson == null;
    let clipboard = null;
    if (clipboardJson != null) {
      clipboard = JSON.parse(clipboardJson);
    }
    let spanInsidePasteBtn = pasteBtn.querySelector("span");
    spanInsidePasteBtn.textContent = clipboard != null ? ("PASTE " + clipboard.length + " ITEMS") : "PASTE";
  }
}

function updateSearchButtonState() {
  let searchBtn = document.getElementById("search-button");
  if (!searchBtn) return;
  if (currentSearchQuery && currentSearchQuery.trim() !== "") {
    searchBtn.classList.add("toggled");
  } else {
    searchBtn.classList.remove("toggled");
  }
}

function updateScreenSizeAvareUI() {
  let neededViewModeElementDisplayMode = smallScreen ? "none" : "flex";
  let viewModeElement = document.getElementById("view-mode");
  if (viewModeElement.style.display == neededViewModeElementDisplayMode) return;
  viewModeElement.style.display = neededViewModeElementDisplayMode;
  renderFileList();
}

function onNewFolderClick() {
  let name = prompt("Please enter new folder name", "New folder");
  if (name == null) return;
  let xhr = new XMLHttpRequest();
  xhr.open("POST", "/api/file/new-folder", true);
  xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
  let data = {
    path: currentPath,
    name: name
  };
  let urlEncodedDataPairs = [], key;
  for (key in data) {
    urlEncodedDataPairs.push(encodeURIComponent(key) + '=' + encodeURIComponent(data[key]));
  }
  xhr.send(urlEncodedDataPairs.join("&"));
  xhr.onload = function () {
    if (this.status == 204) {
      loadPath(currentPath);
    } else {
      alert("Can not create new folder \"" + name + "\": " + this.statusText + "\n" + this.responseText);
      loadPath(currentPath);
    }
  };
}

function onNewFileClick() {
  let name = prompt("Please enter new file name", "example.txt");
  if (name == null) return;
  let xhr = new XMLHttpRequest();
  xhr.open("PUT", "/api/file/upload?path=" + encodeURIComponent(currentPath), true);
  let formData = new FormData();
  formData.append("files[]", new Blob(), name);
  xhr.send(formData);
  xhr.onload = function () {
    if (this.status == 204) {
      loadPath(currentPath);
    } else {
      alert("Can not create new file \"" + name + "\": " + this.statusText + "\n" + this.responseText);
      loadPath(currentPath);
    }
  };
}

function onPasteClick() {
  let clipboardAction = sessionStorage.getItem("clipboardAction");
  let clipboard = JSON.parse(sessionStorage.getItem("clipboard"));

  let fileElements = document.getElementById("files-container").childNodes;
  let conflicts = [];
  for (let i = 0; i < fileElements.length; i++) {
    let element = fileElements[i];
    // Handle both old structure (direct 'a' elements) and new structure (wrapped in div)
    let a = element.nodeName.toLowerCase() == 'a' ? element : element.querySelector('a');
    if (!a) continue;
    let href = a.getAttribute("href");
    let path = href.split("/").map(decodeURIComponent).join("/");
    for (let j = 0; j < clipboard.length; j++) {
      let cp = clipboard[j];
      let fileName = extractFileOrFolderName(path)
      if (extractFileOrFolderName(cp) == fileName) {
        conflicts.push(fileName);
      }
    }
  }

  if (conflicts.length > 0 &&
    !confirm("Do you want to owerride following items: " + conflicts + "?")) {
    return;
  }

  let xhr = new XMLHttpRequest();
  xhr.open("POST", "/api/file/move", true);
  xhr.setRequestHeader('Content-Type', 'application/json');
  let data = {
    action: clipboardAction,
    path: currentPath,
    files: clipboard
  };
  xhr.send(JSON.stringify(data));
  xhr.onload = function () {
    if (this.status == 204) {
      if ("move" == clipboardAction) {
        sessionStorage.removeItem("clipboard");
        sessionStorage.removeItem("clipboardAction");
      }
      loadPath(currentPath);
    } else {
      alert("Can not process command: " + this.statusText + "\n" + this.responseText);
      loadPath(currentPath);
    }
    updateButtonStates();
  };
}

function extractFileOrFolderName(path) {
  let fileNameParts = path.split("/");
  let fileName = fileNameParts.pop();
  if (path.endsWith("/")) fileName = fileNameParts.pop();//second pop to get folder name
  return fileName;
}

function onRenameClick() {
  let filePath = selectedFiles[0].split("/").map(decodeURIComponent).join("/");
  let fileName = extractFileOrFolderName(filePath);
  let name = prompt("Please enter new name", fileName);
  if (name == null) return;
  let xhr = new XMLHttpRequest();
  xhr.open("POST", "/api/file/rename", true);
  xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
  let data = {
    path: filePath,
    name: name
  };
  let urlEncodedDataPairs = [], key;
  for (key in data) {
    urlEncodedDataPairs.push(encodeURIComponent(key) + '=' + encodeURIComponent(data[key]));
  }
  xhr.send(urlEncodedDataPairs.join("&"));
  xhr.onload = function () {
    if (this.status != 204) {
      alert("Can not rename file \"" + fileName + "\" to \"" + name + "\": " + this.statusText + "\n" + this.responseText);
    }
    loadPath(currentPath);
  };
}

function onPushToClipboardClick(action) {
  let decodedPaths = [];
  for (let i = 0; i < selectedFiles.length; i++) {
    let decodedPath = selectedFiles[i].split("/").map(decodeURIComponent).join("/");
    decodedPaths.push(decodedPath);
  }
  sessionStorage.setItem("clipboard", JSON.stringify(decodedPaths));
  sessionStorage.setItem("clipboardAction", action);
  // Clear selections after copying/cutting
  clearAllSelections();
}

function onDeleteClick() {
  if (!confirm("Are you sure you want to permamently delete " + selectedFiles.length + " selected item[s]")) return;
  let xhr = new XMLHttpRequest();
  xhr.open("DELETE", "/api/file/delete", true);
  xhr.setRequestHeader('Content-Type', 'application/json');
  let filenames = [];
  for (let i = 0; i < selectedFiles.length; i++) {
    let filePath = selectedFiles[i];
    let fileNameParts = filePath.split("/").map(decodeURIComponent);
    let fileName = fileNameParts.pop();
    if (filePath.endsWith("/")) fileName = fileNameParts.pop();//second pop to get folder name
    filenames.push(fileName);
  }
  let data = {
    path: currentPath,
    files: filenames
  };
  xhr.send(JSON.stringify(data));
  xhr.onload = function () {
    if (this.status == 204) {
      loadPath(currentPath);
    } else {
      alert("Error while deleting files: " + this.statusText + "\n" + this.responseText);
      loadPath(currentPath);
    }
  };
}

function onZipClick() {
  let filenames = [];
  for (let i = 0; i < selectedFiles.length; i++) {
    let filePath = selectedFiles[i];
    let fileNameParts = filePath.split("/").map(decodeURIComponent);
    let fileName = fileNameParts.pop();
    if (filePath.endsWith("/")) fileName = fileNameParts.pop();//second pop to get folder name
    filenames.push(fileName);
  }

  //instead of using xhr, we can use form submit to download file
  let form = document.createElement("form");
  form.setAttribute("method", "post");
  let currentFolderName = currentPath.split("/").pop();
  form.setAttribute("action", "/api/file/zip");
  form.setAttribute("target", "_blank");
  let hiddenField = document.createElement("input");
  hiddenField.setAttribute("type", "hidden");
  hiddenField.setAttribute("name", "path");
  hiddenField.setAttribute("value", currentPath);
  form.appendChild(hiddenField);
  hiddenField = document.createElement("input");
  hiddenField.setAttribute("type", "hidden");
  hiddenField.setAttribute("name", "files");
  hiddenField.setAttribute("value", JSON.stringify(filenames));
  form.appendChild(hiddenField);
  hiddenField = document.createElement("input");
  hiddenField.setAttribute("type", "hidden");
  hiddenField.setAttribute("name", "uncompressed");
  let extensionsToStoreUncompressed = "mp3,aacmp3,aac,ogg,m4a,mp4,mkv,avi,mov,webm,flac,opus,jpg,jpeg,png,gif,webp,heic,heif,tiff,pdf,docx,xlsx,pptx,odt,ods,odp,epub,cbz,cbr,zip,rar,7z,gz,xz,bz2,tar.gz,tgz,apk,jar,war,ear,iso,dmg";
  hiddenField.setAttribute("value", extensionsToStoreUncompressed);
  form.appendChild(hiddenField);
  document.body.appendChild(form);
  form.submit();
  document.body.removeChild(form);

  // Clear selections after zip download
  clearAllSelections();
}

function onSearchClick() {
  if (currentSearchQuery && currentSearchQuery.trim() !== "") {
    loadPath(currentPath, null);
    return;
  }
  let searchQuery = prompt("Please enter search query. Use * for wildcard search. Example: *.jpg to search for all jpg files.");
  if (searchQuery == null || searchQuery.trim() == "") return;
  loadPath(currentPath, searchQuery);
}

function loadPath(directoryPath, searchQuery = null) {
  // Clear selections when navigating to a new path
  clearAllSelections({ clearDom: false });
  currentPath = directoryPath;
  currentSearchQuery = searchQuery;
  updateSearchButtonState();
  let sort = "default";
  let currentSortReversed = "false";
  let viewMode = localStorage.getItem("file-list-view-mode");
  if (viewMode == "grid") {
    sort = "gallery";
  } else if (viewMode == "table") {
    sort = localStorage.sort ? localStorage.sort : "default";
    currentSortReversed = localStorage.sortReversed == "true";
  }
  document.getElementById("path").innerText = directoryPath;
  document.getElementById("loader").style.visibility = "visible";
  if (window.history.state == null || window.history.state.path != directoryPath) {
    let encodedPath = directoryPath.split("/").map(encodeURIComponent).join("/");
    window.history.pushState({ path: directoryPath }, directoryPath, encodedPath);
  }
  let req = new XMLHttpRequest();
  req.overrideMimeType("application/json");
  req.open('GET', "/api/file/list?path=" + encodeURIComponent(directoryPath) + 
  "&sort=" + sort + "&sort-reversed=" + currentSortReversed + 
  "&search=" + encodeURIComponent(searchQuery || ""), true);
  req.onload = function () {
    if (req.status != 200) {
      alert("Error while loading list of files: " + req.statusText + "\n" + req.responseText);
      return;
    }
    files = JSON.parse(req.responseText);
    renderFileList();
  };
  req.onloadend = function (e) {
    document.getElementById("loader").style.visibility = "hidden";
  }
  req.onreadystatechange = () => {
    if (req.status === 500) {
      alert("Error while loading list of files");
    }
  };
  req.send(null);
}

function onFileItemClick(e) {
  e.preventDefault();
  let a = e.currentTarget;
  let href = a.getAttribute("href");
  let isDirectory = a.dataset.directory == "true";
  
  if (deviceHasPointer) {
    // With pointer: single click selects, double click opens (handled by dblclick listener)
    toggleFileSelection(href, a, e);
  } else {
    // Without pointer: click opens file/folder
    onFileItemOpen(href, isDirectory);
  }
}

function viewModeChange(src) {
  localStorage.setItem("file-list-view-mode", src.value);
  renderFileList();
}

function onPageResize() {
  smallScreen = window.innerWidth < SMALL_UI_MAX_SCREEN_SIZE;
  clearTimeout(resizeTimer);
  resizeTimer = setTimeout(updateScreenSizeAvareUI, 100);
}

function dropHandler(ev) {
  console.log("File(s) dropped");
  ev.stopPropagation();
  ev.preventDefault();
  if (uploadInProgress || !allowEditing) return;

  if (ev.dataTransfer.items) {
    processDroppedDataTransferItems([...ev.dataTransfer.items]);
  } else {
    let files = [];
    [...ev.dataTransfer.files].forEach((file, i) => {
      console.log(`… file[${i}].name = ${file.name}`);
      files.push(file);
    });
    if (files.length == 0) return;
    startFilesUpload(currentPath, files);
  }
}

async function processDroppedDataTransferItems(items) {
  let files = [];
  let relativePaths = [];
  let emptyDirs = [];
  let dirs = [];
  for (let i = 0; i < items.length; i++) {
    const item = items[i];
    if (item.kind === "file") {
      const entry = item.webkitGetAsEntry() || item.getAsEntry();
      if (entry) {
        if (entry.isDirectory) {
          dirs.push(entry);
        } else {
          const file = item.getAsFile();
          console.log(`… file[${i}].name = ${file.name}`);
          files.push(file);
          relativePaths.push(file.name);
        }
      } else {
        const file = item.getAsFile();
        console.log(`… file[${i}].name = ${file.name}`);
        files.push(file);
      }
    }
  }
  for (let i = 0; i < dirs.length; i++) {
    await readDirectoryFilesRecursively(dirs[i], files, relativePaths, emptyDirs);
  }
  if (files.length == 0) return;
  startFilesUpload(currentPath, files, relativePaths.length == files.length ? relativePaths : null, emptyDirs);
}

async function readDirectoryFilesRecursively(directory, files, relativePaths, emptyDirs, currentRelativePath = "") {
  const relativePath = currentRelativePath + "/" + directory.name;
  const entries = await new Promise((resolve, reject) => {
    const reader = directory.createReader();
    reader.readEntries(resolve, reject);
  });
  for (let i = 0; i < entries.length; i++) {
    const entry = entries[i];
    if (entry.isDirectory) {
      await readDirectoryFilesRecursively(entry, files, relativePaths, emptyDirs, relativePath);
    } else {
      const file = await new Promise((resolve, reject) => {
        entry.file(resolve, reject);
      });
      console.log(`… file[${i}].name = ${file.name}`);
      files.push(file);
      relativePaths.push(relativePath + "/" + file.name);
    }
  }
  if (entries.length == 0) {
    emptyDirs.push(relativePath);
  }
}

function dragOverHandler(ev) {
  ev.stopPropagation();
  ev.preventDefault();
  if (uploadInProgress || !allowEditing) {
    ev.dataTransfer.dropEffect = 'none';
    return;
  }
  ev.dataTransfer.dropEffect = 'copy';
}

function showContextMenu(x, y, href, isFolder) {
  let contextMenu = document.getElementById("context-menu");
  let extension = href.split('.').pop().toLowerCase();
  //configure each menu item
  //open in new tab
  let moOpenInNewTab = document.getElementById("mo-open-in-new-tab");
  moOpenInNewTab.onclick = function () {
    window.open(href, "_blank");
  }
  //edit as text
  let moEditAsText = document.getElementById("mo-edit-as-text");
  let nonEditableAsTextForSure = ["jpg", "jpeg", "png", "raw", "webp", "gif", "bmp", "tga", "avi", "mp4", "3gp", "zip", "apk", "exe", "pdf", "doc", "xls", "ppt", "rtf"].includes(extension);
  moEditAsText.style.display = (isFolder || nonEditableAsTextForSure) ? "none" : "block";
  moEditAsText.onclick = function () {
    window.open("/shttps-static-public/text-editor/index.html?path=" + href, "_blank");
  }
  //rename
  let moRename = document.getElementById("mo-rename");
  moRename.style.display = !allowEditing ? "none" : "block";
  moRename.onclick = function () {
    selectedFiles = [href];
    onRenameClick();
    selectedFiles = [];
  }
  //delete
  let moDelete = document.getElementById("mo-delete");
  moDelete.style.display = !allowEditing ? "none" : "block";
  moDelete.onclick = function () {
    selectedFiles = [href];
    onDeleteClick();
    selectedFiles = [];
  }
  //download
  let moDownload = document.getElementById("mo-download");
  moDownload.style.display = isFolder ? "none" : "block";
  moDownload.onclick = function () {
    let fileUrl = window.location.origin + href + "?download=true";
    window.open(fileUrl, "_blank");
  }
  //zip
  let moZip = document.getElementById("mo-zip");
  moZip.style.display = isFolder ? "block" : "none";
  moZip.onclick = function () {
    selectedFiles = [href];
    onZipClick();
    selectedFiles = [];
  }
  //copy link
  let moCopyLink = document.getElementById("mo-copy-link");
  moCopyLink.onclick = function () {
    let link = window.location.origin + href;
    let wasError = false;
    try {
      if (isSecureContext && navigator.clipboard) {
        navigator.clipboard.writeText(link);
      } else {
        let dummy = document.createElement("input");
        document.body.appendChild(dummy);
        dummy.value = link;
        dummy.select();
        document.execCommand("copy");
        document.body.removeChild(dummy);
      }
    } catch (error) {
      wasError = true;
      console.error("Can not copy link to clipboard");
      alert("Can not copy link to clipboard");
    }
    if (!wasError) {
      alert("Link copied to clipboard");
    }
  }

  displayContextMenu(x, y, contextMenu);
}

function onMenuClick(e) {
  let menuButton = document.getElementById("menu-button");
  let mainMenu = document.getElementById("main-menu");
  let mmStatus = document.getElementById("mm-status");
  let mmLogin = document.getElementById("mm-login");
  let mmDatabase = document.getElementById("mm-database");
  if (mmStatus) {
    mmStatus.onclick = function () {
      window.location.href = "/shttps-static-public/status/index.html";
    }
  }
  if (mmDatabase) {
    mmDatabase.onclick = function () {
      window.location.href = "/shttps-static-public/db-browser/index.html";
    }
  }
  if (mmLogin) {
    mmLogin.onclick = function () {
      window.location.href = "/shttps-pages/login/";
    }
  }
  let mmLogout = document.getElementById("mm-logout");
  if (mmLogout) {
    mmLogout.onclick = function () {
      let xhr = new XMLHttpRequest();
      xhr.open("POST", "/api/user/logout", true);
      xhr.send();
      xhr.onload = function () {
        if (this.status == 204) {
          window.location.href = "/";
        } else {
          alert("Error while logging out: " + this.statusText + "\n" + this.responseText);
        }
      };
      xhr.onerror = function () {
        alert("Error while logging out: " + this.statusText + "\n" + this.responseText);
      };
    }
  }
  let rect = menuButton.getBoundingClientRect();
  displayContextMenuWithAnchorRect(rect, mainMenu);
  e.stopPropagation();
  e.preventDefault();
}

function onPageLoad() {
  // Prevent native context menu on mobile
  document.addEventListener('contextmenu', function(e) {
    e.preventDefault();
  });

  updateButtonStates();
  updateScreenSizeAvareUI();

  let viewMode = localStorage.getItem("file-list-view-mode");
  if (!viewMode) {
    viewMode = "list";
  }
  let actionBar = document.getElementById("actionbar");
  if (actionBar) actionBar.style.display = "flex";
  document.getElementById("view-mode").style.visibility = "visible";
  let radioToCheck = document.getElementById("radioList");
  if (viewMode == "table") {
    radioToCheck = document.getElementById("radioTable");
  } else if (viewMode == "grid") {
    let gridElement = document.getElementById("radioGrid");
    if (gridElement != null) {
        radioToCheck = gridElement;
    }
  }
  radioToCheck.checked = true;

  allowEditing = document.getElementById("files") != null;

  window.addEventListener("popstate", function (e) {
    if (e.state == null) return;
    loadPath(e.state.path);
  });

  loadPath(currentPath);
}