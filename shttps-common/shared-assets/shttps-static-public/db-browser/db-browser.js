let databaseSchema = null;
const SHTTPS_TABLES = ['user', 'user_role', 'shttps_db_access_rule', 'shttps_fs_access_rule'];
let shttpsTablesVisible = false;

const EYE_ICON_SVG = '<svg xmlns="http://www.w3.org/2000/svg" height="16px" viewBox="0 -960 960 960" width="16px" fill="#e3e3e3"><path d="M480-320q75 0 127.5-52.5T660-500q0-75-52.5-127.5T480-680q-75 0-127.5 52.5T300-500q0 75 52.5 127.5T480-320Zm0-72q-45 0-76.5-31.5T372-500q0-45 31.5-76.5T480-608q45 0 76.5 31.5T588-500q0 45-31.5 76.5T480-392Zm0 192q-146 0-266-81.5T40-500q54-137 174-218.5T480-800q146 0 266 81.5T920-500q-54 137-174 218.5T480-200Zm0-300Zm0 220q113 0 207.5-59.5T832-500q-50-101-144.5-160.5T480-720q-113 0-207.5 59.5T128-500q50 101 144.5 160.5T480-280Z"/></svg>';

const CROSSED_EYE_ICON_SVG = '<svg xmlns="http://www.w3.org/2000/svg" height="16px" viewBox="0 -960 960 960" width="16px" fill="#e3e3e3"><path d="m644-428-58-58q9-47-27-88t-93-32l-58-58q17-8 34.5-12t37.5-4q75 0 127.5 52.5T660-500q0 20-4 37.5T644-428Zm128 126-58-56q38-29 67.5-63.5T832-500q-50-101-143.5-160.5T480-720q-29 0-57 4t-55 12l-62-62q41-17 84-25.5t90-8.5q151 0 269 83.5T920-500q-23 59-60.5 109.5T772-302Zm20 246L624-222q-35 11-70.5 16.5T480-200q-151 0-269-83.5T40-500q21-53 53-98.5t73-81.5L56-792l56-56 736 736-56 56ZM222-624q-29 26-53 57t-41 67q50 101 143.5 160.5T480-280q20 0 39-2.5t39-5.5l-36-38q-11 3-21 4.5t-21 1.5q-75 0-127.5-52.5T300-500q0-11 1.5-21t4.5-21l-84-82Zm319 93Zm-151 75Z"/></svg>';

function onPageLoad() {
    //fetch the database schema /api/db/schema
    fetch('/api/db/schema')
        .then(async response => {
            if (response.ok) {
                return response.json();
            } else {
                const errorText = await response.text();
                throw new Error(errorText);
            }
        })
        .then(data => {
            databaseSchema = data;
            renderDatabaseSchema();
            // Initialize the icon to crossed eye (hidden state)
            const toggleIcon = document.getElementById('shttps-toggle-icon');
            if (toggleIcon) {
                toggleIcon.innerHTML = CROSSED_EYE_ICON_SVG;
            }
        })
        .catch(error => {
            console.error('Error fetching database schema:', error);
            alert(`Error loading database schema: ${error.message}`);
            document.getElementById('loader').style.display = 'none';
        });
}

