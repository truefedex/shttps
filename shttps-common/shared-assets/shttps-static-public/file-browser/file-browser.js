let files = [];
let uploadInProgress = false;
let selectedFiles = [];
let lastSelectedElement = null;
let currentPath = decodeURIComponent(window.location.pathname);
let currentSearchQuery = null;
let allowEditing = false;

let resizeTimer;

const TABLE_COLUMNS = [
  { sort: "default", label: "File Name", grow: 1 },
  { sort: "modified", label: "Last Modified", width: "190px" },
  { sort: "size", label: "Size", width: "70px" }
];

const THUMBNAIL_EXTENSIONS = ["jpg", "jpeg", "png", "gif", "bmp", "tga", "avi", "mp4", "3gp"];

const EXTENSIONS_TO_STORE_UNCOMPRESSED = "mp3,aacmp3,aac,ogg,m4a,mp4,mkv,avi,mov,webm,flac,opus,jpg,jpeg,png,gif,webp,heic,heif,tiff,pdf,docx,xlsx,pptx,odt,ods,odp,epub,cbz,cbr,zip,rar,7z,gz,xz,bz2,tar.gz,tgz,apk,jar,war,ear,iso,dmg";

/** Percent-encodes every path segment, leaving the separators alone. */
function encodePath(path) {
  return path.split("/").map(encodeURIComponent).join("/");
}

/** The inverse of encodePath: an href from the listing back to a plain path. */
function decodePath(href) {
  return href.split("/").map(decodeURIComponent).join("/");
}

/** The <a> of every file item currently rendered, in display order. */
function listedFileItems() {
  return Array.from(document.querySelectorAll("#files-container .file-item-wrapper > a.file-item"));
}

/** Names of the items currently listed, to detect upload and paste conflicts. */
function listedFileNames() {
  return listedFileItems().map(a => extractFileOrFolderName(decodePath(a.getAttribute("href"))));
}

/** The view mode the user picked, or the best default this server can offer. */
function getViewMode() {
  return localStorage.getItem("file-list-view-mode") || getDefaultViewMode();
}

function getDefaultViewMode() {
  // grid view requires thumbnails support (radioGrid rendered server-side) and JS running
  return document.getElementById("radioGrid") != null ? "grid" : "list";
}

function renderFileList() {
  let container = document.getElementById("files-container");
  container.innerHTML = "";
  let viewMode = smallScreen ? "list" : getViewMode();
  let availableWidth = window.innerWidth - 20;//- page padding
  let itemsMarging = 5;
  //table is one item per row, the other modes fit as many as their minimal size allows
  let minItemSize = viewMode == "grid" ? 140 : 300;
  let itemsPerRow = viewMode == "table" ? 1 : Math.floor(availableWidth / minItemSize);
  if (itemsPerRow < 1) itemsPerRow = 1;//a viewport narrower than one item still gets one column
  let itemWidth = (availableWidth - (itemsMarging * (itemsPerRow - 1))) / itemsPerRow;
  container.style.gridTemplateColumns = "1fr ".repeat(itemsPerRow);
  container.style.gridAutoRows = viewMode == "grid" ? itemWidth + "px" : "auto";
  container.dataset.viewMode = viewMode;

  if (viewMode == "table") {
    container.appendChild(createTableCaption());
  }

  for (let file of files) {
    let path = currentPath + (currentPath.endsWith("/") ? "" : "/") +
      (file.relativePath != null ? file.relativePath : "") + file.name;
    if (file.directory) {
      path = path + "/";
    }
    let href = encodePath(path);

    // Create wrapper for file item (to support checkbox)
    let fileItemWrapper = document.createElement("div");
    fileItemWrapper.classList.add("file-item-wrapper");

    let a = document.createElement("a");
    a.classList.add("file-item");
    a.dataset.directory = file.directory;
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
    if (deviceHasPointer) {
      // With pointer: right click shows context menu, double click opens
      a.addEventListener('contextmenu', function (e) {
        e.preventDefault();
        showContextMenu(e.clientX, e.clientY, href, file.directory);
      });
      a.addEventListener('dblclick', function (e) {
        e.preventDefault();
        onFileItemOpen(href, file.directory);
      });
    } else {
      // Without pointer: long press shows context menu
      onLongPress(a, function (e) {
        showContextMenu(e.clientX, e.clientY, href, file.directory);
      });
    }

    switch (viewMode) {
      case "grid":
        appendGridCardContent(a, file, path);
        break;
      case "table":
        appendTableRowContent(a, file);
        break;
      default://list
        let div = document.createElement("div");
        if (file.directory) div.classList.add("folder");
        div.innerText = file.name;
        a.appendChild(div);
        break;
    }

    fileItemWrapper.appendChild(a);
    container.appendChild(fileItemWrapper);
  }

  let dropHint = document.getElementById("drop-hint");
  if (dropHint) {
    let showDropHint = allowEditing && deviceHasPointer && files.length < 2;
    dropHint.style.display = showDropHint ? "inline-block" : "none";
  }
}

