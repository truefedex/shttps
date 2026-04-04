function simpleHash(str) {
    // FNV-1a hash
    let h = 0x811c9dc5;
    for (let i = 0; i < str.length; i++) {
        h ^= str.charCodeAt(i);
        h += (h << 1) + (h << 4) + (h << 7) + (h << 8) + (h << 24);
    }
    return h >>> 0; // Convert to unsigned 32-bit integer
}

// Tab switching
function switchTab(tabName) {
    const registerAllowed = isRegistrationAllowed();
    if (tabName === 'register' && !registerAllowed) {
        return; // do nothing if registration is not allowed
    }
    // Hide all tabs
    document.querySelectorAll('.tab-content').forEach(tab => {
        tab.classList.remove('active');
    });
    document.querySelectorAll('.tab-button').forEach(button => {
        button.classList.remove('active');
    });
    
    // Show selected tab
    document.getElementById(tabName + 'Tab').classList.add('active');
    event.target.classList.add('active');
    
    // Load captcha when switching to register tab
    if (tabName === 'register') {
        refreshCaptcha();
    }
}

// Captcha functionality
function refreshCaptcha() {
    const captchaImage = document.getElementById('captchaImage');
    const registerButton = document.querySelector('#registerForm button[type="submit"]');
    
    // Disable register button while loading captcha
    registerButton.disabled = true;
    registerButton.textContent = 'Loading...';
    
    captchaImage.src = '/api/captcha?t=' + Date.now(); // Add timestamp to prevent caching
    
    // Re-enable button after image loads
    captchaImage.onload = function() {
        registerButton.disabled = false;
        registerButton.textContent = 'Register';
    };
    
    // Handle captcha loading errors
    captchaImage.onerror = function() {
        registerButton.disabled = false;
        registerButton.textContent = 'Register';
        showRateLimitMessage('registerErrorMessage', 'Failed to load captcha. Please try again later.');
    };
}

// Password visibility toggle for login form
function setupPasswordToggle(inputId, toggleId, iconId) {
    const passwordInput = document.getElementById(inputId);
    const togglePassword = document.getElementById(toggleId);
    let eyeIcon = document.getElementById(iconId);
    let visible = false;
    
    togglePassword.addEventListener('click', function() {
        visible = !visible;
        passwordInput.type = visible ? 'text' : 'password';
        // Change icon
        if (visible) {
            eyeIcon.outerHTML = `<svg id="${iconId}" xmlns="http://www.w3.org/2000/svg" width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="#bbb" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7-10-7-10-7z"/><line x1="1" y1="1" x2="23" y2="23"/></svg>`;
        } else {
            eyeIcon.outerHTML = `<svg id="${iconId}" xmlns="http://www.w3.org/2000/svg" width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="#bbb" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7-10-7-10-7z"/></svg>`;
        }
        // Re-select the new icon after outerHTML replacement
        eyeIcon = document.getElementById(iconId);
    });
}

// Show error message
function showErrorMessage(elementId, message) {
    const errorElement = document.getElementById(elementId);
    errorElement.textContent = message;
    errorElement.style.display = 'block';
}

// Show rate limit message
function showRateLimitMessage(elementId, message) {
    const errorElement = document.getElementById(elementId);
    errorElement.textContent = message;
    errorElement.className = 'rate-limit-message';
    errorElement.style.display = 'block';
}

// Hide messages
function hideMessages() {
    document.querySelectorAll('.error-message, .rate-limit-message').forEach(msg => {
        msg.style.display = 'none';
        msg.className = 'error-message'; // Reset class
    });
}

// Check if response indicates rate limiting
function isRateLimited(response) {
    return response.status === 429 || response.status === 503;
}

// Handle rate limited response
async function handleRateLimitedResponse(response, errorElementId) {
    const text = await response.text();
    let message = 'Too many requests. Please try again later.';
    
    try {
        const json = JSON.parse(text);
        if (json.error) {
            message = json.error;
        } else if (json.message) {
            message = json.message;
        }
    } catch (e) {
        // Use default message if JSON parsing fails
    }
    
    showRateLimitMessage(errorElementId, message);
}

async function handleLogin(event) {
    event.preventDefault();
    
    const form = event.target;
    const errorMessage = document.getElementById('errorMessage');
    hideMessages();
    
    const formData = new FormData(form);
    const username = formData.get('username');
    const password = formData.get('password');
    
    // Hash the password using the simple hash function
    const hashedPassword = simpleHash(password).toString(16);
    
    const data = new URLSearchParams();
    data.append('username', username);
    data.append('password', hashedPassword);
    
    try {
        const response = await fetch('/api/user/login', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded',
            },
            body: data.toString()
        });
        
        if (response.ok) {
            // Redirect to root on success
            window.location.href = '/';
        } else if (isRateLimited(response)) {
            await handleRateLimitedResponse(response, 'errorMessage');
        } else {
            // Show error message on failure
            showErrorMessage('errorMessage', 'Invalid username or password');
        }
    } catch (error) {
        showErrorMessage('errorMessage', 'An error occurred. Please try again.');
    }
}

