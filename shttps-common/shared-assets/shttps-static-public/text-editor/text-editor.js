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
    editor.session.getUndoManager().undo();
    undoButton.disabled = !editor.session.getUndoManager().hasUndo();
    redoButton.disabled = !editor.session.getUndoManager().hasRedo();
}

function onRedoClick(e) {
    editor.session.getUndoManager().redo();
    undoButton.disabled = !editor.session.getUndoManager().hasUndo();
    redoButton.disabled = !editor.session.getUndoManager().hasRedo();
}

function onSettingsClick(e) {
    editor.showSettingsMenu();
}

function onTextContentLoaded(data) {
    textData = data;
    editor = ace.edit("editor");
    editor.setTheme("ace/theme/monokai");
    ace.require('ace/ext/settings_menu').init(editor);
    let aceModeList = ace.require("ace/ext/modelist");
    let mode = aceModeList.getModeForPath(editedFilePath).mode;
    editor.session.setMode(mode);
    editor.setOptions({
        enableBasicAutocompletion: true,
        enableSnippets: true,
        enableLiveAutocompletion: true
    });
    editor.setValue(data, -1);
    editor.clearSelection();
    editor.focus();
    editor.session.getUndoManager().reset();
    editor.session.on('change', function(delta) {
        saveButton.disabled = false;
        undoButton.disabled = !editor.session.getUndoManager().hasUndo();
        redoButton.disabled = !editor.session.getUndoManager().hasRedo();
    });
}

function showToast(msg, duration = 3000) {
    var snackbar = document.getElementById("snackbar");
    snackbar.className = "show";
    snackbar.innerText = msg;
    setTimeout(function(){ snackbar.className = snackbar.className.replace("show", ""); }, duration);
  }