let tableName = new URL(document.location.toString()).searchParams.get('table');
let tableSchema = null;
let rowCount = 0;
let currentPage = 1;
let rowsPerPage = calculateRowsPerPage();
let autoReloadOnResizeTimer = null;
let filters = {};
let sort = null;
let selectedRows = [];

let mainUI = document.getElementById('mainUI');
let tableTitle = document.getElementById('table-name');
let newRecordButton = document.getElementById('new-record-button');
let deleteRowsButton = document.getElementById('delete-rows-button');
let editRowButton = document.getElementById('edit-row-button');

function onPageLoad() {
    tableTitle.textContent = tableName;

    window.addEventListener("resize", () => {
        window.clearTimeout(autoReloadOnResizeTimer);
        autoReloadOnResizeTimer = window.setTimeout(onPageResize, 500);
    });

    document.getElementById('prevPage').addEventListener('click', () => {
        if (currentPage > 1) {
            currentPage--;
            fetchTableData();
            document.getElementById('currentPage').textContent = currentPage;
        }
    });
    
    document.getElementById('nextPage').addEventListener('click', () => {
        currentPage++;
        fetchTableData();
        document.getElementById('currentPage').textContent = currentPage;
    });

    document.getElementById('firstPage').addEventListener('click', () => {
        currentPage = 1;
        fetchTableData();
        document.getElementById('currentPage').textContent = currentPage;
    });

    document.getElementById('lastPage').addEventListener('click', () => {
        currentPage = Math.ceil(rowCount / rowsPerPage);
        fetchTableData();
        document.getElementById('currentPage').textContent = currentPage;
    });

    deleteRowsButton.addEventListener('click', () => {
        let confirmation = confirm('Are you sure you want to delete the selected rows?');
        if (confirmation) {
            deleteSelectedRows();
        }
    });

    newRecordButton.addEventListener('click', () => {
        showRowEditorDialog([], true);
    });

    editRowButton.addEventListener('click', () => {
        showRowEditorDialog(selectedRows[0]);
    });

    fetchTableSchema(() => {
        fetchTableData();
    });
}

function calculateRowsPerPage() {
    let tableDataContainer = document.getElementById('table-data-container');
    const rowHeight = 30 + 2;
    const availableHeight = tableDataContainer.clientHeight - 70; // 60px for the header + padding + horizontal scrollbar
    return Math.floor(availableHeight / rowHeight);
}

function onPageResize() {
    rowsPerPage = calculateRowsPerPage();
    fetchTableData();
}

function fetchTableSchema(onDone) {
    // fetch the table schema /api/db/schema?table=tableName
    fetch(`/api/db/schema?table=${tableName}`)
        .then(response => response.json())
        .then(data => {
            tableSchema = data;
            rowCount = tableSchema.rowCount;
            detectTypesHints();
            onDone();
        }
    );
}

function detectTypesHints() {
    //detect types by sqlite's type affinity (https://www.sqlite.org/datatype3.html)
    for (let column of tableSchema.columns) {
        let columnHints = {};
        let affinity = "NUMERIC";//can be INTEGER, REAL, TEXT, BLOB, NUMERIC
        let internalType = "TEXT";//can be TEXT, MULTILINE_TEXT, INTEGER, REAL, ENUM, BOOLEAN, DATE, TIME, DATETIME, UNIX_TIMESTAMP, BLOB
        if (column.type.includes("INT")) {
            affinity = "INTEGER";
            internalType = "INTEGER";
        } else if (column.type.includes("CHAR") || column.type.includes("CLOB") || column.type.includes("TEXT")) {
            affinity = "TEXT";
            internalType = "TEXT";
        } else if (column.type.includes("BLOB") || !column.type) {
            affinity = "BLOB";
            internalType = "BLOB";
        } else if (column.type.includes("REAL") || column.type.includes("FLOA") || column.type.includes("DOUB")) {
            affinity = "REAL";
            internalType = "REAL";
        }
        columnHints['affinity'] = affinity;
        columnHints['internalType'] = internalType;
        columnHints['useDefault'] = column.defaultValue !== undefined && column.defaultValue !== null;
        columnHints['useNull'] = !columnHints['useDefault'] && !column.notNull && !column.primaryKey;

        column.hints = columnHints;
    }
}

