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
        let showDataElement = document.createElement('div');
        showDataElement.className = 'db-header-button';
        showDataElement.innerHTML = '🔎 DATA';
        showDataElement.onclick = () => {
            window.open(`/shttps-static-public/db-browser/table-data.html?table=${table.name}`, '_blank');
        };
        headerElement.appendChild(showDataElement);
        tableElement.appendChild(headerElement);
        let columnsElement = document.createElement('div');
        columnsElement.className = 'db-columns';
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