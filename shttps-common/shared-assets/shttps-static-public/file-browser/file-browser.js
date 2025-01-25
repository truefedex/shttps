let files = [];
let uploadInProgress = false;
let selectMode = false;
let selectedFiles = [];
let lastSelectedElement = null;
let currentPath = decodeURIComponent(window.location.pathname);
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
    let a = document.createElement("a");
    a.dataset.directory = file.directory;
    let path = currentPath + (currentPath.endsWith("/") ? "" : "/") + file.name;
    let href = encodeURI(path);
    a.setAttribute("href", href);
    a.addEventListener('click', onFileItemClick);
    if (iOS()) {
      onLongPress(a, function (e) {
        showContextMenu(e.pageX, e.pageY, href, file.directory);
      });
    } else {
      a.addEventListener('contextmenu', function (e) {
        e.preventDefault();
        showContextMenu(e.pageX, e.pageY, href, file.directory);
      });
    }
    a.classList.add("file-item");
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
      case "grid":
        a.style.textDecoration = "none";
        div.style.height = "100%";
        if (file.directory) {
          div.innerText = file.name;
        } else {
          div.style.display = "flex";
          div.style.flexDirection = "column";
          let needThumbnail = false;
          let fileExt = file.name.includes(".") ? file.name.split('.').pop().toLowerCase() : "";
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
              thumbnail.style["object-fit"] = "none";
            });
            div.appendChild(thumbnail);
          } else {
            let fileEXTDiv = document.createElement("div");
            fileEXTDiv.innerText = fileExt;
            fileEXTDiv.classList.add("grid-file-ext");
            div.appendChild(fileEXTDiv);
          }
          let fileNameDiv = document.createElement("div");
          fileNameDiv.innerText = file.name;
          fileNameDiv.classList.add("grid-file-name");
          div.appendChild(fileNameDiv);
        }
        break;

      default://list
        div.innerText = file.name;
        break;
    }
    a.appendChild(div);
    container.appendChild(a);
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
  loadPath(currentPath);
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
  let oldTime = new Date();
  let oldProgress = 0;
  let conflicts = new Set();
  for (let i = 0; i < files.length; i++) {
    let file = files[i];
    let nameWithRelativePath = relativePaths != null ? relativePaths[i] : file.name;
    formData.append("files[]", file, nameWithRelativePath);
    let fileElements = document.getElementById("files-container").childNodes;
    for (let j = 0; j < fileElements.length; j++) {
      let element = fileElements[j];
      if (element.nodeName.toLowerCase() != 'a') continue;
      let href = element.getAttribute("href");
      let path = decodeURI(href);
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
  textElement.textContent = "UPLOADING...";

  xhr.upload.onprogress = function (event) {
    let newTime = new Date();
    let timeDiff = (newTime - oldTime) / 1000;
    let progressDiff = (event.loaded - oldProgress) / 1000;
    let kbps = progressDiff / timeDiff;
    console.log('Uploaded ' + event.loaded / 1000 + ' kb from ' + event.total / 1000 + "(" + kbps + "kb/sec)");
    oldTime = newTime;
    oldProgress = event.loaded;

    let percent = event.loaded * 100 / event.total;
    button.querySelector(".button__progress").style.width = percent + "%";
  };

  xhr.upload.onloadend = function (e) {
    textElement.textContent = "UPLOAD FILES";
    let progressElement = button.querySelector(".button__progress");
    progressElement.classList.add('notransition');
    progressElement.style.width = "0%";
    progressElement.offsetHeight; // Trigger a reflow, flushing the CSS changes
    progressElement.classList.remove('notransition');
    uploadInProgress = false;
    setTimeout(() => {//wait for server to process files
      loadPath(basePath);
    }, 500);
  };

  xhr.upload.onerror = function (e) {
    alert('Error while uploading fires!');
  };

  xhr.open("PUT", "/api/file/upload?path=" + encodeURIComponent(basePath));
  xhr.send(formData);
  uploadInProgress = true;
}

function onSelectModeFileClick(e) {
  let a = e.currentTarget;
  if (a.textContent == "..") return;

  let href = a.getAttribute("href");

  if (e.shiftKey && lastSelectedElement != null) {
    let fileElements = document.getElementById("files-container").childNodes;
    let startIndex = -1;
    let endIndex = -1;
    for (let i = 0; i < fileElements.length; i++) {
      let element = fileElements[i];
      if (element.nodeName.toLowerCase() != 'a') continue;
      if (element == a) {
        endIndex = i;
      }
      if (element == lastSelectedElement) {
        startIndex = i;
      }
    }
    if (startIndex == -1 || endIndex == -1) return;
    if (startIndex > endIndex) {
      startIndex--;
      let tmp = startIndex;
      startIndex = endIndex;
      endIndex = tmp;
    } else {
      startIndex++;
    }
    if (startIndex < 0 || endIndex < 0 || startIndex >= fileElements.length || endIndex >= fileElements.length) return;
    for (let i = startIndex; i <= endIndex; i++) {
      let element = fileElements[i];
      if (element.nodeName.toLowerCase() != 'a') continue;
      
      element.classList.toggle("selected-item");
      if (element.classList.contains("selected-item")) {
        selectedFiles.push(element.getAttribute("href"));
      } else {
        let index = selectedFiles.indexOf(element.getAttribute("href"));
        if (index > -1) {
          selectedFiles.splice(index, 1);
        }
      }
    }
  } else {
    a.classList.toggle("selected-item");
    if (a.classList.contains("selected-item")) {
      selectedFiles.push(href);
    } else {
      let index = selectedFiles.indexOf(href);
      if (index > -1) {
        selectedFiles.splice(index, 1);
      }
    }
  }

  lastSelectedElement = a;
  updateButtonStates();
}

function onSelectModeClick(button) {
  setSelectMode(!selectMode);
}

function setSelectMode(value) {
  selectMode = value;
  selectedFiles = [];
  lastSelectedElement = null;
  updateButtonStates();
  let fileElements = document.getElementById("files-container").childNodes;
  for (let i = 0; i < fileElements.length; i++) {
    let element = fileElements[i];
    if (element.nodeName.toLowerCase() != 'a') continue;
    if ((!selectMode) && element.classList.contains("selected-item")) {
      element.classList.remove("selected-item");
    }
  }
}

function updateButtonStates() {
  let selectModeBtn = document.getElementById("select-button");
  if (selectModeBtn) {
    selectModeBtn.style["background-color"] = selectMode ? 'cadetblue' : '#555';
    selectModeBtn.style["color"] = selectMode ? 'darkslategrey' : 'white';
  }
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
    pasteBtn.textContent = clipboard != null ? ("PASTE " + clipboard.length + " ITEMS") : "PASTE";
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
      alert("Can not create new folder \"" + name + "\"");
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
      alert("Can not create new file \"" + name + "\"");
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
    if (element.nodeName.toLowerCase() != 'a') continue;
    let href = element.getAttribute("href");
    let path = decodeURI(href);
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
      alert("Can not process command");
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
  let filePath = decodeURI(selectedFiles[0]);
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
      alert("Can not rename file \"" + fileName + "\" to \"" + name + "\"");
    }
    loadPath(currentPath);
  };
}