function fetchTableData() {
    if (rowsPerPage < 1) {
        return;
    }
    selectedRows = [];
    updateButtonStates();

    let loader = document.getElementById('loader');
    loader.style.visibility = 'visible';
    
    let params = new URLSearchParams();
    params.append('table', tableName);
    params.append('limit', rowsPerPage);
    params.append('offset', (currentPage - 1) * rowsPerPage);

    if (tableSchema.hasRowId) {
        params.append('includeRowId', 'true');
    }

    if (Object.keys(filters).length) {
        let formattedFilters = {};
        let filtersClauses = [];
        for (let filter in filters) {
            filtersClauses.push(filter + "?");
        }
        formattedFilters['clauses'] = filtersClauses;
        let filterArgs = [];
        for (let filter in filters) {
            filterArgs.push("%" + filters[filter] + "%");
        }
        formattedFilters['args'] = filterArgs;
        params.append('filters', JSON.stringify(formattedFilters));
    }

    if (sort) {
        params.append('sort', sort.column);
        params.append('sort-order', sort.order);
    }

    fetch("/api/db/table", {
            method: 'POST',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded'
            },
            body: params
        }
    )
        .then(response => response.json())
        .then(data => {
            renderTableData(data);
            renderPager();
        }
    );
}

function deleteSelectedRows() {
    let params = new URLSearchParams();
    params.append('table', tableName);

    let compositeKeyColumns = tableSchema.columns.filter(column => column.primaryKey);
    if (tableSchema.hasRowId || compositeKeyColumns.length === 1) {
        let columnName = tableSchema.hasRowId ? 'rowid' : compositeKeyColumns[0].name;
        let columnIndex = tableSchema.hasRowId ? 0 : (tableSchema.columns.indexOf(compositeKeyColumns[0]));
        let rowIds = selectedRows.map(row => row[columnIndex]);
        let formattedFilters = {};
        formattedFilters['clauses'] = [columnName + '∈' + rowIds.length];
        let filterArgs = [];
        for (let rowId of rowIds) {
            filterArgs.push(rowId);
        }
        formattedFilters['args'] = filterArgs;
        params.append('filters', JSON.stringify(formattedFilters));
        
    } else {
        //in case of composite primary key without rowid, we can select and delete only one row at a time
        let row = selectedRows[0];
        let formattedFilters = {};
        let filtersClauses = [];
        let filterArgs = [];
        for (let column of compositeKeyColumns) {
            filtersClauses.push(column.name + "=");
            filterArgs.push(row[tableSchema.columns.indexOf(column)]);
        }
        formattedFilters['clauses'] = filtersClauses;
        formattedFilters['args'] = filterArgs;
        params.append('filters', JSON.stringify(formattedFilters));
    }

    fetch("/api/db/delete", {
            method: 'DELETE',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded'
            },
            body: params
        }
    )
        .then(response => response.json())
        .then(data => {
            console.log(data);
            fetchTableData();
        }
    );
}

