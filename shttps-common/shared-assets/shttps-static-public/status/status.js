let statusData = null;

function onPageLoad() {
    fetchStatusData();
}

function fetchStatusData() {
    let loader = document.getElementById('loader');
    loader.style.visibility = 'visible';
    
    fetch('/api/system/status')
        .then(async response => {
            if (response.ok) {
                return response.json();
            } else {
                const errorText = await response.text();
                throw new Error(errorText);
            }
        })
        .then(data => {
            statusData = data;
            renderStatusDashboard();
            loader.style.visibility = 'hidden';
        })
        .catch(error => {
            console.error('Error fetching status data:', error);
            alert(`Error loading status data: ${error.message}`);
            loader.style.visibility = 'hidden';
        });
}

function renderStatusDashboard() {
    let container = document.getElementById('status-container');
    container.innerHTML = '';
    
    // User Information Card
    if (statusData.user) {
        let userItems = [
            { label: 'Identity', value: statusData.user.identity },
            { label: 'Role', value: statusData.user.role != null ? statusData.user.role : 'not set' },
            { label: 'System Rights', value: statusData.user.system_rights },
            { label: 'Database Rights', value: statusData.user.db_rights },
            { label: 'Filesystem Rights', value: statusData.user.fs_rights }
        ];
        
        // Add storage progress bar if limit is set
        if (statusData.user.storage_limit_bytes != null && statusData.user.storage_limit_bytes > 0) {
            let storageUsed = statusData.user.storage_used_bytes || 0;
            let storageLimit = statusData.user.storage_limit_bytes;
            let storagePercent = storageUsed / storageLimit;
            userItems.push({
                label: 'Storage',
                value: formatBytes(storageUsed) + ' / ' + formatBytes(storageLimit),
                progress: storagePercent,
                type: 'progress'
            });
        } else {
            userItems.push({ label: 'Storage Used', value: formatBytes(statusData.user.storage_used_bytes) });
        }
        
        userItems.push({ label: 'Registered At', value: formatTimestamp(statusData.user.registered_at) });
        
        let userCard = createCard('User Information', userItems);
        container.appendChild(userCard);
    }
    
    // System Information Card
    if (statusData.system) {
        let systemItems = [
            { label: 'Server Name', value: statusData.system.server_name },
            { label: 'Server Version', value: statusData.system.server_version },
            { label: 'Uptime', value: formatUptime(statusData.system.server_uptime) },
            { label: 'OS Name', value: statusData.system.os_name },
            { label: 'OS Version', value: statusData.system.os_version },
            { label: 'Java Runtime Name', value: statusData.system.java_runtime_name },
            { label: 'Java VM Version', value: statusData.system.java_vm_version },
            { label: 'CPU Cores', value: statusData.system.cpu_cores },
            { label: 'CPU Architecture', value: statusData.system.cpu_arch }
        ];
        
        // Add RAM progress bar
        if (statusData.system.ram_total_bytes != null && statusData.system.ram_total_bytes > 0) {
            let ramUsed = statusData.system.ram_total_bytes - statusData.system.ram_free_bytes;
            let ramTotal = statusData.system.ram_total_bytes;
            let ramPercent = ramUsed / ramTotal;
            systemItems.push({
                label: 'JVM RAM',
                value: formatBytes(ramUsed) + ' / ' + formatBytes(ramTotal),
                progress: ramPercent,
                type: 'progress'
            });
        } else {
            systemItems.push({ label: 'Total JVM RAM', value: formatBytes(statusData.system.ram_total_bytes) });
            systemItems.push({ label: 'Free JVM RAM', value: formatBytes(statusData.system.ram_free_bytes) });
            systemItems.push({ label: 'Used JVM RAM', value: formatBytes(statusData.system.ram_total_bytes - statusData.system.ram_free_bytes) });
        }
        
        let systemCard = createCard('System Information', systemItems);
        container.appendChild(systemCard);
    }
    
    // Filesystem Information Card
    if (statusData.filesystem) {
        let fsItems = [];

        if (statusData.filesystem.storage_path != null) {
            fsItems.push({ label: 'Storage Path', value: statusData.filesystem.storage_path });
        }
        
        // Add filesystem progress bar
        if (statusData.filesystem.total_space_bytes != null && statusData.filesystem.total_space_bytes > 0) {
            let fsUsed = statusData.filesystem.total_space_bytes - statusData.filesystem.free_space_bytes;
            let fsTotal = statusData.filesystem.total_space_bytes;
            let fsPercent = fsUsed / fsTotal;
            fsItems.push({
                label: 'Storage',
                value: formatBytes(fsUsed) + ' / ' + formatBytes(fsTotal),
                progress: fsPercent,
                type: 'progress'
            });
        } else {
            fsItems.push({ label: 'Total Space', value: formatBytes(statusData.filesystem.total_space_bytes) });
            fsItems.push({ label: 'Free Space', value: formatBytes(statusData.filesystem.free_space_bytes) });
            fsItems.push({ label: 'Used Space', value: formatBytes(statusData.filesystem.total_space_bytes - statusData.filesystem.free_space_bytes) });
        }
        
        let fsCard = createCard('Filesystem Information', fsItems);
        container.appendChild(fsCard);
    }
    
    // Database Information Card
    if (statusData.database) {
        let dbItems = [];
        for (let key in statusData.database) {
            let value = statusData.database[key];
            if (typeof value === 'object') {
                value = JSON.stringify(value);
            }
            if (key === 'size') {
                value = formatBytes(value);
            }
            dbItems.push({ label: formatLabel(key), value: value });
        }
        let dbCard = createCard('Database Information', dbItems);
        container.appendChild(dbCard);
    }
}