function onPushToClipboardClick(action) {
  let decodedPaths = [];
  for (let i = 0; i < selectedFiles.length; i++) {
    decodedPaths.push(decodeURI(selectedFiles[i]));
  }
  sessionStorage.setItem("clipboard", JSON.stringify(decodedPaths));
  sessionStorage.setItem("clipboardAction", action);
  if (selectMode) onSelectModeClick(document.getElementById("select-button"));
  updateButtonStates();
}

function onDeleteClick() {
  if (!confirm("Are you sure you want to permamently delete " + selectedFiles.length + " selected item[s]")) return;
  let xhr = new XMLHttpRequest();
  xhr.open("DELETE", "/api/file/delete", true);
  xhr.setRequestHeader('Content-Type', 'application/json');
  let filenames = [];
  for (let i = 0; i < selectedFiles.length; i++) {
    let filePath = decodeURI(selectedFiles[i]);
    let fileNameParts = filePath.split("/");
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
      alert("Can not process command");
      loadPath(currentPath);
    }
  };
}

function onZipClick() {
  let filenames = [];
  for (let i = 0; i < selectedFiles.length; i++) {
    let filePath = decodeURI(selectedFiles[i]);
    let fileNameParts = filePath.split("/");
    let fileName = fileNameParts.pop();
    if (filePath.endsWith("/")) fileName = fileNameParts.pop();//second pop to get folder name
    filenames.push(fileName);
  }

  //instead of using xhr, we can use form submit to download file
  let form = document.createElement("form");
  form.setAttribute("method", "post");
  let currentFolderName = currentPath.split("/").pop();
  form.setAttribute("action", "/api/file/zip/" + encodeURIComponent(currentFolderName));
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
  document.body.appendChild(form);
  form.submit();
  document.body.removeChild(form);

  setSelectMode(false);
}

function loadPath(directoryPath) {
  setSelectMode(false);
  currentPath = directoryPath;
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
    window.history.pushState({ path: directoryPath }, directoryPath, encodeURI(directoryPath));
  }
  let req = new XMLHttpRequest();
  req.overrideMimeType("application/json");
  req.open('GET', "/api/file/list?path=" + encodeURIComponent(directoryPath) + "&sort=" + sort + "&sort-reversed=" + currentSortReversed, true);
  req.onload = function () {
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
  if (selectMode) {
    onSelectModeFileClick(e);
    return;
  }
  let href = e.currentTarget.getAttribute("href");
  if (e.currentTarget.dataset.directory == "true") {
    let path = decodeURI(href);
    if (path.endsWith("/..")) {
      let parts = path.split("/");
      parts.pop();
      parts.pop();
      path = parts.join("/");
      if (!path.startsWith("/")) {
        path = "/" + path;
      }
    }
    loadPath(path);
  } else {
    window.location.href = href;
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

function onPageLoad() {
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
    radioToCheck = document.getElementById("radioGrid");
  }
  radioToCheck.checked = true;

  allowEditing = document.getElementById("files") != null;

  window.addEventListener("popstate", function (e) {
    if (e.state == null) return;
    loadPath(e.state.path);
  });

  loadPath(currentPath);
}