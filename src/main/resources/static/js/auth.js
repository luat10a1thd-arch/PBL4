// ============================================================
// Domain cố định của hệ thống - đổi ở đây nếu sau này muốn dùng domain khác
// ============================================================
const MAIL_DOMAIN = "@pbl4.com";

// ------------------------------------------------------------
// Kiểm tra phần tên trước @ chỉ chứa chữ, số, dấu chấm, gạch dưới,
// gạch ngang - không cho phép ký tự lạ (đặc biệt là dấu / \ ..)
// vì username sẽ được dùng làm TÊN THƯ MỤC lưu mail trên server,
// ký tự lạ có thể gây lỗi hoặc rủi ro bảo mật (path traversal).
// ------------------------------------------------------------
function isValidUsernameLocalPart(value) {
    return /^[a-zA-Z0-9._-]{3,32}$/.test(value);
}

// Chuyển phần tên người dùng gõ thành email đầy đủ dạng "ten@pbl4.com"
function toFullEmail(localPart) {
    return localPart.trim().toLowerCase() + MAIL_DOMAIN;
}

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

// Gọi API Đăng nhập thực tế (ĐÃ CẬP NHẬT LƯU AUTH TOKEN)
async function handleLogin(event) {
    event.preventDefault();
    const rawUsername = document.getElementById('login-username').value;
    const password = document.getElementById('login-password').value;

    if (!isValidUsernameLocalPart(rawUsername)) {
        showToast('Tên đăng nhập chỉ gồm chữ, số, dấu chấm, gạch dưới/ngang (3-32 ký tự)!', 'error');
        return;
    }
    const username = toFullEmail(rawUsername);

    try {
        const response = await fetch('/api/auth/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: `username=${encodeURIComponent(username)}&password=${encodeURIComponent(password)}`
        });

        const data = await response.json();
        if (data.success) {
            localStorage.setItem('currentUser', data.username);
            localStorage.setItem('authToken', data.token);
            window.location.href = '/main';
        } else {
            showToast(data.message || 'Sai tài khoản hoặc mật khẩu!', 'error');
        }
    } catch (err) {
        showToast('Lỗi kết nối tới Server!', 'error');
    }
}

// Gọi API Đăng ký thực tế
async function handleRegister(event) {
    event.preventDefault();
    const rawUsername = document.getElementById('reg-username').value;
    const password = document.getElementById('reg-password').value;
    const confirm = document.getElementById('reg-confirm').value;

    if (!isValidUsernameLocalPart(rawUsername)) {
        showToast('Tên đăng nhập chỉ gồm chữ, số, dấu chấm, gạch dưới/ngang (3-32 ký tự)!', 'error');
        return;
    }
    if (password !== confirm) {
        showToast('Mật khẩu xác nhận không khớp!', 'error');
        return;
    }
    const username = toFullEmail(rawUsername);

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