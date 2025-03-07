//Wrapper around ace-based editor and fallback to textarea (in case if ace is not loaded)
//take as constructor argument the element to attach the editor to
class EditorWrapper {
    constructor(element) {
        this.element = element;
        this.editor = null;
        this.textarea = null;
        this.init();
    }

    init() {
         if (typeof ace === 'undefined') {
            this.textarea = document.createElement('textarea');
            this.element.appendChild(this.textarea);
         } else {
            this.editor = ace.edit(this.element);
            this.editor.setTheme("ace/theme/monokai");
            this.editor.session.setMode("ace/mode/sql");
            this.editor.setOptions({
                enableBasicAutocompletion: true,
                enableSnippets: true,
                enableLiveAutocompletion: false
            });
         }
    }

    getValue() {
        if (this.editor) {
            return this.editor.getValue();
        } else {
            return this.textarea.value;
        }
    }

    setValue(value) {
        if (this.editor) {
            this.editor.setValue(value, -1);
            this.editor.clearSelection();
            this.editor.focus();
            this.editor.session.getUndoManager().reset();
        } else {
            this.textarea.value = value;
        }
    }

    setOnChange(callback) {
        if (this.editor) {
            this.editor.on('change', callback);
        } else {
            this.textarea.addEventListener('input', callback);
        }
    }

    tryToInitSettingsMenu() {
        if (this.editor) {
            ace.require('ace/ext/settings_menu').init(this.editor);
            return true;
        } else {
            return false;
        }
    }

    selectModeForPath(path) {
        if (this.editor) {
            let aceModeList = ace.require("ace/ext/modelist");
            let mode = aceModeList.getModeForPath(path).mode;
            this.editor.session.setMode(mode);
        }
    }

    hasUndo() {
        if (this.editor) {
            return this.editor.session.getUndoManager().hasUndo();
        } else {
            return true;
        }
    }

    hasRedo() {
        if (this.editor) {
            return this.editor.session.getUndoManager().hasRedo();
        } else {
            return true;
        }
    }

    undo() {
        if (this.editor) {
            this.editor.session.getUndoManager().undo();
        } else {
            document.execCommand('undo');
        }
    }

    redo() {
        if (this.editor) {
            this.editor.session.getUndoManager().redo();
        } else {
            document.execCommand('redo');
        }
    }

    showSettingsMenu() {
        if (this.editor) {
            this.editor.showSettingsMenu();
        }
    }
}