function createCard(title, items) {
    let card = document.createElement('div');
    card.classList.add('status-card');
    
    let cardHeader = document.createElement('div');
    cardHeader.classList.add('status-card-header');
    cardHeader.textContent = title;
    card.appendChild(cardHeader);
    
    let cardBody = document.createElement('div');
    cardBody.classList.add('status-card-body');
    
    items.forEach(item => {
        if (item.type === 'progress') {
            // Create progress bar row
            let row = document.createElement('div');
            row.classList.add('status-row');
            row.classList.add('status-row-progress');
            
            let label = document.createElement('div');
            label.classList.add('status-label');
            label.textContent = item.label + ':';
            
            let valueContainer = document.createElement('div');
            valueContainer.classList.add('status-value');
            valueContainer.style.flexDirection = 'column';
            valueContainer.style.alignItems = 'flex-end';
            valueContainer.style.gap = '5px';
            
            let valueText = document.createElement('div');
            valueText.textContent = item.value;
            valueText.style.width = '100%';
            valueText.style.textAlign = 'right';
            
            let progressBarContainer = document.createElement('div');
            progressBarContainer.classList.add('progress-bar-container');
            
            let progressBar = document.createElement('div');
            progressBar.classList.add('progress-bar');
            let percent = Math.min(Math.max(item.progress * 100, 0), 100);
            progressBar.style.width = percent + '%';
            
            // Color based on usage percentage
            if (percent >= 90) {
                progressBar.classList.add('progress-bar-danger');
            } else if (percent >= 70) {
                progressBar.classList.add('progress-bar-warning');
            } else {
                progressBar.classList.add('progress-bar-normal');
            }
            
            progressBarContainer.appendChild(progressBar);
            valueContainer.appendChild(valueText);
            valueContainer.appendChild(progressBarContainer);
            
            row.appendChild(label);
            row.appendChild(valueContainer);
            cardBody.appendChild(row);
        } else {
            // Create regular row
            let row = document.createElement('div');
            row.classList.add('status-row');
            
            let label = document.createElement('div');
            label.classList.add('status-label');
            label.textContent = item.label + ':';
            
            let value = document.createElement('div');
            value.classList.add('status-value');
            value.textContent = item.value;
            
            row.appendChild(label);
            row.appendChild(value);
            cardBody.appendChild(row);
        }
    });
    
    card.appendChild(cardBody);
    return card;
}

function formatBytes(bytes) {
    if (bytes == null || bytes === undefined) return 'N/A';
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return Math.round((bytes / Math.pow(k, i)) * 100) / 100 + ' ' + sizes[i];
}

function formatUptime(milliseconds) {
    if (milliseconds == null || milliseconds === undefined) return 'N/A';
    const seconds = Math.floor(milliseconds / 1000);
    const minutes = Math.floor(seconds / 60);
    const hours = Math.floor(minutes / 60);
    const days = Math.floor(hours / 24);
    
    if (days > 0) {
        return `${days}d ${hours % 24}h ${minutes % 60}m`;
    } else if (hours > 0) {
        return `${hours}h ${minutes % 60}m ${seconds % 60}s`;
    } else if (minutes > 0) {
        return `${minutes}m ${seconds % 60}s`;
    } else {
        return `${seconds}s`;
    }
}

function formatTimestamp(timestamp) {
    if (timestamp == null || timestamp === undefined) return 'N/A';
    try {
        return new Date(timestamp).toLocaleString();
    } catch (e) {
        return timestamp;
    }
}

function formatPercentage(value) {
    if (value == null || value === undefined || isNaN(value)) return 'N/A';
    return (value * 100).toFixed(2) + '%';
}

function formatLabel(key) {
    return key.split('_').map(word => 
        word.charAt(0).toUpperCase() + word.slice(1)
    ).join(' ');
}

