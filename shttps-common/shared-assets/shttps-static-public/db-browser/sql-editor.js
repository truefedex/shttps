let sqlEditor = null;
let sqlEditorContainer = document.getElementById('sql-editor-container');
let loader = document.getElementById('loader');
let resultContainer = document.getElementById('result-container');
let errorContainer = document.getElementById('error-container');
let successContainer = document.getElementById('success-but-no-data-container');
let tableDataContainer = document.getElementById('table-data-container');
let handler = document.querySelector('#draggable-resizer');
let isHandlerDragging = false;

let columns = null;
let maxRows = localStorage.getItem('max-rows') || 100;

function onPageLoad(e) {
    sqlEditor = new EditorWrapper(sqlEditorContainer);
    sqlEditor.setValue(localStorage.getItem('sql-editor') || '');
    sqlEditor.setOnChange(function() {
        //save the state of the editor to local storage
        localStorage.setItem('sql-editor', sqlEditor.getValue());
    });

    document.addEventListener('mousedown', function(e) {
        if (e.target === handler) {
            isHandlerDragging = true;
        }
    });
      
    document.addEventListener('mousemove', function(e) {
        if (!isHandlerDragging) {
            return false;
        }
        
        let container = sqlEditorContainer;
        container.style.flexGrow = 0;
        container.style.height = (e.clientY - container.getBoundingClientRect().top - 10) + 'px';
    });
    
    document.addEventListener('mouseup', function(e) {
        isHandlerDragging = false;
    });
}

function onExecuteSQLClick(e) {
    loader.style.display = 'block';
    fetch('/api/db/query?' + new URLSearchParams({
        'include-names': true,
        'limit': maxRows
    }).toString(), {
        method: 'POST',
        headers: {
            'Content-Type': 'text/plain'
        },
        body: sqlEditor.getValue()
    }).then(async response => {
        loader.style.display = 'none';
        if (response.ok) {
            const data = await response.json();
            if (!data.data) {
                renderQuerySuccessButNoData(data);
                return;
            }
            if (data.columns) {
                columns = data.columns;
            }
            renderQueryResult(data);
        } else {
            const err = await response.text();
            renderQueryError(err);
        }
    });
}

function onSettingsClick() {
    let newMaxRows = prompt('Enter the maximum number of rows to display', 
        localStorage.getItem('max-rows') || 100);
    if (newMaxRows !== null && !isNaN(newMaxRows)) {
        maxRows = parseInt(newMaxRows);
        localStorage.setItem('max-rows', maxRows);
    }
}

function renderQuerySuccessButNoData(data) {
    errorContainer.style.display = 'none';
    tableDataContainer.style.display = 'none';
    successContainer.style.display = 'block';
    successContainer.innerHTML = `<div class="success">Query executed successfully, but no data returned.</div>`;
}

function renderQueryResult(data) {
    errorContainer.style.display = 'none';
    successContainer.style.display = 'none';
    tableDataContainer.style.display = 'block';
    let tableElement = document.getElementById('table-data');
    let headerElement = document.getElementById('table-header');
    headerElement.innerHTML = '';
    for (let column of columns) {
        let columnElement = document.createElement('th');
        let columnNameElement = document.createElement('div');
        columnNameElement.textContent = column;
        columnNameElement.className = 'column-name';
        columnElement.appendChild(columnNameElement);
        headerElement.appendChild(columnElement);
    }

    let tableBody = document.getElementById('table-body');
    tableBody.innerHTML = '';
    for (let row of data.data) {
        let rowElement = document.createElement('tr');
        rowElement.className = 'db-row';
        let columnIndex = 0;
        for (let column of row) {
            let columnElement = document.createElement('td');
            columnElement.style.minWidth = 'auto';
            let escapedHtml;

            if (column === null) {
                escapedHtml = '<span style="color: #7f8;">NULL</span>';
            } else if (column.constructor.name === "Object" && column.type === "blob") {
                escapedHtml = '<span style="color: #00f;">BLOB</span>';
            } else {
                escapedHtml = column.toString().replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
            }
            columnElement.innerHTML = escapedHtml;
            columnElement.title = column;
            rowElement.appendChild(columnElement);
            columnIndex++;
        }
        tableBody.appendChild(rowElement);
    }

    if (data.data.length == maxRows) {
        let rowElement = document.createElement('tr');
        let columnElement = document.createElement('td');
        columnElement.colSpan = columns.length;
        columnElement.classList.add('last-row-limit-hint');
        columnElement.textContent = 'Results limited to ' + maxRows + ' rows. You can change this in settings.';
        rowElement.appendChild(columnElement);
        tableBody.appendChild(rowElement);
    }

    document.getElementById('loader').style.visibility = 'hidden';
}

function renderQueryError(err) {
    errorContainer.style.display = 'block';
    tableDataContainer.style.display = 'none';
    successContainer.style.display = 'none';
    errorContainer.innerHTML = `<div class="error">${err}</div>`;
}