function renderTableData(data) {
    let container = document.getElementById('table-data-container');
    let tableElement = document.getElementById('table-data');
    let headerElement = document.getElementById('table-header');
    let columnWidths = null;
    if (!headerElement.hasChildNodes()) {
        for (let column of tableSchema.columns) {
            let columnElement = document.createElement('th');
            let columnNameElement = document.createElement('div');
            columnNameElement.textContent = column.name;
            columnNameElement.className = 'column-name';
            columnNameElement.addEventListener('click', () => {
                if (sort && sort.column === column.name) {
                    sort.order = sort.order === 'asc' ? 'desc' : 'asc';
                } else {
                    if (sort) {
                        //remove the sort indicator from the previous column
                        sort.element.textContent = sort.column;
                    }
                    sort = { column: column.name, order: 'asc', element: columnNameElement };
                }
                columnNameElement.textContent = column.name + (sort.order === 'asc' ? ' ▲' : ' ▼');
                currentPage = 1;
                fetchTableData();
            });
            columnElement.appendChild(columnNameElement);
            let columnFilterElement = document.createElement('div');
            columnFilterElement.className = 'column-filter';
            let filterInput = document.createElement('input');
            filterInput.type = 'search';
            filterInput.placeholder = 'Filter';
            filterInput.addEventListener('input', debounce(() => {
                if (filterInput.value === '') {
                    delete filters[column.name];
                } else {
                    filters[column.name] = filterInput.value;
                }
                currentPage = 1;
                fetchTableData();
            }), 1000);
            columnFilterElement.appendChild(filterInput);
            columnElement.appendChild(columnFilterElement);
            headerElement.appendChild(columnElement);
        }
    } else {
        columnWidths = Array.from(headerElement.children).map(column => column.offsetWidth);
    }

    let tableBody = document.getElementById('table-body');
    tableBody.innerHTML = '';
    for (let row of data) {
        let rowElement = document.createElement('tr');
        rowElement.className = 'db-row';
        let columnIndex = 0;
        for (let column of row) {
            if (tableSchema.hasRowId && columnIndex === 0) {
                columnIndex++;
                continue;
            }
            let columnElement = document.createElement('td');
            columnElement.style.minWidth = columnWidths ? columnWidths[columnIndex] + 'px' : 'auto';
            let escapedHtml;
            let schemaColumnIndex = tableSchema.hasRowId ? columnIndex - 1 : columnIndex;
            let internalType = tableSchema.columns[schemaColumnIndex].hints ? tableSchema.columns[schemaColumnIndex].hints.internalType : 'TEXT';
            if (column === null) {
                escapedHtml = '<span style="color: #7f8;">NULL</span>';
            } else if (internalType === 'BOOLEAN') {
                escapedHtml = column ? '&#10004;' : '&#10008;';
            } else if (internalType === 'UNIX_TIMESTAMP') {
                let date = new Date(column * 1000);
                escapedHtml = date.toLocaleString();
            } else if (internalType === 'BLOB') {
                escapedHtml = '<span style="color: #00f;">BLOB</span>';
            } else {
                escapedHtml = column.toString().replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
            }
            columnElement.innerHTML = escapedHtml;
            columnElement.title = column;
            rowElement.appendChild(columnElement);
            columnIndex++;
        }
        rowElement.addEventListener('click', (event) => {
            //allow multiple selection with ctrl key only if the table has a rowid or simple one column primary key
            let compositeKeyColumns = tableSchema.columns.filter(column => column.primaryKey).length > 1;
            if (event.ctrlKey && (tableSchema.hasRowId || !compositeKeyColumns)) {
                if (selectedRows.includes(row)) {
                    selectedRows = selectedRows.filter(selectedRow => selectedRow !== row);
                    rowElement.classList.remove('selected-row');
                } else {
                    selectedRows.push(row);
                    rowElement.classList.add('selected-row');
                }
                updateButtonStates();
            } else {
                selectedRows = [row];
                let selectedRowsElements = document.getElementsByClassName('selected-row');
                for (let i = selectedRowsElements.length - 1; i >= 0; i--) {
                    selectedRowsElements[i].classList.remove('selected-row');
                }
                rowElement.classList.add('selected-row');
                updateButtonStates();
            }
        });
        rowElement.addEventListener('dblclick', () => {
            showRowEditorDialog(row);
        });
        tableBody.appendChild(rowElement);
    }

    document.getElementById('loader').style.visibility = 'hidden';
}

function updateButtonStates() {
    if (selectedRows.length > 0) {
        deleteRowsButton.disabled = false;
        if (selectedRows.length === 1) {
            editRowButton.disabled = false;
        } else {
            editRowButton.disabled = true;
        }
    } else {
        deleteRowsButton.disabled = true;
        editRowButton.disabled = true;
    }
}

