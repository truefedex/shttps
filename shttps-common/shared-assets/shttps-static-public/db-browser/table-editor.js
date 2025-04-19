let columnCounter = 0;
let originalTableStructure = null;

function onPageLoad() {
    // Get table name from URL
    const urlParams = new URLSearchParams(window.location.search);
    const tableName = urlParams.get('table');
    
    if (!tableName) {
        alert('No table specified');
        window.close();
        return;
    }

    // Set up the form
    const tableNameInput = document.getElementById('tableName');
    const originalTableNameInput = document.getElementById('originalTableName');
    const tableNameButtons = document.querySelector('.table-name-buttons');
    
    tableNameInput.value = tableName;
    originalTableNameInput.value = tableName;

    // Add event listeners for table name editing
    tableNameInput.addEventListener('input', function() {
        const newName = this.value.trim();
        const originalName = originalTableNameInput.value;
        if (newName !== originalName) {
            tableNameButtons.style.display = 'flex';
        } else {
            tableNameButtons.style.display = 'none';
        }
    });

    // Apply button handler
    document.querySelector('.apply-table-name').addEventListener('click', function() {
        const newName = tableNameInput.value.trim();
        const originalName = originalTableNameInput.value;
        
        if (!newName) {
            alert('Please enter a table name');
            return;
        }

        if (newName === originalName) {
            tableNameButtons.style.display = 'none';
            return;
        }

        // Show loader
        document.getElementById('loader').style.display = 'block';

        fetch('/api/db/query?' + new URLSearchParams({
            'include-names': true,
            'limit': 100
        }).toString(), {
            method: 'POST',
            headers: {
                'Content-Type': 'text/plain'
            },
            body: `ALTER TABLE "${originalName}" RENAME TO "${newName}";`
        })
        .then(async response => {
            if (!response.ok) {
                const err = await response.text();
                alert('Error renaming table: ' + err);
                // Revert the change in the UI
                tableNameInput.value = originalName;
            } else {
                // Update the original name
                originalTableNameInput.value = newName;
                // Hide the buttons
                tableNameButtons.style.display = 'none';
            }
        })
        .catch(error => {
            alert('Error renaming table: ' + error);
            // Revert the change in the UI
            tableNameInput.value = originalName;
        })
        .finally(() => {
            document.getElementById('loader').style.display = 'none';
        });
    });

    // Cancel button handler
    document.querySelector('.cancel-table-name').addEventListener('click', function() {
        tableNameInput.value = originalTableNameInput.value;
        tableNameButtons.style.display = 'none';
    });
    
    // Load table structure
    loadTableStructure(tableName);
}

function loadTableStructure(tableName) {
    // Show loader
    document.getElementById('loader').style.display = 'block';

    // Fetch table structure using schema endpoint
    fetch(`/api/db/schema?table=${encodeURIComponent(tableName)}`)
    .then(async response => {
        if (response.ok) {
            const data = await response.json();
            if (data.columns) {
                // Store the original table structure
                originalTableStructure = data.columns;
                
                // Clear existing columns
                document.getElementById('columnList').innerHTML = '';
                columnCounter = 0;

                // Add columns from the table structure
                data.columns.forEach(column => {
                    const columnEntry = document.createElement('div');
                    columnEntry.className = 'column-entry';
                    columnEntry.id = `column-${columnCounter}`;

                    // Set column constraints
                    columnEntry.dataset.primaryKey = column.primaryKey;
                    columnEntry.dataset.notNull = column.notNull;
                    columnEntry.dataset.unique = column.unique;
                    columnEntry.dataset.autoIncrement = column.autoIncrement;

                    const type = column.type.split('(')[0].toUpperCase();
                    const isCustomType = !['INTEGER', 'TEXT', 'REAL', 'BLOB', 'NUMERIC'].includes(type);

                    columnEntry.innerHTML = `
                        <input type="text" class="form-input" placeholder="Column name" required value="${column.name}">
                        <div class="type-selector">
                            ${isCustomType ? 
                                `<input type="text" class="form-input" placeholder="Column type" value="${column.type}" disabled>` :
                                `<select class="form-input" disabled>
                                    <option value="INTEGER">INTEGER</option>
                                    <option value="TEXT">TEXT</option>
                                    <option value="REAL">REAL</option>
                                    <option value="BLOB">BLOB</option>
                                    <option value="NUMERIC">NUMERIC</option>
                                    <option value="CUSTOM">Custom...</option>
                                </select>`
                            }
                        </div>
                        <input type="text" class="form-input" placeholder="Default value" value="${column.defaultValue || ''}" disabled>
                        <button class="settings-button" onclick="showConstraintsPopup(${columnCounter})" title="Column settings">⚙️</button>
                        <button class="remove-column" onclick="removeColumn(${columnCounter})">✕</button>
                    `;

                    // Set the correct type and add event listener if not custom type
                    if (!isCustomType) {
                        const typeSelect = columnEntry.querySelector('select');
                        typeSelect.value = type;
                        typeSelect.addEventListener('change', function() {
                            if (this.value === 'CUSTOM') {
                                const typeInput = document.createElement('input');
                                typeInput.type = 'text';
                                typeInput.className = 'form-input';
                                typeInput.placeholder = 'Column type';
                                this.parentNode.replaceChild(typeInput, this);
                            }
                        });
                    }

                    // Store original name for tracking changes
                    const nameInput = columnEntry.querySelector('input[type="text"]');
                    nameInput.dataset.originalName = column.name;

                    // Add event listener for column name changes
                    nameInput.addEventListener('change', function() {
                        const oldName = this.dataset.originalName;
                        const newName = this.value.trim();
                        const currentTableName = document.getElementById('tableName').value.trim();
                        const columnId = columnEntry.id.split('-')[1]; // Get the ID from the column entry
                        
                        if (oldName && oldName !== newName) {
                            renameColumn(currentTableName, oldName, newName, columnId);
                        }
                    });

                    document.getElementById('columnList').appendChild(columnEntry);
                    columnCounter++;
                });
            }
        } else {
            const err = await response.text();
            alert('Error loading table structure: ' + err);
        }
    })
    .catch(error => {
        alert('Error loading table structure: ' + error);
    })
    .finally(() => {
        document.getElementById('loader').style.display = 'none';
    });
}