/** The clickable header of the table view, one cell per sortable column. */
function createTableCaption() {
  let currentSort = localStorage.sort ? localStorage.sort : "default";
  let currentSortReversed = localStorage.sortReversed == "true";
  let captionDiv = document.createElement("div");
  captionDiv.classList.add("table-caption");
  for (let column of TABLE_COLUMNS) {
    let columnDiv = document.createElement("div");
    let sortMark = currentSort == column.sort ? (currentSortReversed ? "↑" : "↓") : "";
    columnDiv.innerText = sortMark + " " + column.label;
    if (column.grow) columnDiv.style.flexGrow = column.grow;
    if (column.width) columnDiv.style.width = column.width;
    columnDiv.addEventListener("click", function () { tableSortChange(column.sort); });
    captionDiv.appendChild(columnDiv);
  }
  return captionDiv;
}

function appendGridCardContent(a, file, path) {
  a.classList.add("grid-card");
  let mediaWrapper = document.createElement("div");
  mediaWrapper.classList.add("grid-card-media");
  a.appendChild(mediaWrapper);

  if (file.directory) {
    let folderPlaceholder = document.createElement("div");
    folderPlaceholder.classList.add("grid-folder-placeholder");
    folderPlaceholder.innerText = "📁";
    mediaWrapper.appendChild(folderPlaceholder);
  } else {
    let fileExt = file.name.includes(".") ? file.name.split('.').pop().toLowerCase() : "";
    if (THUMBNAIL_EXTENSIONS.includes(fileExt)) {
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
  }

  let fileNameOverlay = document.createElement("div");
  fileNameOverlay.innerText = file.name;
  fileNameOverlay.classList.add("grid-file-name");
  a.appendChild(fileNameOverlay);
}

function appendTableRowContent(a, file) {
  a.style.textDecoration = "none";
  let div = document.createElement("div");
  if (file.directory) div.classList.add("folder");
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
  a.appendChild(div);
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
  let formData = new FormData();
  let listed = listedFileNames();
  let conflicts = new Set();
  for (let i = 0; i < files.length; i++) {
    let file = files[i];
    let nameWithRelativePath = relativePaths != null ? relativePaths[i] : file.name;
    formData.append("files[]", file, nameWithRelativePath);
    //only the topmost element of an uploaded tree can clash with what is listed here
    let topLevelName = nameWithRelativePath.replace(/^\//, "").split("/")[0];
    if (listed.includes(topLevelName)) {
      conflicts.add(topLevelName);
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
  let progressElement = button.querySelector(".button__progress");
  let lastSpeedUpdateTime = new Date();
  let lastSpeedLoaded = 0;

  function showSpeed(text) {
    speedElement.textContent = text;
    speedElement.style.visibility = text ? "visible" : "hidden";
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

  function uploadFinished() {
    textElement.textContent = "UPLOAD FILES";
    progressElement.classList.add('notransition');
    progressElement.style.width = "0%";
    progressElement.offsetHeight; // Trigger a reflow, flushing the CSS changes
    progressElement.classList.remove('notransition');
    showSpeed("");
    uploadInProgress = false;
  }

  textElement.textContent = "UPLOADING...";
  showSpeed("");

  let xhr = new XMLHttpRequest();

  xhr.upload.onprogress = function (event) {
    let now = new Date();
    let timeSinceUpdate = (now - lastSpeedUpdateTime) / 1000;
    if ((timeSinceUpdate >= 1) || event.loaded === event.total) {
      let bytesDiff = event.loaded - lastSpeedLoaded;
      let bytesPerSecond = timeSinceUpdate > 0 ? bytesDiff / timeSinceUpdate : 0;
      showSpeed(formatSpeed(bytesPerSecond));
      lastSpeedUpdateTime = now;
      lastSpeedLoaded = event.loaded;
    }
    progressElement.style.width = (event.loaded * 100 / event.total) + "%";
  };

  xhr.onload = function () {
    uploadFinished();
    if (xhr.status == 204) {
      setTimeout(() => {//wait for server to process files
        loadPath(basePath);
      }, 500);
    } else {
      alert('Error while uploading files: ' + xhr.status + ' ' + xhr.statusText + '\n' + xhr.responseText);
      loadPath(basePath);
    }
  };

  xhr.onerror = function () {
    uploadFinished();
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
  let a = checkbox.closest(".file-item-wrapper").querySelector("a");
  let index = selectedFiles.indexOf(href);
  if (checkbox.checked) {
    if (index === -1) selectedFiles.push(href);
    a.classList.add("selected-item");
  } else {
    if (index > -1) selectedFiles.splice(index, 1);
    a.classList.remove("selected-item");
  }
  updateButtonStates();
}

function onFileItemOpen(href, isDirectory) {
  if (isDirectory) {
    let path = decodePath(href);
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
    listedFileItems().forEach(a => setItemSelected(a, false));
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
    let items = listedFileItems();
    let startIndex = items.indexOf(lastSelectedElement);
    let endIndex = items.indexOf(a);
    if (startIndex != -1 && endIndex != -1) {
      if (startIndex > endIndex) {
        let tmp = startIndex;
        startIndex = endIndex;
        endIndex = tmp;
      }
      for (let i = startIndex; i <= endIndex; i++) {
        let elementA = items[i];
        if (elementA.textContent == "..") continue;
        let elementHref = elementA.getAttribute("href");
        if (selectedFiles.indexOf(elementHref) === -1) {
          selectedFiles.push(elementHref);
          setItemSelected(elementA, true);
        }
      }
      lastSelectedElement = a;
      updateButtonStates();
      return;
    }
  }

  if (deviceHasPointer && !ctrlKey) {
    // Without Ctrl: clear all selections and select only this item
    clearAllSelections({ updateButtons: false });
    selectedFiles.push(href);
    setItemSelected(a, true);
  } else {
    // With Ctrl (or touch device): toggle this item
    let index = selectedFiles.indexOf(href);
    if (index > -1) {
      selectedFiles.splice(index, 1);
      setItemSelected(a, false);
    } else {
      selectedFiles.push(href);
      setItemSelected(a, true);
    }
  }
  lastSelectedElement = a;
  updateButtonStates();
}

/** Marks one file item selected or not, keeping its checkbox (touch UI) in step. */
function setItemSelected(a, selected) {
  a.classList.toggle("selected-item", selected);
  let checkbox = a.closest(".file-item-wrapper").querySelector(".file-checkbox");
  if (checkbox) checkbox.checked = selected;
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

async function onNewFolderClick() {
  let name = prompt("Please enter new folder name", "New folder");
  if (name == null) return;
  try {
    await api("POST", "/api/file/new-folder", new URLSearchParams({ path: currentPath, name: name }));
  } catch (error) {
    alert("Can not create new folder \"" + name + "\": " + error.message);
  }
  loadPath(currentPath);
}

async function onNewFileClick() {
  let name = prompt("Please enter new file name", "example.txt");
  if (name == null) return;
  let formData = new FormData();
  formData.append("files[]", new Blob(), name);
  try {
    await api("PUT", "/api/file/upload?path=" + encodeURIComponent(currentPath), formData);
  } catch (error) {
    alert("Can not create new file \"" + name + "\": " + error.message);
  }
  loadPath(currentPath);
}

async function onPasteClick() {
  let clipboardAction = sessionStorage.getItem("clipboardAction");
  let clipboard = JSON.parse(sessionStorage.getItem("clipboard"));

  let listed = listedFileNames();
  let conflicts = clipboard.map(extractFileOrFolderName).filter(name => listed.includes(name));
  if (conflicts.length > 0 &&
    !confirm("Do you want to owerride following items: " + conflicts + "?")) {
    return;
  }

  try {
    await api("POST", "/api/file/move", {
      action: clipboardAction,
      path: currentPath,
      files: clipboard
    });
    if ("move" == clipboardAction) {
      sessionStorage.removeItem("clipboard");
      sessionStorage.removeItem("clipboardAction");
    }
  } catch (error) {
    alert("Can not process command: " + error.message);
  }
  loadPath(currentPath);
  updateButtonStates();
}

function extractFileOrFolderName(path) {
  let fileNameParts = path.split("/");
  let fileName = fileNameParts.pop();
  if (path.endsWith("/")) fileName = fileNameParts.pop();//second pop to get folder name
  return fileName;
}

async function onRenameClick(path = selectedFiles[0]) {
  let filePath = decodePath(path);
  let fileName = extractFileOrFolderName(filePath);
  let name = prompt("Please enter new name", fileName);
  if (name == null) return;
  try {
    await api("POST", "/api/file/rename", new URLSearchParams({ path: filePath, name: name }));
  } catch (error) {
    alert("Can not rename file \"" + fileName + "\" to \"" + name + "\": " + error.message);
  }
  loadPath(currentPath);
}

function onPushToClipboardClick(action) {
  sessionStorage.setItem("clipboard", JSON.stringify(selectedFiles.map(decodePath)));
  sessionStorage.setItem("clipboardAction", action);
  // Clear selections after copying/cutting
  clearAllSelections();
}

async function onDeleteClick(paths = selectedFiles) {
  if (!confirm("Are you sure you want to permamently delete " + paths.length + " selected item[s]")) return;
  try {
    await api("DELETE", "/api/file/delete", {
      path: currentPath,
      files: paths.map(path => extractFileOrFolderName(decodePath(path)))
    });
  } catch (error) {
    alert("Error while deleting files: " + error.message);
  }
  loadPath(currentPath);
}

function onZipClick(paths = selectedFiles) {
  //instead of using xhr, we can use form submit to download file
  let form = document.createElement("form");
  form.setAttribute("method", "post");
  form.setAttribute("action", "/api/file/zip");
  form.setAttribute("target", "_blank");
  let fields = {
    path: currentPath,
    files: JSON.stringify(paths.map(path => extractFileOrFolderName(decodePath(path)))),
    uncompressed: EXTENSIONS_TO_STORE_UNCOMPRESSED
  };
  for (let name in fields) {
    let hiddenField = document.createElement("input");
    hiddenField.setAttribute("type", "hidden");
    hiddenField.setAttribute("name", name);
    hiddenField.setAttribute("value", fields[name]);
    form.appendChild(hiddenField);
  }
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

async function loadPath(directoryPath, searchQuery = null) {
  // Clear selections when navigating to a new path
  clearAllSelections({ clearDom: false });
  currentPath = directoryPath;
  currentSearchQuery = searchQuery;
  updateSearchButtonState();
  let viewMode = getViewMode();
  let sort = "default";
  let sortReversed = false;
  if (viewMode == "grid") {
    sort = "gallery";
  } else if (viewMode == "table") {
    sort = localStorage.sort ? localStorage.sort : "default";
    sortReversed = localStorage.sortReversed == "true";
  }
  document.getElementById("path").innerText = directoryPath;
  document.getElementById("loader").style.visibility = "visible";
  if (window.history.state == null || window.history.state.path != directoryPath) {
    window.history.pushState({ path: directoryPath }, directoryPath, encodePath(directoryPath));
  }
  let query = new URLSearchParams({
    path: directoryPath,
    sort: sort,
    "sort-reversed": sortReversed,
    search: searchQuery || ""
  });
  try {
    files = await api("GET", "/api/file/list?" + query);
    renderFileList();
  } catch (error) {
    alert("Error while loading list of files: " + error.message);
  } finally {
    document.getElementById("loader").style.visibility = "hidden";
  }
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
  // readEntries() returns at most 100 entries per call; keep calling on the
  // same reader until it returns an empty batch to get the full listing
  const reader = directory.createReader();
  const entries = [];
  while (true) {
    const batch = await new Promise((resolve, reject) => {
      reader.readEntries(resolve, reject);
    });
    if (batch.length == 0) break;
    entries.push(...batch);
  }
  for (let i = 0; i < entries.length; i++) {
    const entry = entries[i];
    if (entry.isDirectory) {
      await readDirectoryFilesRecursively(entry, files, relativePaths, emptyDirs, relativePath);
    } else {
      const file = await new Promise((resolve, reject) => {
        entry.file(resolve, reject);
      });
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
    onRenameClick(href);
  }
  //delete
  let moDelete = document.getElementById("mo-delete");
  moDelete.style.display = !allowEditing ? "none" : "block";
  moDelete.onclick = function () {
    onDeleteClick([href]);
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
    onZipClick([href]);
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
  // Prevent native context menu on mobile
  document.addEventListener('contextmenu', function(e) {
    e.preventDefault();
  });

  setupMainMenu({
    "mm-status": "/shttps-static-public/status/index.html",
    "mm-database": "/shttps-static-public/db-browser/index.html",
    "mm-screen": "/shttps-static-public/screen/index.html",
    "mm-login": "/shttps-pages/login/",
    "mm-logout": onLogoutClick
  });

  updateButtonStates();
  updateScreenSizeAvareUI();

  let actionBar = document.getElementById("actionbar");
  if (actionBar) actionBar.style.display = "flex";
  document.getElementById("view-mode").style.visibility = "visible";
  let viewMode = getViewMode();
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

async function onLogoutClick() {
  try {
    await api("POST", "/api/user/logout");
    window.location.href = "/";
  } catch (error) {
    alert("Error while logging out: " + error.message);
  }
}