function renderPager() {
    let pageInfo = document.getElementById('pageInfo');
    pageInfo.textContent = `Showing ${Math.min(rowCount, (currentPage - 1) * rowsPerPage + 1)} to ${Math.min(rowCount, currentPage * rowsPerPage)} of ${rowCount} rows`;

    let firstPage = document.getElementById('firstPage');
    let lastPage = document.getElementById('lastPage');
    if (currentPage === 1) {
        firstPage.classList.add('disabled-link');
    } else {
        firstPage.classList.remove('disabled-link');
    }
    if (currentPage * rowsPerPage >= rowCount) {
        lastPage.classList.add('disabled-link');
    } else {
        lastPage.classList.remove('disabled-link');
    }

    let prevPage = document.getElementById('prevPage');
    let nextPage = document.getElementById('nextPage');
    if (currentPage === 1) {
        prevPage.classList.add('disabled-link');
    } else {
        prevPage.classList.remove('disabled-link');
    }
    if (currentPage * rowsPerPage >= rowCount) {
        nextPage.classList.add('disabled-link');
    } else {
        nextPage.classList.remove('disabled-link');
    }

    let currentPageElement = document.getElementById('currentPage');
    currentPageElement.textContent = currentPage;
}

function showRowEditorDialog(row, forNewRow = false) {
    let dialog = document.getElementById('editRowModal');
    dialog.style.display = 'block';
    let dialogContent = document.getElementById('modal-content');
    dialogContent.innerHTML = '';

    let title = document.createElement('h2');
    title.textContent = forNewRow ? 'Add new row' : 'Edit row';
    dialogContent.appendChild(title);

    let form = document.createElement('form');
    form.id = 'row-editor-form';
    dialogContent.appendChild(form);

    let editedRow = {};
    let columnIndex = 0;
    for (let column of tableSchema.columns) {
        if (tableSchema.hasRowId && columnIndex === 0) {
            editedRow["rowid"] = {};
            editedRow["rowid"]["value"] = row[columnIndex];
            editedRow["rowid"]["useDefault"] = true;
            columnIndex++;
        }
        editedRow[column.name] = {};

        let value;
        if (!forNewRow && (row[columnIndex] !== null) && (row[columnIndex] !== undefined)) {
            value = row[columnIndex];
        } else if (column.hints.useNull) {
            value = null;
            editedRow[column.name]["useNull"] = true;
        } else if (column.hints.useDefault) {
            value = column.defaultValue;
            editedRow[column.name]["useDefault"] = true;
        } else {
            value = forNewRow ? column.defaultValue : row[columnIndex];
        }

        if (value === null) {
            if (column.hints.internalType === 'INTEGER' || column.hints.internalType === 'REAL') {
                value = 0;
            } else {
                value = '';
            }
        }
        editedRow[column.name]["value"] = value;

        let label = document.createElement('label');
        label.innerHTML = (column.primaryKey ? '&#128273; ' : '') + column.name + ':' + column.type;
        form.appendChild(label);
        let inputContainer = document.createElement('div');
        inputContainer.className = 'input-container';

        prepareColumnInput(column, inputContainer, editedRow, forNewRow);
        
        form.appendChild(inputContainer);
        columnIndex++;
    }

    let saveButton = document.createElement('button');
    saveButton.textContent = forNewRow ? 'Add' : 'Save';
    saveButton.addEventListener('click', (e) => {
        e.preventDefault();

        let formData = new FormData(form);
        let valuesToUpdate = {};
        let columnIndex = 0;
        for (let column of tableSchema.columns) {
            if (tableSchema.hasRowId && columnIndex === 0) {
                columnIndex++;
            }
            if (editedRow[column.name].useNull) {
                valuesToUpdate[column.name] = null;
            } else if (editedRow[column.name].useDefault) {
                //don't update the column
            } else if (column.hints.internalType === 'BOOLEAN') {
                valuesToUpdate[column.name] = formData.get(column.name) === 'on' ? 1 : 0;
            } else if (column.hints.internalType === 'UNIX_TIMESTAMP') {
                let date = new Date(formData.get(column.name));
                valuesToUpdate[column.name] = date.getTime() / 1000;
            } else if (column.hints.internalType === 'BLOB') {
                let value = editedRow[column.name].value;
                if (value === null) {
                    valuesToUpdate[column.name] = null;
                } else if (value.constructor.name === "Object") {
                    if (value.changed && value.value && value.value.constructor.name === "ArrayBuffer") {
                        var binary = '';
                        var bytes = new Uint8Array( value.value );
                        var len = bytes.byteLength;
                        for (var i = 0; i < len; i++) {
                            binary += String.fromCharCode( bytes[ i ] );
                        }
                        valuesToUpdate[column.name] = {type: 'blob', value: btoa(binary)};
                    } else {
                        //don't update the column
                    }
                } else {
                    valuesToUpdate[column.name] = value;
                }
            } else {
                valuesToUpdate[column.name] = formData.get(column.name);
            }
            columnIndex++;
        }

        if (forNewRow) {
            addRow(tableName, valuesToUpdate);
        } else {        
            let compositeKeyColumns = tableSchema.columns.filter(column => column.primaryKey).length > 1;
            let formattedFilters = {};
            if (tableSchema.hasRowId || !compositeKeyColumns) {
                let columnName = tableSchema.hasRowId ? 'rowid' : tableSchema.columns.find(column => column.primaryKey).name;
                let columnIndex = tableSchema.hasRowId ? 0 : (tableSchema.columns.indexOf(tableSchema.columns.find(column => column.primaryKey)));
                let rowId = row[columnIndex];
                formattedFilters['clauses'] = [columnName + "="];
                formattedFilters['args'] = [rowId];
                delete valuesToUpdate[columnName];
            } else {
                let filtersClauses = [];
                let filterArgs = [];
                for (let column of compositeKeyColumns) {
                    filtersClauses.push(column.name + "=");
                    filterArgs.push(row[tableSchema.columns.indexOf(column)]);
                    delete valuesToUpdate[column.name];
                }
                formattedFilters['clauses'] = filtersClauses;
                formattedFilters['args'] = filterArgs;
            }

            saveRow(tableName, valuesToUpdate, formattedFilters);
        }
    });

    form.appendChild(saveButton);

    let closeButton = document.createElement('button');
    closeButton.textContent = 'Cancel';
    closeButton.addEventListener('click', (e) => {
        e.preventDefault();
        dialog.style.display = 'none';
    });
    form.appendChild(closeButton);
}