function addColumn() {
    const tableName = document.getElementById('tableName').value.trim();
    if (!tableName) {
        alert('Please enter a table name');
        return;
    }

    const columnList = document.getElementById('columnList');
    const columnEntry = document.createElement('div');
    columnEntry.className = 'column-entry';
    columnEntry.id = `column-${columnCounter}`;

    columnEntry.innerHTML = `
        <input type="text" class="form-input" placeholder="Column name" required>
        <div class="type-selector">
            <select class="form-input">
                <option value="INTEGER">INTEGER</option>
                <option value="TEXT">TEXT</option>
                <option value="REAL">REAL</option>
                <option value="BLOB">BLOB</option>
                <option value="NUMERIC">NUMERIC</option>
                <option value="CUSTOM">Custom...</option>
            </select>
        </div>
        <input type="text" class="form-input" placeholder="Default value">
        <button class="settings-button" onclick="showConstraintsPopup(${columnCounter})" title="Column settings">⚙️</button>
        <button class="apply-column" onclick="applyColumn(${columnCounter})" title="Apply changes">✓</button>
        <button class="remove-column" onclick="removeColumn(${columnCounter})">✕</button>
    `;

    // Add event listener for type selection changes
    const typeSelect = columnEntry.querySelector('select');
    typeSelect.addEventListener('change', function() {
        if (this.value === 'CUSTOM') {
            const typeInput = document.createElement('input');
            typeInput.type = 'text';
            typeInput.className = 'form-input';
            typeInput.placeholder = 'Column type';
            this.parentNode.replaceChild(typeInput, this);
        }
    });

    columnList.appendChild(columnEntry);
    columnCounter++;
}

function removeColumn(id) {
    const columnEntry = document.getElementById(`column-${id}`);
    const [nameInput] = columnEntry.querySelectorAll('.form-input');
    const columnName = nameInput.value.trim();
    
    // Check if column has been applied (saved to database)
    const applyButton = columnEntry.querySelector('.apply-column');
    if (applyButton) {
        // Column hasn't been applied yet, just remove it from UI
        columnEntry.remove();
        return;
    }
    
    // Column has been applied, show confirmation and delete from database
    if (confirm(`Are you sure you want to delete column "${columnName}"? This action cannot be undone.`)) {
        const tableName = document.getElementById('tableName').value.trim();
        deleteColumn(tableName, columnName);
        columnEntry.remove();
    }
}

function renameColumn(tableName, oldName, newName, columnId) {
    // Show loader
    document.getElementById('loader').style.display = 'block';

    fetch('/api/db/query?' + new URLSearchParams({
        'include-names': true,
        'limit': 100
    }).toString(), {
        method: 'POST',
        headers: {
            'Content-Type': 'text/plain'
        },
        body: `ALTER TABLE "${tableName}" RENAME COLUMN "${oldName}" TO "${newName}";`
    })
    .then(async response => {
        if (!response.ok) {
            const err = await response.text();
            throw new Error(err);
        }
        // Update the original name in the dataset
        const columnEntry = document.querySelector(`#column-${columnId} input[type="text"]`);
        columnEntry.dataset.originalName = newName;
    })
    .catch(error => {
        alert('Error renaming column: ' + error);
        // Revert the change in the UI
        const columnEntry = document.querySelector(`#column-${columnId} input[type="text"]`);
        columnEntry.value = oldName;
    })
    .finally(() => {
        document.getElementById('loader').style.display = 'none';
    });
}

