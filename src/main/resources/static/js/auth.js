// ==========================================
// THƯ VIỆN TIỆN ÍCH (UTILITIES)
// ==========================================

// Hàm hiển thị Toast Notification chuyên nghiệp
function showToast(message, type = 'success') {
    const container = document.getElementById('toast-container');
    if (!container) return;

    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    
    const iconClass = type === 'success' ? 'fa-circle-check' : 'fa-circle-exclamation';
    
    toast.innerHTML = `
        <i class="fa-solid ${iconClass} toast-icon"></i>
        <span class="toast-message">${escapeHtml(message)}</span>
    `;

    container.appendChild(toast);

    setTimeout(() => toast.classList.add('show'), 10);

    setTimeout(() => {
        toast.classList.remove('show');
        setTimeout(() => toast.remove(), 300);
    }, 3000);
}

// Anti-XSS HTML Escape
function escapeHtml(text) {
    if (!text) return '';
    return text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

// Chuyển đổi giữa Form Đăng nhập và Form Đăng ký
function switchTab(tab) {
    const loginForm = document.getElementById('login-form');
    const registerForm = document.getElementById('register-form');
    const loginBtn = document.getElementById('tab-login-btn');
    const regBtn = document.getElementById('tab-register-btn');

    if (!loginForm || !registerForm) return;

    if (tab === 'login') {
        loginForm.classList.remove('hidden');
        registerForm.classList.add('hidden');
        if (loginBtn) loginBtn.classList.add('active');
        if (regBtn) regBtn.classList.remove('active');
    } else {
        loginForm.classList.add('hidden');
        registerForm.classList.remove('hidden');
        if (loginBtn) loginBtn.classList.remove('active');
        if (regBtn) regBtn.classList.add('active');
    }
}

// ==========================================
// XỬ LÝ ĐĂNG NHẬP (LOGIN)
// ==========================================
async function handleLogin(event) {
    event.preventDefault();
    const usernameInput = document.getElementById('login-username');
    const passwordInput = document.getElementById('login-password');

    if (!usernameInput || !passwordInput) return;

    const username = usernameInput.value.trim();
    const password = passwordInput.value;

    // 1. Kiểm tra định dạng tài khoản phải có đuôi @pbl4.com
    if (!username.toLowerCase().endsWith('@pbl4.com')) {
        showToast('Tài khoản phải có định dạng @pbl4.com!', 'error');
        return;
    }

    try {
        const response = await fetch('/api/auth/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8' },
            body: `username=${encodeURIComponent(username)}&password=${encodeURIComponent(password)}`
        });

        const data = await response.json();
        if (data.success) {
            // Lưu thông tin người dùng và Session Token vào LocalStorage
            localStorage.setItem('currentUser', data.username);
            localStorage.setItem('authToken', data.token);
            localStorage.setItem('userRole', data.role || 'USER');

            showToast('Đăng nhập thành công!', 'success');

            // Chuyển hướng giao diện tùy thuộc vào Role (ADMIN / USER)
            setTimeout(() => {
                if (data.role === 'ADMIN') {
                    window.location.href = '/admin.html';
                } else {
                    window.location.href = '/main.html';
                }
            }, 500);

        } else {
            showToast(data.message || 'Sai tài khoản hoặc mật khẩu!', 'error');
        }
    } catch (err) {
        console.error('Lỗi kết nối Login:', err);
        showToast('Lỗi kết nối tới Server!', 'error');
    }
}

// ==========================================
// XỬ LÝ ĐĂNG KÝ (REGISTER)
// ==========================================
async function handleRegister(event) {
    event.preventDefault();
    const usernameInput = document.getElementById('reg-username');
    const passwordInput = document.getElementById('reg-password');
    const confirmInput = document.getElementById('reg-confirm');

    if (!usernameInput || !passwordInput || !confirmInput) return;

    const username = usernameInput.value.trim();
    const password = passwordInput.value;
    const confirm = confirmInput.value;

    // 1. Kiểm tra đuôi email phải là @pbl4.com
    if (!username.toLowerCase().endsWith('@pbl4.com')) {
        showToast('Tên đăng nhập phải có đuôi @pbl4.com!', 'error');
        return;
    }

    // 2. Chặn đăng ký tài khoản admin@pbl4.com hoặc tài khoản bắt đầu bằng admin
    if (username.toLowerCase().startsWith('admin')) {
        showToast('Không được phép đăng ký tài khoản Admin!', 'error');
        return;
    }

    // 3. Kiểm tra độ dài mật khẩu (>= 8 và <= 32)
    if (password.length < 8 || password.length > 32) {
        showToast('Mật khẩu phải từ 8 đến 32 ký tự!', 'error');
        return;
    }

    // 4. Kiểm tra mật khẩu phải có cả chữ cái, chữ số và ký tự đặc biệt
    const hasLetter = /[a-zA-Z]/.test(password);
    const hasDigit = /[0-9]/.test(password);
    const hasSpecial = /[!@#$%^&*()_+\-=\[\]{};':"\\|,.<>\/?]/.test(password);

    if (!hasLetter || !hasDigit || !hasSpecial) {
        showToast('Mật khẩu phải chứa ít nhất 1 chữ cái, 1 chữ số và 1 ký tự đặc biệt (!@#$%^&*)!', 'error');
        return;
    }

    // 5. Kiểm tra xác nhận mật khẩu
    if (password !== confirm) {
        showToast('Mật khẩu xác nhận không khớp!', 'error');
        return;
    }

    try {
        const response = await fetch('/api/auth/register', {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8' },
            body: `username=${encodeURIComponent(username)}&password=${encodeURIComponent(password)}`
        });

        const data = await response.json();
        
        if (data.success) {
            showToast(data.message || 'Đăng ký tài khoản thành công!', 'success');
            document.getElementById('register-form').reset();
            switchTab('login');
        } else {
            showToast(data.message || 'Đăng ký thất bại!', 'error');
        }
    } catch (err) {
        console.error('Lỗi kết nối Register:', err);
        showToast('Lỗi kết nối tới Server!', 'error');
    }
}