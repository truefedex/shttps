let columnCounter = 0;

function onPageLoad() {
    // Initialize with one column for new table creation
    addColumn();
}

function addColumn() {
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

function showConstraintsPopup(columnId) {
    const columnEntry = document.getElementById(`column-${columnId}`);
    const popup = document.createElement('div');
    popup.className = 'constraints-popup';
    popup.innerHTML = `
        <div class="constraints-content">
            <h3>Column Constraints</h3>
            <div class="constraints-list">
                <label class="checkbox-label">
                    <input type="checkbox" class="constraint-checkbox" data-constraint="PRIMARY KEY" 
                           onchange="handlePrimaryKeyChange(this, ${columnId})"
                           ${columnEntry.dataset.primaryKey === 'true' ? 'checked' : ''}>
                    Primary Key
                </label>
                <label class="checkbox-label">
                    <input type="checkbox" class="constraint-checkbox" data-constraint="AUTOINCREMENT" 
                           onchange="handleAutoincrementChange(this, ${columnId})"
                           ${columnEntry.dataset.autoincrement === 'true' ? 'checked' : ''}>
                    Auto Increment
                </label>
                <label class="checkbox-label">
                    <input type="checkbox" class="constraint-checkbox" data-constraint="NOT NULL" 
                           ${columnEntry.dataset.notNull === 'true' ? 'checked' : ''}>
                    Not Null
                </label>
                <label class="checkbox-label">
                    <input type="checkbox" class="constraint-checkbox" data-constraint="UNIQUE" 
                           ${columnEntry.dataset.unique === 'true' ? 'checked' : ''}>
                    Unique
                </label>
            </div>
            <button class="action-button" onclick="closeConstraintsPopup(this)">Close</button>
        </div>
    `;

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

function handlePrimaryKeyChange(checkbox, columnId) {
    const columnEntry = document.getElementById(`column-${columnId}`);
    if (checkbox.checked) {
        columnEntry.dataset.primaryKey = 'true';
        
        // If there are multiple primary keys, uncheck all autoincrement flags
        const primaryKeyColumns = Array.from(document.querySelectorAll('.column-entry'))
            .filter(entry => entry.dataset.primaryKey === 'true');
            
        if (primaryKeyColumns.length > 1) {
            document.querySelectorAll('.column-entry').forEach(entry => {
                entry.dataset.autoincrement = 'false';
                // Also uncheck the autoincrement checkbox in the popup if it's open
                const popup = entry.querySelector('.constraints-popup');
                if (popup) {
                    const autoincrementCheckbox = popup.querySelector('[data-constraint="AUTOINCREMENT"]');
                    if (autoincrementCheckbox) {
                        autoincrementCheckbox.checked = false;
                    }
                }
            });
        }
    } else {
        columnEntry.dataset.primaryKey = 'false';
        // Uncheck autoincrement when primary key is unchecked
        columnEntry.dataset.autoincrement = 'false';
        // Also uncheck the autoincrement checkbox in the popup if it's open
        const popup = columnEntry.querySelector('.constraints-popup');
        if (popup) {
            const autoincrementCheckbox = popup.querySelector('[data-constraint="AUTOINCREMENT"]');
            if (autoincrementCheckbox) {
                autoincrementCheckbox.checked = false;
            }
        }
    }
}

function handleAutoincrementChange(checkbox, columnId) {
    const columnEntry = document.getElementById(`column-${columnId}`);
    const primaryKeyCheckbox = checkbox.closest('.constraints-list')
        .querySelector('.constraint-checkbox[data-constraint="PRIMARY KEY"]');
    
    if (checkbox.checked) {
        // Uncheck all other primary keys and autoincrement flags
        document.querySelectorAll('.column-entry').forEach(entry => {
            if (entry.id !== `column-${columnId}`) {
                entry.dataset.primaryKey = 'false';
                entry.dataset.autoincrement = 'false';
            }
        });
        
        // Set this column as primary key and autoincrement
        primaryKeyCheckbox.checked = true;
        columnEntry.dataset.primaryKey = 'true';
        columnEntry.dataset.autoincrement = 'true';
    } else {
        columnEntry.dataset.autoincrement = 'false';
    }
}

function removeColumn(id) {
    if (document.getElementById('columnList').children.length > 1) {
        document.getElementById(`column-${id}`).remove();
    }
}

function generateSQL() {
    const tableName = document.getElementById('tableName').value.trim();
    if (!tableName) {
        alert('Please enter a table name');
        return null;
    }

    const columns = [];
    const primaryKeyColumns = [];
    let autoincrementColumn = null;
    const columnEntries = document.querySelectorAll('.column-entry');
    
    for (const entry of columnEntries) {
        const [nameInput, typeSelector, defaultInput] = entry.querySelectorAll('.form-input');
        const name = nameInput.value.trim();
        
        if (!name) {
            alert('All columns must have a name');
            return null;
        }

        // Get the type value
        let type;
        const typeElement = typeSelector;
        if (typeElement.tagName === 'INPUT') {
            type = typeElement.value.trim();
            if (!type) {
                alert('Please enter a column type');
                return null;
            }
        } else {
            type = typeElement.value;
        }

        let columnDef = `"${name}" ${type}`;
        
        // Add non-key constraints
        const constraints = [];
        if (entry.dataset.notNull === 'true') {
            constraints.push('NOT NULL');
        }
        if (entry.dataset.unique === 'true') {
            constraints.push('UNIQUE');
        }
        
        if (constraints.length > 0) {
            columnDef += ` ${constraints.join(' ')}`;
        }

        // Add default value if specified
        const defaultValue = defaultInput.value.trim();
        if (defaultValue) {
            // Quote the default value if it's a string
            const typeUpper = type.toUpperCase();
            if (typeUpper.includes('TEXT') || typeUpper.includes('CHAR') || typeUpper === 'DATE' || typeUpper === 'DATETIME' || typeUpper === 'TIME') {
                columnDef += ` DEFAULT '${defaultValue}'`;
            } else {
                columnDef += ` DEFAULT ${defaultValue}`;
            }
        }

        columns.push(columnDef);

        // Track primary key and autoincrement columns separately
        if (entry.dataset.primaryKey === 'true') {
            primaryKeyColumns.push(name);
            if (entry.dataset.autoincrement === 'true') {
                autoincrementColumn = name;
            }
        }
    }

    if (columns.length === 0) {
        alert('Please add at least one column');
        return null;
    }

    // Add primary key constraint at the end
    if (primaryKeyColumns.length > 0) {
        let pkConstraint;
        if (autoincrementColumn && primaryKeyColumns.length === 1) {
            pkConstraint = `PRIMARY KEY("${primaryKeyColumns[0]}" AUTOINCREMENT)`;
        } else {
            pkConstraint = `PRIMARY KEY("${primaryKeyColumns.join('","')}")`;
        }
        columns.push(pkConstraint);
    }

    return `CREATE TABLE "${tableName}" (\n    ${columns.join(',\n    ')}\n);`;
}

function showSQLPreview() {
    const sql = generateSQL();
    if (!sql) return;

    const popup = document.createElement('div');
    popup.className = 'sql-preview-popup';
    popup.innerHTML = `
        <div class="sql-preview-content">
            <h3>Generated SQL</h3>
            <pre class="sql-preview-text">${sql}</pre>
            <div class="sql-preview-actions">
                <button class="action-button" onclick="copySQL()">Copy SQL</button>
                <button class="action-button" onclick="openInSQLEditor()">Open in SQL Editor</button>
                <button class="action-button secondary" onclick="closeSQLPreview(this)">Close</button>
            </div>
        </div>
    `;

    // Add overlay
    const overlay = document.createElement('div');
    overlay.className = 'popup-overlay';
    overlay.onclick = () => closeSQLPreview(popup);

    document.body.appendChild(overlay);
    document.body.appendChild(popup);
}

function closeSQLPreview(element) {
    const popup = element.closest('.sql-preview-popup');
    const overlay = document.querySelector('.popup-overlay');
    if (popup) {
        popup.remove();
        overlay.remove();
    }
}

function copySQL() {
    const sqlText = document.querySelector('.sql-preview-text');
    if (sqlText) {
        navigator.clipboard.writeText(sqlText.textContent)
            .then(() => alert('SQL copied to clipboard'))
            .catch(err => alert('Failed to copy SQL: ' + err));
    }
}

function openInSQLEditor() {
    const sqlText = document.querySelector('.sql-preview-text');
    if (sqlText) {
        localStorage.setItem('sql-editor', sqlText.textContent);
        window.open('sql-editor.html', '_blank');
    }
}

function createTable() {
    const sql = generateSQL();
    if (!sql) return;
    
    // Show loader
    document.getElementById('loader').style.display = 'block';

    // Send request to create table
    fetch('/api/db/query?' + new URLSearchParams({
        'include-names': true,
        'limit': 100
    }).toString(), {
        method: 'POST',
        headers: {
            'Content-Type': 'text/plain'
        },
        body: sql
    })
    .then(async response => {
        if (response.ok) {
            window.location.href = 'index.html';
        } else {
            const err = await response.text();
            alert('Error creating table: ' + err);
        }
    })
    .catch(error => {
        alert('Error creating table: ' + error);
    })
    .finally(() => {
        document.getElementById('loader').style.display = 'none';
    });
}

// Initialize the page
onPageLoad(); 