function applyColumn(id) {
    const tableName = document.getElementById('tableName').value.trim();
    if (!tableName) {
        alert('Please enter a table name');
        return;
    }

    const columnEntry = document.getElementById(`column-${id}`);
    const nameInput = columnEntry.querySelector('input[type="text"]');
    const columnName = nameInput.value.trim();
    
    if (!columnName) {
        alert('Please enter a column name');
        return;
    }

    // Show loader
    document.getElementById('loader').style.display = 'block';

    const typeSelector = columnEntry.querySelector('.type-selector');
    const defaultInput = columnEntry.querySelector('input[placeholder="Default value"]');
    
    // Get the type value
    let type;
    const typeElement = typeSelector.firstElementChild;
    if (typeElement.tagName === 'INPUT') {
        type = typeElement.value.trim();
        if (!type) {
            alert('Please enter a column type');
            document.getElementById('loader').style.display = 'none';
            return;
        }
    } else {
        type = typeElement.value;
    }

    const defaultValue = defaultInput.value.trim();
    const isNotNull = columnEntry.dataset.notNull === 'true';
    
    let sql = `ALTER TABLE "${tableName}" ADD COLUMN "${columnName}" ${type}`;
    if (defaultValue) {
        sql += ` DEFAULT ${defaultValue}`;
    }
    if (isNotNull) {
        sql += ` NOT NULL`;
    }

    fetch('/api/db/query?' + new URLSearchParams({
        'include-names': true,
        'limit': 100
    }).toString(), {
        method: 'POST',
        headers: {
            'Content-Type': 'text/plain'
        },
        body: sql + ';'
    })
    .then(async response => {
        if (!response.ok) {
            const err = await response.text();
            alert('Error adding column: ' + err);
            // Remove the column entry from UI if it fails
            columnEntry.remove();
        } else {
            // Store the original name for future rename operations
            nameInput.dataset.originalName = columnName;
            // Remove the apply button since the column is now added
            const applyButton = columnEntry.querySelector('.apply-column');
            applyButton.remove();
            // Disable the type selector and default value input since the column is now applied
            typeSelector.firstElementChild.disabled = true;
            defaultInput.disabled = true;
        }
    })
    .catch(error => {
        alert('Error adding column: ' + error);
        // Remove the column entry from UI if it fails
        columnEntry.remove();
    })
    .finally(() => {
        document.getElementById('loader').style.display = 'none';
    });
}

function deleteColumn(tableName, columnName) {
    // Show loader
    document.getElementById('loader').style.display = 'block';

    fetch('/api/db/query?' + new URLSearchParams({
        'include-names': true,
        'limit': 100
    }).toString(), {
        method: 'POST',
        headers: {
            'Content-Type': 'text/plain'
        },
        body: `ALTER TABLE "${tableName}" DROP COLUMN "${columnName}";`
    })
    .then(async response => {
        if (!response.ok) {
            const err = await response.text();
            alert('Error deleting column: ' + err);
        }
    })
    .catch(error => {
        alert('Error deleting column: ' + error);
    })
    .finally(() => {
        document.getElementById('loader').style.display = 'none';
    });
}

function showConstraintsPopup(columnId) {
    const columnEntry = document.getElementById(`column-${columnId}`);
    const isApplied = !columnEntry.querySelector('.apply-column');
    
    const popup = document.createElement('div');
    popup.className = 'constraints-popup';
    popup.innerHTML = `
        <div class="constraints-content">
            <h3>Column Constraints</h3>
            <div class="constraints-list">
                <label class="checkbox-label">
                    <input type="checkbox" class="constraint-checkbox" data-constraint="PRIMARY KEY" 
                           ${columnEntry.dataset.primaryKey === 'true' ? 'checked' : ''}
                           disabled>
                    Primary Key
                </label>
                <label class="checkbox-label">
                    <input type="checkbox" class="constraint-checkbox" data-constraint="AUTOINCREMENT" 
                           ${columnEntry.dataset.autoIncrement === 'true' ? 'checked' : ''}
                           disabled>
                    Auto Increment
                </label>
                <label class="checkbox-label">
                    <input type="checkbox" class="constraint-checkbox" data-constraint="NOT NULL" 
                           ${columnEntry.dataset.notNull === 'true' ? 'checked' : ''}
                           ${isApplied ? 'disabled' : ''}>
                    Not Null
                </label>
                <label class="checkbox-label">
                    <input type="checkbox" class="constraint-checkbox" data-constraint="UNIQUE" 
                           ${columnEntry.dataset.unique === 'true' ? 'checked' : ''}
                           disabled>
                    Unique
                </label>
            </div>
            <button class="action-button" onclick="closeConstraintsPopup(this)">Close</button>
        </div>
    `;

    // Add event listeners for checkboxes
    const checkboxes = popup.querySelectorAll('.constraint-checkbox');
    checkboxes.forEach(checkbox => {
        checkbox.addEventListener('change', function() {
            const constraint = this.dataset.constraint;
            if (constraint === 'NOT NULL') {
                columnEntry.dataset.notNull = this.checked;
            }
        });
    });

    // Add overlay
    const overlay = document.createElement('div');
    overlay.className = 'popup-overlay';
    overlay.onclick = () => closeConstraintsPopup(popup);

    document.body.appendChild(overlay);
    document.body.appendChild(popup);
}

function closeConstraintsPopup(element) {
    const popup = element.closest('.constraints-popup');
    const overlay = document.querySelector('.popup-overlay');
    if (popup) {
        popup.remove();
        overlay.remove();
    }
}

// Initialize the page
onPageLoad(); 