let databaseSchema = null;

function onPageLoad() {
    //fetch the database schema /api/db/schema
    fetch('/api/db/schema')
        .then(response => response.json())
        .then(data => {
            databaseSchema = data;
            renderDatabaseSchema();
        }
    );    
}

function renderDatabaseSchema() {
    // render the database schema
    let container = document.getElementById('tables-container');
    for (let table of databaseSchema) {
        let tableElement = document.createElement('div');
        tableElement.className = 'db-table';
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