async function handleRegister(event) {
    event.preventDefault();
    
    const form = event.target;
    const errorMessage = document.getElementById('registerErrorMessage');
    const successMessage = document.getElementById('registerSuccessMessage');
    const registerButton = form.querySelector('button[type="submit"]');
    
    hideMessages();
    
    const formData = new FormData(form);
    const identity = formData.get('identity');
    const password = formData.get('password');
    const captchaCode = formData.get('captcha_code');
    
    // Disable button during request
    registerButton.disabled = true;
    registerButton.textContent = 'Registering...';
    
    // Hash the password using the simple hash function
    const hashedPassword = simpleHash(password).toString(16);
    
    const data = new URLSearchParams();
    data.append('identity', identity);
    data.append('password', hashedPassword);
    data.append('captcha_code', captchaCode);
    
    try {
        const response = await fetch('/api/user/register', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded',
            },
            body: data.toString()
        });
        
        const result = await response.json();
        
        if (response.ok && result.success) {
            // Show success message
            successMessage.style.display = 'block';
            
            // Auto-login after short delay
            setTimeout(async () => {
                try {
                    const loginData = new URLSearchParams();
                    loginData.append('username', identity);
                    loginData.append('password', hashedPassword);
                    
                    const loginResponse = await fetch('/api/user/login', {
                        method: 'POST',
                        headers: {
                            'Content-Type': 'application/x-www-form-urlencoded',
                        },
                        body: loginData.toString()
                    });
                    
                    if (loginResponse.ok) {
                        // Redirect to root on success
                        window.location.href = '/';
                    } else if (isRateLimited(loginResponse)) {
                        await handleRateLimitedResponse(loginResponse, 'registerErrorMessage');
                        successMessage.style.display = 'none';
                    } else {
                        // Show error if auto-login fails
                        showErrorMessage('registerErrorMessage', 'Registration successful, but auto-login failed. Please login manually.');
                        successMessage.style.display = 'none';
                    }
                } catch (loginError) {
                    showErrorMessage('registerErrorMessage', 'Registration successful, but auto-login failed. Please login manually.');
                    successMessage.style.display = 'none';
                }
            }, 2000); // 2 second delay
        } else if (isRateLimited(response)) {
            await handleRateLimitedResponse(response, 'registerErrorMessage');
            // Refresh captcha on rate limit
            refreshCaptcha();
        } else {
            // Show error message on failure
            showErrorMessage('registerErrorMessage', result.error || 'Registration failed. Please try again.');
            // Refresh captcha on error
            refreshCaptcha();
        }
    } catch (error) {
        showErrorMessage('registerErrorMessage', 'An error occurred. Please try again.');
        // Refresh captcha on error
        refreshCaptcha();
    } finally {
        // Re-enable button
        registerButton.disabled = false;
        registerButton.textContent = 'Register';
    }
}

// Initialize password toggles
document.addEventListener('DOMContentLoaded', function() {
    setupPasswordToggle('password', 'togglePassword', 'eyeIcon');
    setupPasswordToggle('regPassword', 'toggleRegPassword', 'regEyeIcon');
    applyRegistrationVisibility();
});

function getCookie(name) {
    const value = `; ${document.cookie}`;
    const parts = value.split(`; ${name}=`);
    if (parts.length === 2) return parts.pop().split(';').shift();
    return null;
}

function isRegistrationAllowed() {
    return getCookie('registration_allowed') === '1';
}

function applyRegistrationVisibility() {
    const allowed = isRegistrationAllowed();
    const tabs = document.querySelector('.form-tabs');
    const registerTab = document.getElementById('registerTab');
    const registerButton = document.querySelector('.form-tabs .tab-button:nth-child(2)');

    if (!allowed) {
        if (tabs) tabs.style.display = 'none';
        if (registerTab) registerTab.style.display = 'none';
        // Ensure login tab is active
        const loginTab = document.getElementById('loginTab');
        if (loginTab) loginTab.classList.add('active');
        document.querySelectorAll('.tab-button').forEach(btn => btn.classList.remove('active'));
        const firstBtn = document.querySelector('.form-tabs .tab-button:first-child');
        if (firstBtn) firstBtn.classList.add('active');
    } else {
        if (tabs) tabs.style.display = '';
        if (registerTab) registerTab.style.display = '';
    }
}
