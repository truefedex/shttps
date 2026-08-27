let statusData = null;

const POWER_SOURCE_LABELS = {
    'none': 'Not plugged in',
    'ac': 'AC',
    'usb': 'USB',
    'wireless': 'Wireless'
};

function onPageLoad() {
    setupMainMenu({ "mm-back-to-files": "/?forceContents=true" });
    fetchStatusData();
}

async function fetchStatusData() {
    let loader = document.getElementById('loader');
    loader.style.visibility = 'visible';
    try {
        statusData = await api('GET', '/api/system/status');
        renderStatusDashboard();
    } catch (error) {
        console.error('Error fetching status data:', error);
        alert(`Error loading status data: ${error.message}`);
    } finally {
        loader.style.visibility = 'hidden';
    }
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
    
    // Server, Device and Software cards - all three describe what the 'system' scope returned,
    // so they stand or fall together
    if (statusData.system) {
        let system = statusData.system;
        // the device scope is optional: a platform that cannot introspect its host omits it entirely
        let device = statusData.device || {};

        // --- Server: the SHTTPS process itself ---
        let serverItems = [
            { label: 'Name', value: system.server_name },
            { label: 'Version', value: system.server_version },
            { label: 'Uptime', value: formatUptime(system.server_uptime) }
        ];
        if (system.server_time != null) {
            serverItems.push({ label: 'Server Time', value: formatTimestamp(system.server_time) });
        }
        if (system.server_timezone != null) {
            serverItems.push({ label: 'Time Zone', value: system.server_timezone });
        }
        container.appendChild(createCard('Server', serverItems));

        // --- Device: the machine it runs on ---
        let deviceItems = [];
        if (device.device_name != null) {
            deviceItems.push({ label: 'Device Name', value: device.device_name });
        }
        if (device.manufacturer != null) {
            deviceItems.push({ label: 'Manufacturer', value: device.manufacturer });
        }
        if (device.model != null) {
            deviceItems.push({ label: 'Model', value: device.model });
        }
        // the system scope reports os.name, which says "Linux" on a phone
        deviceItems.push({ label: 'OS Name', value: device.os_name != null ? device.os_name : system.os_name });
        // on Android the os.version property is the kernel version, which is not what anyone
        // means by the OS version of a phone - os_release is the user facing one
        deviceItems.push({ label: 'OS Version', value: device.os_release != null ? device.os_release : system.os_version });
        if (device.api_level != null) {
            deviceItems.push({ label: 'API Level', value: device.api_level });
        }
        deviceItems.push({ label: 'CPU Cores', value: system.cpu_cores });
        deviceItems.push({ label: 'CPU Architecture', value: system.cpu_arch });

        // physical memory, which is a different thing from the JVM heap in the Software card
        if (device.ram_total_bytes != null && device.ram_total_bytes > 0) {
            let ramTotal = device.ram_total_bytes;
            if (device.ram_available_bytes != null) {
                let ramUsed = ramTotal - device.ram_available_bytes;
                deviceItems.push({
                    label: 'RAM',
                    value: formatBytes(ramUsed) + ' / ' + formatBytes(ramTotal),
                    // a usage bar: full is bad here, unlike the battery level
                    progress: ramUsed / ramTotal,
                    type: 'progress'
                });
            } else {
                deviceItems.push({ label: 'Total RAM', value: formatBytes(ramTotal) });
            }
        }
        if (device.system_uptime != null) {
            deviceItems.push({ label: 'System Uptime', value: formatUptime(device.system_uptime) });
        }
        container.appendChild(createCard('Device', deviceItems));

        // --- Software: the runtime this happens to sit on ---
        let softwareItems = [
            { label: 'Java Runtime Name', value: system.java_runtime_name },
            { label: 'Java VM Version', value: system.java_vm_version }
        ];
        if (system.ram_total_bytes != null && system.ram_total_bytes > 0) {
            let heapUsed = system.ram_total_bytes - system.ram_free_bytes;
            let heapTotal = system.ram_total_bytes;
            softwareItems.push({
                label: 'JVM RAM',
                value: formatBytes(heapUsed) + ' / ' + formatBytes(heapTotal),
                progress: heapUsed / heapTotal,
                type: 'progress'
            });
        } else {
            softwareItems.push({ label: 'Total JVM RAM', value: formatBytes(system.ram_total_bytes) });
            softwareItems.push({ label: 'Free JVM RAM', value: formatBytes(system.ram_free_bytes) });
            softwareItems.push({ label: 'Used JVM RAM', value: formatBytes(system.ram_total_bytes - system.ram_free_bytes) });
        }
        container.appendChild(createCard('Software', softwareItems));
    }
    
    // Filesystem Information Card
    if (statusData.filesystem) {
        let fsItems = [];

        if (statusData.filesystem.storage_path != null) {
            fsItems.push({ label: 'Storage Path', value: formatStoragePath(statusData.filesystem.storage_path) });
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

    // Battery Information Card
    if (statusData.battery) {
        let battery = statusData.battery;
        let batteryItems = [];

        // Every field is optional - a host reports only what its hardware can tell us,
        // and a row it cannot fill is left out rather than shown as zero
        if (battery.level_percent != null) {
            batteryItems.push({
                label: 'Level',
                value: battery.level_percent + '%',
                // createCard expects a 0..1 fraction, not a percentage
                progress: battery.level_percent / 100,
                goodWhenHigh: true,
                type: 'progress'
            });
        }
        if (battery.temperature_celsius != null) {
            batteryItems.push({ label: 'Temperature', value: Number(battery.temperature_celsius).toFixed(1) + ' °C' });
        }
        if (battery.status != null) {
            batteryItems.push({ label: 'Status', value: formatLabel(battery.status) });
        }
        if (battery.health != null) {
            batteryItems.push({ label: 'Health', value: formatLabel(battery.health) });
        }
        if (battery.capacity_mah != null) {
            batteryItems.push({ label: 'Capacity', value: battery.capacity_mah + ' mAh' });
        }
        if (battery.charge_counter_mah != null) {
            batteryItems.push({ label: 'Charge', value: battery.charge_counter_mah + ' mAh' });
        }
        if (battery.voltage_millivolts != null) {
            batteryItems.push({ label: 'Voltage', value: (battery.voltage_millivolts / 1000).toFixed(2) + ' V' });
        }
        if (battery.technology != null) {
            batteryItems.push({ label: 'Technology', value: battery.technology });
        }
        if (battery.power_source != null) {
            batteryItems.push({ label: 'Power Source', value: POWER_SOURCE_LABELS[battery.power_source] || formatLabel(battery.power_source) });
        }

        if (batteryItems.length > 0) {
            container.appendChild(createCard('Battery', batteryItems));
        }
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
            
            // Color based on the value. Usage bars are bad when they are full, a charge bar is
            // the other way round - but only the color is inverted, never the width
            if (item.goodWhenHigh) {
                if (percent <= 15) {
                    progressBar.classList.add('progress-bar-danger');
                } else if (percent <= 30) {
                    progressBar.classList.add('progress-bar-warning');
                } else {
                    progressBar.classList.add('progress-bar-good');
                }
            } else if (percent >= 90) {
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

// The root is reported as a URI. A local one reads better as the plain path it stands for,
// while Android's content:// roots are left exactly as they are - there is no path behind them.
function formatStoragePath(uri) {
    if (uri == null || !/^file:/i.test(uri)) return uri;
    let path = uri.replace(/^file:(\/\/)?/i, '');
    try {
        path = decodeURIComponent(path);
    } catch (e) {
        // a stray % that is not an escape - better the raw text than nothing
    }
    // 'file:/C:/dir' leaves a slash in front of the drive letter that is no part of the path
    if (/^\/[a-zA-Z]:/.test(path)) {
        path = path.substring(1);
    }
    return path;
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

const LABEL_ACRONYMS = {
    'sqlite': 'SQLite',
    'db': 'DB',
    'id': 'ID',
    'os': 'OS',
    'cpu': 'CPU',
    'ram': 'RAM',
    'url': 'URL',
    'uri': 'URI'
};

function formatLabel(key) {
    return String(key)
        // 'tablesCount' -> 'tables Count', so camelCase keys read like the snake_case ones
        .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
        .split(/[_\s]+/)
        .filter(word => word.length > 0)
        .map(word => LABEL_ACRONYMS[word.toLowerCase()] || word.charAt(0).toUpperCase() + word.slice(1))
        .join(' ');
}