function prepareColumnInput(column, container, editedRow, forNewRow) {
    if (container.hasChildNodes()) {
        container.innerHTML = '';
    }

    let inputElement;
    if (editedRow[column.name].useNull) {
        inputElement = document.createElement('input');
        inputElement.className = 'input-field';
        inputElement.type = 'text';
        inputElement.name = column.name;
        inputElement.placeholder = "[NULL]";
        inputElement.disabled = true;
    } else if (editedRow[column.name].useDefault) {
        inputElement = document.createElement('input');
        inputElement.className = 'input-field';
        inputElement.type = 'text';
        inputElement.name = column.name;
        inputElement.placeholder = column.hints.defaultValue ? column.hints.defaultValue : 
            (forNewRow ? "[DEFAULT]" : "[DON'T UPDATE]");
        inputElement.disabled = true;
    } else if (column.hints.internalType === 'MULTILINE_TEXT') {
        inputElement = document.createElement('textarea');
        inputElement.className = 'input-field';
        inputElement.name = column.name;
        inputElement.placeholder = column.name;
        inputElement.value = editedRow[column.name].value;
    } else if (column.hints.internalType === 'INTEGER') {
        inputElement = document.createElement('input');
        inputElement.className = 'input-field';
        inputElement.type = 'number';
        inputElement.name = column.name;
        inputElement.placeholder = column.name;
        inputElement.value = editedRow[column.name].value;
    } else if (column.hints.internalType === 'REAL') {
        inputElement = document.createElement('input');
        inputElement.className = 'input-field';
        inputElement.type = 'number';
        inputElement.step = 'any';
        inputElement.name = column.name;
        inputElement.placeholder = column.name;
        inputElement.value = editedRow[column.name].value;
    } else if (column.hints.internalType === 'BOOLEAN') {
        inputElement = document.createElement('input');
        inputElement.className = 'input-field';
        inputElement.type = 'checkbox';
        inputElement.name = column.name;
        inputElement.checked = editedRow[column.name].value;
    } else if (column.hints.internalType === 'DATE') {
        inputElement = document.createElement('input');
        inputElement.className = 'input-field';
        inputElement.type = 'date';
        inputElement.name = column.name;
        inputElement.placeholder = column.name;
        inputElement.value = editedRow[column.name].value;
    } else if (column.hints.internalType === 'TIME') {
        inputElement = document.createElement('input');
        inputElement.className = 'input-field';
        inputElement.type = 'time';
        inputElement.step = '1';
        inputElement.name = column.name;
        inputElement.placeholder = column.name;
        inputElement.value = editedRow[column.name].value;
    } else if (column.hints.internalType === 'DATETIME') {
        inputElement = document.createElement('input');
        inputElement.className = 'input-field';
        inputElement.type = 'datetime-local';
        inputElement.name = column.name;
        inputElement.placeholder = column.name;
        inputElement.value = editedRow[column.name].value;
    } else if (column.hints.internalType === 'UNIX_TIMESTAMP') {
        inputElement = document.createElement('input');
        inputElement.className = 'input-field';
        inputElement.type = 'datetime-local';
        inputElement.name = column.name;
        inputElement.placeholder = column.name;
        let date = new Date(editedRow[column.name].value * 1000);
        inputElement.value = date.toISOString().slice(0, 16);
    } else if (column.hints.internalType === 'BLOB') {
        let valueElement = document.createElement('div');
        let currentValue = 'Current value: ';
        if (editedRow[column.name].value === null) {
            currentValue += 'NULL';
        } else if (editedRow[column.name].value.constructor.name === "Object") {
            if (tableSchema.hasRowId && !editedRow[column.name].value.changed) {
                let filter = {};
                filter['clauses'] = ['rowid='];
                filter['args'] = [editedRow["rowid"].value];
                let url = "/api/db/cell-data?table=" + tableName + "&column=" + column.name + "&filters=" + encodeURIComponent(JSON.stringify(filter));
                currentValue += "<a href='" + url + "' target='_blank' download='" + column.name + "-" + editedRow["rowid"].value + ".bin'>Download</a>";
            } else {
                currentValue += 'BLOB';
            }
        } else {
            currentValue += 'UNKNOWN';
        }
        valueElement.innerHTML = currentValue;
        container.appendChild(valueElement);
        inputElement = document.createElement('input');
        inputElement.className = 'input-field';
        inputElement.type = 'file';
        inputElement.name = column.name;
        inputElement.placeholder = column.name;
        inputElement.onchange = (e) => {
            let file = e.target.files[0];
            let reader = new FileReader();
            reader.onload = (e) => {
                let value = {
                    type: 'blob',
                    changed: true,
                    value: e.target.result
                }
                editedRow[column.name].value = value;
                valueElement.innerHTML = 'Current value: BLOB';
            };
            reader.readAsArrayBuffer(file);
        };
    } else {
        inputElement = document.createElement('input');
        inputElement.className = 'input-field';
        inputElement.type = 'text';
        inputElement.name = column.name;
        inputElement.placeholder = column.name;
        inputElement.value = editedRow[column.name].value;
    }
    container.appendChild(inputElement);

    let inputMenuButton = document.createElement('div');
    inputMenuButton.classList = ['input-menu-button', 'context-menu-anchor'].join(' ');
    inputMenuButton.innerHTML = '&#x22EE;';
    inputMenuButton.addEventListener('click', (e) => {
        let menu = document.getElementById('input-menu');
        let setNullOption = document.querySelector('#input-menu #mo-set-null');
        let setDefaultOption = document.querySelector('#input-menu #mo-set-default');
        setNullOption.onclick = () => {
            if (editedRow[column.name].useNull) {
                editedRow[column.name].useNull = false;
            } else {
                editedRow[column.name].useNull = true;
                editedRow[column.name].useDefault = false;
            }
            prepareColumnInput(column, container, editedRow, forNewRow);
        };
        setDefaultOption.onclick = () => {
            if (editedRow[column.name].useDefault) {
                editedRow[column.name].useDefault = false;
            } else {
                editedRow[column.name].useDefault = true;
                editedRow[column.name].useNull = false;
            }
            prepareColumnInput(column, container, editedRow, forNewRow);
        };

        let setTypeSingleLineTextOption = document.querySelector('#input-menu #mo-single-line-text');
        let setTypeMultiLineTextOption = document.querySelector('#input-menu #mo-multi-line-text');
        let setTypeIntegerOption = document.querySelector('#input-menu #mo-int');
        let setTypeRealOption = document.querySelector('#input-menu #mo-float');
        let setTypeBooleanOption = document.querySelector('#input-menu #mo-boolean');
        let setTypeDateStringOption = document.querySelector('#input-menu #mo-date-str');
        let setTypeTimeStringOption = document.querySelector('#input-menu #mo-time-str');
        let setTypeDateTimeStringOption = document.querySelector('#input-menu #mo-date-time-str');
        let setTypeUnixTimestampOption = document.querySelector('#input-menu #mo-unix-timestamp');
        let setTypeBlobOption = document.querySelector('#input-menu #mo-blob');

        setTypeSingleLineTextOption.onclick = () => {
            column.hints.internalType = 'TEXT';
            prepareColumnInput(column, container, editedRow, forNewRow);
        };
        setTypeMultiLineTextOption.onclick = () => {
            column.hints.internalType = 'MULTILINE_TEXT';
            prepareColumnInput(column, container, editedRow, forNewRow);
        };
        setTypeIntegerOption.onclick = () => {
            column.hints.internalType = 'INTEGER';
            prepareColumnInput(column, container, editedRow, forNewRow);
        };
        setTypeRealOption.onclick = () => {
            column.hints.internalType = 'REAL';
            prepareColumnInput(column, container, editedRow, forNewRow);
        };
        setTypeBooleanOption.onclick = () => {
            column.hints.internalType = 'BOOLEAN';
            prepareColumnInput(column, container, editedRow, forNewRow);
        };
        setTypeDateStringOption.onclick = () => {
            column.hints.internalType = 'DATE';
            prepareColumnInput(column, container, editedRow, forNewRow);
        };
        setTypeTimeStringOption.onclick = () => {
            column.hints.internalType = 'TIME';
            prepareColumnInput(column, container, editedRow, forNewRow);
        };
        setTypeDateTimeStringOption.onclick = () => {
            column.hints.internalType = 'DATETIME';
            prepareColumnInput(column, container, editedRow, forNewRow);
        };
        setTypeUnixTimestampOption.onclick = () => {
            column.hints.internalType = 'UNIX_TIMESTAMP';
            prepareColumnInput(column, container, editedRow, forNewRow);
        };
        setTypeBlobOption.onclick = () => {
            column.hints.internalType = 'BLOB';
            prepareColumnInput(column, container, editedRow, forNewRow);
        };


        setNullOption.style.display = column.notNull ? 'none' : 'block';
        setDefaultOption.innerHTML = forNewRow ? 'Set default' : 'Don\'t update';
        if (editedRow[column.name].useNull) {
            setNullOption.classList.add('menu-option-mark');
        } else {
            setNullOption.classList.remove('menu-option-mark');
        }
        if (editedRow[column.name].useDefault) {
            setDefaultOption.classList.add('menu-option-mark');
        } else {
            setDefaultOption.classList.remove('menu-option-mark');
        }

        if (column.hints.internalType === 'TEXT') {
            setTypeSingleLineTextOption.classList.add('menu-option-circle-mark');
        } else {
            setTypeSingleLineTextOption.classList.remove('menu-option-circle-mark');
        }
        if (column.hints.internalType === 'MULTILINE_TEXT') {
            setTypeMultiLineTextOption.classList.add('menu-option-circle-mark');
        } else {
            setTypeMultiLineTextOption.classList.remove('menu-option-circle-mark');
        }
        if (column.hints.internalType === 'INTEGER') {
            setTypeIntegerOption.classList.add('menu-option-circle-mark');
        } else {
            setTypeIntegerOption.classList.remove('menu-option-circle-mark');
        }
        if (column.hints.internalType === 'REAL') {
            setTypeRealOption.classList.add('menu-option-circle-mark');
        } else {
            setTypeRealOption.classList.remove('menu-option-circle-mark');
        }
        if (column.hints.internalType === 'BOOLEAN') {
            setTypeBooleanOption.classList.add('menu-option-circle-mark');
        } else {
            setTypeBooleanOption.classList.remove('menu-option-circle-mark');
        }
        if (column.hints.internalType === 'DATE') {
            setTypeDateStringOption.classList.add('menu-option-circle-mark');
        } else {
            setTypeDateStringOption.classList.remove('menu-option-circle-mark');
        }
        if (column.hints.internalType === 'TIME') {
            setTypeTimeStringOption.classList.add('menu-option-circle-mark');
        } else {
            setTypeTimeStringOption.classList.remove('menu-option-circle-mark');
        }
        if (column.hints.internalType === 'DATETIME') {
            setTypeDateTimeStringOption.classList.add('menu-option-circle-mark');
        } else {
            setTypeDateTimeStringOption.classList.remove('menu-option-circle-mark');
        }
        if (column.hints.internalType === 'UNIX_TIMESTAMP') {
            setTypeUnixTimestampOption.classList.add('menu-option-circle-mark');
        } else {
            setTypeUnixTimestampOption.classList.remove('menu-option-circle-mark');
        }
        if (column.hints.internalType === 'BLOB') {
            setTypeBlobOption.classList.add('menu-option-circle-mark');
        } else {
            setTypeBlobOption.classList.remove('menu-option-circle-mark');
        }


        displayContextMenuWithAnchor(inputMenuButton, menu);
        e.stopPropagation();
    });
    container.appendChild(inputMenuButton);
}

