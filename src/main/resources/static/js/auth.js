// Chuyển đổi qua lại giữa Tab Đăng nhập và Đăng ký
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

// Xử lý sự kiện Submit Form Đăng nhập (Tạm thời giả lập chuyển trang)
function handleLogin(event) {
    event.preventDefault();
    // Sau này sẽ gọi API kiểm tra mật khẩu ở đây
    window.location.href = 'main.html';
}

// Xử lý sự kiện Submit Form Đăng ký
function handleRegister(event) {
    event.preventDefault();
    alert('Đăng ký thành công! Hãy đăng nhập.');
    switchTab('login');
}