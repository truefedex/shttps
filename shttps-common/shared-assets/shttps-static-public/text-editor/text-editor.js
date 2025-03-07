let editedFilePath = null;
let textData = null;
let loader = document.getElementById('loader');
let saveButton = document.getElementById('save-button');
let undoButton = document.getElementById('undo-button');
let redoButton = document.getElementById('redo-button');
let editor = null;

function onPageLoad() {
    loader.style.display = 'block';
    editedFilePath = new URLSearchParams(window.location.search).get('path');
    let title = editedFilePath.split('/').pop();
    document.getElementById('file-name').innerText = title;

    fetch(editedFilePath + `?t=${Date.now()}`)
        .then(response => response.text())
        .then(data => {
            onTextContentLoaded(data);
            loader.style.display = 'none';
        });
}

function onSaveClick(e) {
    let data = editor.getValue();
    let path = editedFilePath.split('/').slice(0, -1).join('/');//remove file name from path
    if (path === '') {
        path = '/';
    }
    let filename = editedFilePath.split('/').pop();
    let url = "/api/file/upload?path=" + encodeURIComponent(path);
    //send as post request with form data
    let formData = new FormData();
    formData.append("files[]", new Blob([data], { type: 'text/plain' }), filename);
    fetch(url, {
        method: 'PUT',
        body: formData
    }).then(response => {
        if (response.ok) {
            showToast('File saved successfully');
            saveButton.disabled = true;
        } else {
            showToast('Failed to save file');
        }
    });
}

function onUndoClick(e) {
    editor.undo();
    undoButton.disabled = !editor.hasUndo();
    redoButton.disabled = !editor.hasRedo();
}

function onRedoClick(e) {
    editor.redo();
    undoButton.disabled = !editor.hasUndo();
    redoButton.disabled = !editor.hasRedo();
}

function onSettingsClick(e) {
    editor.showSettingsMenu();
}

function onTextContentLoaded(data) {
    textData = data;
    editor = new EditorWrapper(document.getElementById('editor'));
    if (editor.tryToInitSettingsMenu()) {
        document.getElementById('settings-button').style.display = 'block';
    }
    editor.selectModeForPath(editedFilePath);
    
    editor.setValue(data);
    
    editor.setOnChange(function() {
        saveButton.disabled = false;
        undoButton.disabled = !editor.hasUndo();
        redoButton.disabled = !editor.hasRedo();
    });
}

function showToast(msg, duration = 3000) {
    var snackbar = document.getElementById("snackbar");
    snackbar.className = "show";
    snackbar.innerText = msg;
    setTimeout(function(){ snackbar.className = snackbar.className.replace("show", ""); }, duration);
}