function renderDatabaseSchema() {
    // render the database schema
    let container = document.getElementById('tables-container');
    for (let table of databaseSchema) {
        let tableElement = document.createElement('div');
        tableElement.className = 'db-table';
        
        // Check if this is an SHTTPS table and mark it
        const isSHTTPSTable = SHTTPS_TABLES.includes(table.name);
        if (isSHTTPSTable) {
            tableElement.classList.add('shttps-table');
            // Hide by default
            tableElement.style.display = 'none';
        }
        
        let headerElement = document.createElement('div');
        headerElement.className = 'db-header';
        headerElement.innerHTML = `<h2>${table.name}</h2>`;
        
        let editStructureButton = document.createElement('div');
        editStructureButton.className = 'db-header-button';
        editStructureButton.innerHTML = '⚙️';
        editStructureButton.title = 'Edit table structure';
        editStructureButton.onclick = (e) => {
            e.stopPropagation(); // Prevent table click event
            window.open(`/shttps-static-public/db-browser/table-editor.html?table=${table.name}`, '_blank');
        };
        headerElement.appendChild(editStructureButton);
        
        let duplicateButton = document.createElement('div');
        duplicateButton.className = 'db-header-button';
        duplicateButton.innerHTML = '🗐';
        duplicateButton.title = 'Duplicate table';
        duplicateButton.onclick = (e) => {
            e.stopPropagation(); // Prevent table click event
            duplicateTable(table.name);
        };
        headerElement.appendChild(duplicateButton);
        
        let deleteButton = document.createElement('div');
        deleteButton.className = 'db-header-button';
        deleteButton.innerHTML = '🗑️';
        deleteButton.title = 'Delete table';
        deleteButton.onclick = (e) => {
            e.stopPropagation(); // Prevent table click event
            deleteTable(table.name);
        };
        headerElement.appendChild(deleteButton);
        
        tableElement.appendChild(headerElement);
        let columnsElement = document.createElement('div');
        columnsElement.className = 'db-columns';
        columnsElement.onclick = () => {
            window.open(`/shttps-static-public/db-browser/table-data.html?table=${table.name}`, '_blank');
        };
        for (let column of table.columns) {
            let columnElement = document.createElement('div');
            columnElement.className = 'db-column';
            let keyIndexMark = column.primaryKey ? '&#128273;' : (column.index ? '&#x26A1;' : '');
            columnElement.innerHTML = `<div>${keyIndexMark}</div><div>${column.name}</div><div>${column.type}</div>`;
            columnsElement.appendChild(columnElement);
        }
        tableElement.appendChild(columnsElement);
        container.appendChild(tableElement);
    }

    // show the tables container
    container.style.display = 'grid';
    document.getElementById('loader').style.display = 'none';
}

function onExecuteSQLClick() {
    window.open('sql-editor.html', '_blank');
}

function duplicateTable(tableName) {
    if (confirm(`Are you sure you want to duplicate the table "${tableName}"?\n\nNote: This method will copy the data but not the constraints (primary keys, foreign keys, etc.).`)) {
        document.getElementById('loader').style.display = 'block';
        
        // Create SQL to duplicate the table
        const newTableName = `${tableName}_copy`;
        const sql = `CREATE TABLE "${newTableName}" AS SELECT * FROM "${tableName}";`;
        
        // Send request to duplicate table using the query endpoint
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
                alert(`Table "${tableName}" duplicated successfully as "${newTableName}"!`);
                // Refresh the page to show the new table
                window.location.reload();
            } else {
                const err = await response.text();
                throw new Error(err);
            }
        })
        .catch(error => {
            console.error('Error:', error);
            alert(`Error duplicating table: ${error.message}`);
            document.getElementById('loader').style.display = 'none';
        });
    }
}

function deleteTable(tableName) {
    if (confirm(`Are you sure you want to delete the table "${tableName}"? This action cannot be undone.`)) {
        document.getElementById('loader').style.display = 'block';
        
        // Create SQL to drop the table
        const sql = `DROP TABLE "${tableName}";`;
        
        // Send request to delete table using the query endpoint
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
                alert(`Table "${tableName}" deleted successfully!`);
                // Refresh the page to update the table list
                window.location.reload();
            } else {
                const err = await response.text();
                throw new Error(err);
            }
        })
        .catch(error => {
            console.error('Error:', error);
            alert(`Error deleting table: ${error.message}`);
            document.getElementById('loader').style.display = 'none';
        });
    }
}

function toggleSHTTPSTables() {
    shttpsTablesVisible = !shttpsTablesVisible;
    const shttpsTables = document.querySelectorAll('.shttps-table');
    const toggleIcon = document.getElementById('shttps-toggle-icon');
    
    shttpsTables.forEach(table => {
        table.style.display = shttpsTablesVisible ? '' : 'none';
    });
    
    // Update icon: crossed eye when hidden, regular eye when visible
    toggleIcon.innerHTML = shttpsTablesVisible ? EYE_ICON_SVG : CROSSED_EYE_ICON_SVG;
}

// Menu/context menu logic for header menu button
function onMenuClick(e) {
    let menuButton = document.getElementById("menu-button");
    let mainMenu = document.getElementById("main-menu");
    let mmBackToFiles = document.getElementById("mm-back-to-files");
    if (mmBackToFiles) {
        mmBackToFiles.onclick = function () {
            window.location.href = "/?forceContents=true";
        };
    }
    let rect = menuButton.getBoundingClientRect();
    displayContextMenuWithAnchorRect(rect, mainMenu);
    e.stopPropagation();
    e.preventDefault();
}