function saveRow(tableName, valuesToUpdate, formattedFilters) {
    let params = new URLSearchParams();
    params.append('table', tableName);
    params.append('filters', JSON.stringify(formattedFilters));
    params.append('values', JSON.stringify(valuesToUpdate));

    fetch("/api/db/update", {
            method: 'PUT',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded'
            },
            body: params
        }
    )
        .then(async response => {
            if (!response.ok) {
                throw new Error(await response.text());
            } else {
                return response.json();
            }
        })
        .then(data => {
            console.log(data);
            document.getElementById('editRowModal').style.display = 'none';
            fetchTableData();
        })
        .catch(error => {
            alert('An error occurred while updating the row:\n' + error);
        });
}

function addRow(tableName, valuesToInsert) {
    let params = new URLSearchParams();
    params.append('table', tableName);
    params.append('values', JSON.stringify(valuesToInsert));

    fetch("/api/db/insert", {
            method: 'POST',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded'
            },
            body: params
        }
    )
        .then(async response => {
            if (!response.ok) {
                throw new Error(await response.text());
            } else {
                return response.json();
            }
        })
        .then(data => {
            console.log(data);
            document.getElementById('editRowModal').style.display = 'none';
            fetchTableData();            
        })
        .catch(error => {
            alert('An error occurred while adding the row:\n' + error);
        });
}