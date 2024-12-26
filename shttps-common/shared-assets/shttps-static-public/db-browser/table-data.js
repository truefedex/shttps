let tableName = new URL(document.location.toString()).searchParams.get('table');
let tableSchema = null;
let rowCount = 0;
let currentPage = 1;
let rowsPerPage = calculateRowsPerPage();
let autoReloadOnResizeTimer = null;
let filters = {};
let sort = null;
let selectedRows = [];

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

function fetchTableSchema(callback) {
    // fetch the table schema /api/db/schema?table=tableName
    fetch(`/api/db/schema?table=${tableName}`)
        .then(response => response.json())
        .then(data => {
            tableSchema = data;
            rowCount = tableSchema.rowCount;
            callback();
        }
    );
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
    // render the table data
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
            let escapedHtml = column ? column.toString().replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;') : '';
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
    form.addEventListener('submit', (event) => {
        event.preventDefault();
        if (forNewRow) {
            addRow();
        } else {
            saveRow();
        }
    });
    dialogContent.appendChild(form);

    let columnIndex = 0;
    for (let column of tableSchema.columns) {
        if (tableSchema.hasRowId && columnIndex === 0) {
            columnIndex++;
        }
        let label = document.createElement('label');
        label.innerHTML = (column.primaryKey ? '&#128273; ' : '') + column.name + ':' + column.type;
        form.appendChild(label);
        let input = document.createElement('input');
        input.type = 'text';
        input.name = column.name;
        input.placeholder = column.name;
        input.value = forNewRow ? '' : row[columnIndex];
        form.appendChild(input);
        columnIndex++;
    }

    let saveButton = document.createElement('button');
    saveButton.textContent = forNewRow ? 'Add' : 'Save';
    form.appendChild(saveButton);

    let closeButton = document.createElement('button');
    closeButton.textContent = 'Cancel';
    closeButton.addEventListener('click', (e) => {
        e.preventDefault();
        dialog.style.display = 'none';
    });
    form.appendChild(closeButton);
}

function saveRow() {
    let row = selectedRows[0];
    let form = document.getElementById('row-editor-form');
    let formData = new FormData(form);
    let valuesToUpdate = {};
    let columnIndex = 0;
    for (let column of tableSchema.columns) {
        if (tableSchema.hasRowId && columnIndex === 0) {
            columnIndex++;
        }
        valuesToUpdate[column.name] = formData.get(column.name);
        columnIndex++;
    }

    let params = new URLSearchParams();
    params.append('table', tableName);

    let compositeKeyColumns = tableSchema.columns.filter(column => column.primaryKey).length > 1;
    let formattedFilters = {};
    if (tableSchema.hasRowId || !compositeKeyColumns) {
        let columnName = tableSchema.hasRowId ? 'rowid' : tableSchema.columns.find(column => column.primaryKey).name;
        let columnIndex = tableSchema.hasRowId ? 0 : (tableSchema.columns.indexOf(tableSchema.columns.find(column => column.primaryKey)));
        let rowId = selectedRows[0][columnIndex];
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

function addRow() {
    let form = document.getElementById('row-editor-form');
    let formData = new FormData(form);
    let valuesToInsert = {};
    for (let column of tableSchema.columns) {
        valuesToInsert[column.name] = formData.get(column.name);
    }

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