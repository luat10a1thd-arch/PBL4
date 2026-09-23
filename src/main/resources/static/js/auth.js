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

function escapeHtml(text) {
    if (!text) return '';
    return text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

function switchTab(tab) {
    const loginForm = document.getElementById('login-form');
    const registerForm = document.getElementById('register-form');
    const loginBtn = document.getElementById('tab-login-btn');
    const regBtn = document.getElementById('tab-register-btn');

    if (tab === 'login') {
        loginForm.classList.remove('hidden');
        registerForm.classList.add('hidden');
        loginBtn.classList.add('active');
        regBtn.classList.remove('active');
    } else {
        loginForm.classList.add('hidden');
        registerForm.classList.remove('hidden');
        loginBtn.classList.remove('active');
        regBtn.classList.add('active');
    }
}

// Gọi API Đăng nhập thực tế (ĐÃ BỔ SUNG VALIDATE EMAIL @pbl4.com)
async function handleLogin(event) {
    event.preventDefault();
    const username = document.getElementById('login-username').value.trim();
    const password = document.getElementById('login-password').value;

    // 1. Kiểm tra định dạng tài khoản phải có đuôi @pbl4.com
    if (!username.endsWith('@pbl4.com')) {
        showToast('Tài khoản phải có định dạng @pbl4.com!', 'error');
        return;
    }

    try {
        const response = await fetch('/api/auth/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: `username=${encodeURIComponent(username)}&password=${encodeURIComponent(password)}`
        });

        const data = await response.json();
        if (data.success) {
            // Lưu username và session token vào LocalStorage
            localStorage.setItem('currentUser', data.username);
            localStorage.setItem('authToken', data.token); // Đã thêm token bảo mật
            window.location.href = '/main';
        } else {
            showToast(data.message || 'Sai tài khoản hoặc mật khẩu!', 'error');
        }
    } catch (err) {
        showToast('Lỗi kết nối tới Server!', 'error');
    }
}

// Gọi API Đăng ký thực tế (ĐÃ BỔ SUNG VALIDATE ĐUÔI EMAIL & MẬT KHẨU MẠNH)
async function handleRegister(event) {
    event.preventDefault();
    const username = document.getElementById('reg-username').value.trim();
    const password = document.getElementById('reg-password').value;
    const confirm = document.getElementById('reg-confirm').value;

    // 1. Kiểm tra đuôi email phải là @pbl4.com
    if (!username.endsWith('@pbl4.com')) {
        showToast('Tên đăng nhập phải có đuôi @pbl4.com!', 'error');
        return;
    }

    // 2. Kiểm tra độ dài mật khẩu (>= 8 và <= 32)
    if (password.length < 8 || password.length > 32) {
        showToast('Mật khẩu phải từ 8 đến 32 ký tự!', 'error');
        return;
    }

    // 3. Kiểm tra mật khẩu phải có cả chữ cái, chữ số và ký tự đặc biệt
    const hasLetter = /[a-zA-Z]/.test(password);
    const hasDigit = /[0-9]/.test(password);
    const hasSpecial = /[!@#$%^&*()_+\-=\[\]{};':"\\|,.<>\/?]/.test(password);

    if (!hasLetter || !hasDigit || !hasSpecial) {
        showToast('Mật khẩu phải chứa ít nhất 1 chữ cái, 1 chữ số và 1 ký tự đặc biệt (!@#$%^&*)!', 'error');
        return;
    }

    // 4. Kiểm tra xác nhận mật khẩu
    if (password !== confirm) {
        showToast('Mật khẩu xác nhận không khớp!', 'error');
        return;
    }

    try {
        const response = await fetch('/api/auth/register', {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
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
        showToast('Lỗi kết nối tới Server!', 'error');
    }
}