// FILE: src/main/resources/web/js/auth.js
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
    return String(text)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function switchTab(tab) {
    const loginForm = document.getElementById('login-form');
    const registerForm = document.getElementById('register-form');
    const forgotForm = document.getElementById('forgot-form');
    const loginBtn = document.getElementById('tab-login-btn');
    const regBtn = document.getElementById('tab-register-btn');

    if (!loginForm || !registerForm) return;

    if (forgotForm) forgotForm.classList.add('hidden');

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


async function handleLogin(event) {
    event.preventDefault();
    const usernameInput = document.getElementById('login-username');
    const passwordInput = document.getElementById('login-password');

    if (!usernameInput || !passwordInput) return;

    const username = usernameInput.value.trim();
    const password = passwordInput.value;

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
            localStorage.setItem('currentUser', data.username);
            localStorage.setItem('authToken', data.token);
            localStorage.setItem('userRole', data.role || 'USER');

            showToast('Đăng nhập thành công!', 'success');

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

async function handleRegister(event) {
    event.preventDefault();
    const usernameInput = document.getElementById('reg-username');
    const passwordInput = document.getElementById('reg-password');
    const confirmInput = document.getElementById('reg-confirm');

    if (!usernameInput || !passwordInput || !confirmInput) return;

    const username = usernameInput.value.trim();
    const password = passwordInput.value;
    const confirm = confirmInput.value;

    if (!username.toLowerCase().endsWith('@pbl4.com')) {
        showToast('Tên đăng nhập phải có đuôi @pbl4.com!', 'error');
        return;
    }

    if (username.toLowerCase().startsWith('admin')) {
        showToast('Không được phép đăng ký tài khoản Admin!', 'error');
        return;
    }

    if (password.length < 8 || password.length > 32) {
        showToast('Mật khẩu phải từ 8 đến 32 ký tự!', 'error');
        return;
    }

    const hasLetter = /[a-zA-Z]/.test(password);
    const hasDigit = /[0-9]/.test(password);
    const hasSpecial = /[!@#$%^&*()_+\-=\[\]{};':"\\|,.<>\/?]/.test(password);

    if (!hasLetter || !hasDigit || !hasSpecial) {
        showToast('Mật khẩu phải chứa ít nhất 1 chữ cái, 1 chữ số và 1 ký tự đặc biệt (!@#$%^&*)!', 'error');
        return;
    }


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

// ============================================================================
// Dùng chung: kiểm tra mật khẩu, thanh độ mạnh, thông điệp nội tuyến
// ============================================================================

function formEncodeAuth(params) {
    return Object.keys(params || {})
        .filter(k => params[k] !== undefined && params[k] !== null)
        .map(k => encodeURIComponent(k) + '=' + encodeURIComponent(params[k]))
        .join('&');
}

// Trả về thông báo lỗi, hoặc '' nếu mật khẩu đạt quy tắc
function validatePasswordRules(pw) {
    if (pw.length < 8 || pw.length > 32) return 'Mật khẩu phải từ 8 đến 32 ký tự!';
    if (!/[a-zA-Z]/.test(pw) || !/[0-9]/.test(pw) || !/[!@#$%^&*()_+\-=\[\]{};':"\\|,.<>\/?]/.test(pw)) {
        return 'Mật khẩu phải chứa ít nhất 1 chữ cái, 1 chữ số và 1 ký tự đặc biệt (!@#$%^&*)!';
    }
    return '';
}

// 0..4
function passwordStrength(pw) {
    if (!pw) return 0;
    let score = 0;
    if (pw.length >= 8) score++;
    if (pw.length >= 12) score++;
    if (/[a-z]/.test(pw) && /[A-Z]/.test(pw)) score++;
    if (/[0-9]/.test(pw) && /[^A-Za-z0-9]/.test(pw)) score++;
    if (/^(.)\1+$/.test(pw) || pw.length < 8) score = Math.min(score, 1);
    return Math.min(score, 4);
}

function updateStrength(inputId, barId, labelId) {
    const input = document.getElementById(inputId);
    const bar = document.getElementById(barId);
    const label = document.getElementById(labelId);
    if (!input || !bar || !label) return;
    const score = passwordStrength(input.value);
    const names = ['', 'Yếu', 'Trung bình', 'Khá', 'Mạnh'];
    bar.className = 'strength-bar s' + score;
    bar.style.width = (score * 25) + '%';
    label.textContent = input.value ? 'Độ mạnh: ' + names[score] : '';
}

function setInlineMessage(id, text, ok) {
    const node = document.getElementById(id);
    if (!node) return;
    node.textContent = text || '';
    node.className = 'inline-msg ' + (ok ? 'success' : 'error');
    if (!text) node.classList.add('hidden');
}

function clearInlineMessage(id) {
    const node = document.getElementById(id);
    if (node) {
        node.textContent = '';
        node.className = 'inline-msg hidden';
    }
}

// ============================================================================
// Quên mật khẩu (trang đăng nhập)
// ============================================================================

let resetMethod = 'OTP';
let otpCooldownTimer = null;

function showForgotPassword(event) {
    if (event) event.preventDefault();
    document.getElementById('login-form').classList.add('hidden');
    document.getElementById('register-form').classList.add('hidden');
    document.getElementById('forgot-form').classList.remove('hidden');
    document.getElementById('tab-login-btn').classList.remove('active');
    document.getElementById('tab-register-btn').classList.remove('active');
    const typed = document.getElementById('login-username').value.trim();
    if (typed) document.getElementById('fp-username').value = typed;
    selectResetMethod('OTP');
    clearInlineMessage('fp-message');
}

function backToLogin(event) {
    if (event) event.preventDefault();
    document.getElementById('forgot-form').reset();
    updateStrength('fp-new-password', 'fp-strength-bar', 'fp-strength-label');
    clearInlineMessage('fp-message');
    switchTab('login');
}

function selectResetMethod(method) {
    resetMethod = method === 'BACKUP_CODE' ? 'BACKUP_CODE' : 'OTP';
    document.getElementById('fp-method-otp').classList.toggle('active', resetMethod === 'OTP');
    document.getElementById('fp-method-backup').classList.toggle('active', resetMethod === 'BACKUP_CODE');
    document.getElementById('fp-send-otp').classList.toggle('hidden', resetMethod !== 'OTP');
    document.getElementById('fp-code-label').textContent =
        resetMethod === 'OTP' ? 'Mã OTP (gửi tới email khôi phục)' : 'Mã dự phòng (dùng một lần)';
    document.getElementById('fp-code').value = '';
}

function startOtpCooldown(seconds) {
    const btn = document.getElementById('fp-send-otp');
    let left = seconds;
    btn.disabled = true;
    clearInterval(otpCooldownTimer);
    const tick = () => {
        if (left <= 0) {
            clearInterval(otpCooldownTimer);
            btn.disabled = false;
            btn.textContent = '';
            const icon = document.createElement('i');
            icon.className = 'fa-solid fa-paper-plane';
            btn.appendChild(icon);
            btn.appendChild(document.createTextNode(' Gửi mã OTP'));
            return;
        }
        btn.textContent = 'Gửi lại sau ' + left + 's';
        left--;
    };
    tick();
    otpCooldownTimer = setInterval(tick, 1000);
}

async function handleSendOtp() {
    const username = document.getElementById('fp-username').value.trim();
    if (!username.toLowerCase().endsWith('@pbl4.com')) {
        showToast('Tài khoản phải có định dạng @pbl4.com!', 'error');
        return;
    }
    try {
        const response = await fetch('/api/auth/forgot-password', {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8' },
            body: formEncodeAuth({ username: username })
        });
        let data = null;
        try { data = await response.json(); } catch (e) { data = null; }
        // API luôn trả thông điệp chung: hiển thị nguyên văn, không suy đoán tài khoản có tồn tại hay không
        setInlineMessage('fp-message',
            (data && data.message) || 'Nếu thông tin hợp lệ, mã xác minh sẽ được gửi tới email khôi phục.', response.ok);
        startOtpCooldown(60);
    } catch (err) {
        setInlineMessage('fp-message', 'Lỗi kết nối tới Server!', false);
    }
}

async function handleResetPassword(event) {
    event.preventDefault();
    const username = document.getElementById('fp-username').value.trim();
    const code = document.getElementById('fp-code').value.replace(/[\s-]/g, '');
    const newPassword = document.getElementById('fp-new-password').value;
    const confirmPassword = document.getElementById('fp-confirm').value;

    if (!username.toLowerCase().endsWith('@pbl4.com')) {
        setInlineMessage('fp-message', 'Tài khoản phải có định dạng @pbl4.com!', false);
        return;
    }
    if (!/^[0-9A-Za-z]{4,20}$/.test(code)) {
        setInlineMessage('fp-message', 'Vui lòng nhập mã xác minh hợp lệ!', false);
        return;
    }
    const ruleError = validatePasswordRules(newPassword);
    if (ruleError) {
        setInlineMessage('fp-message', ruleError, false);
        return;
    }
    if (newPassword !== confirmPassword) {
        setInlineMessage('fp-message', 'Mật khẩu xác nhận không khớp!', false);
        return;
    }

    try {
        const response = await fetch('/api/auth/reset-password', {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8' },
            body: formEncodeAuth({
                username: username,
                method: resetMethod,
                code: code,
                newPassword: newPassword,
                confirmPassword: confirmPassword
            })
        });
        let data = null;
        try { data = await response.json(); } catch (e) { data = null; }

        if (data && data.success) {
            showToast(data.message || 'Đặt lại mật khẩu thành công!', 'success');
            document.getElementById('login-username').value = username;
            document.getElementById('forgot-form').reset();
            updateStrength('fp-new-password', 'fp-strength-bar', 'fp-strength-label');
            clearInlineMessage('fp-message');
            switchTab('login');
        } else {
            setInlineMessage('fp-message',
                (data && data.message) || 'Không thể đặt lại mật khẩu. Vui lòng kiểm tra thông tin và thử lại.', false);
        }
    } catch (err) {
        setInlineMessage('fp-message', 'Lỗi kết nối tới Server!', false);
    }
}

// ============================================================================
// Cửa sổ "Bảo mật tài khoản" (nằm trong main.html; main.html nạp file này TRƯỚC app.js)
// ============================================================================

let secCodes = [];   // chỉ giữ trong bộ nhớ, xóa khi đóng cửa sổ / chuyển tab / bấm "Tôi đã lưu lại"

async function secApi(method, url, params) {
    const token = localStorage.getItem('authToken');
    const headers = { 'Authorization': 'Bearer ' + token };
    const options = { method: method, headers: headers };
    if (params && method !== 'GET') {
        headers['Content-Type'] = 'application/x-www-form-urlencoded; charset=UTF-8';
        options.body = formEncodeAuth(params);
    }
    const response = await fetch(url, options);
    if (response.status === 401) {
        localStorage.removeItem('currentUser');
        localStorage.removeItem('authToken');
        window.location.href = '/';
        return { ok: false, status: 401, data: null };
    }
    let data = null;
    try { data = await response.json(); } catch (e) { data = null; }
    return { ok: response.ok, status: response.status, data: data };
}

function openSecurityModal(event) {
    if (event && event.preventDefault) event.preventDefault();
    document.getElementById('security-modal').classList.remove('hidden');
    switchSecTab('password');
}

function closeSecurityModal() {
    clearBackupCodes(false);
    ['sec-current-pw', 'sec-new-pw', 'sec-confirm-pw', 'sec-rec-pw', 'sec-verify-code', 'sec-codes-pw']
        .forEach(id => { const n = document.getElementById(id); if (n) n.value = ''; });
    updateStrength('sec-new-pw', 'sec-strength-bar', 'sec-strength-label');
    clearInlineMessage('sec-message');
    document.getElementById('sec-verify-form').classList.add('hidden');
    document.getElementById('sec-rec-form').classList.remove('hidden');
    document.getElementById('security-modal').classList.add('hidden');
}

function switchSecTab(tab) {
    clearBackupCodes(false);
    clearInlineMessage('sec-message');
    ['password', 'recovery', 'codes'].forEach(name => {
        document.getElementById('sec-tab-' + name).classList.toggle('active', name === tab);
        document.getElementById('sec-pane-' + name).classList.toggle('hidden', name !== tab);
    });
    if (tab === 'recovery' || tab === 'codes') loadRecoveryStatus();
}

async function loadRecoveryStatus() {
    const recBox = document.getElementById('sec-recovery-status');
    const codesBox = document.getElementById('sec-codes-status');
    recBox.textContent = 'Đang tải...';
    codesBox.textContent = '';
    try {
        const r = await secApi('GET', '/api/auth/recovery-status');
        if (!r.ok || !r.data) {
            recBox.textContent = 'Không tải được trạng thái khôi phục.';
            return;
        }
        const s = r.data;
        const email = s.recoveryEmail || s.maskedRecoveryEmail || '';
        const verified = s.recoveryEmailVerified;
        if (email) {
            recBox.textContent = 'Email khôi phục: ' + email
                + (verified === false ? ' (chưa xác minh)' : verified === true ? ' (đã xác minh)' : '');
        } else {
            recBox.textContent = 'Chưa cài email khôi phục.';
        }
        const remaining = (s.remainingBackupCodes !== undefined) ? s.remainingBackupCodes : s.backupCodesRemaining;
        codesBox.textContent = (remaining !== undefined && remaining !== null)
            ? 'Mã dự phòng còn dùng được: ' + remaining
            : (s.backupCodesConfigured ? 'Đã tạo mã dự phòng.' : 'Chưa tạo mã dự phòng.');
    } catch (err) {
        recBox.textContent = 'Lỗi kết nối Server!';
    }
}

async function handleChangePassword(event) {
    event.preventDefault();
    const currentPassword = document.getElementById('sec-current-pw').value;
    const newPassword = document.getElementById('sec-new-pw').value;
    const confirmPassword = document.getElementById('sec-confirm-pw').value;

    const ruleError = validatePasswordRules(newPassword);
    if (ruleError) { setInlineMessage('sec-message', ruleError, false); return; }
    if (newPassword === currentPassword) {
        setInlineMessage('sec-message', 'Mật khẩu mới không được trùng mật khẩu hiện tại!', false);
        return;
    }
    if (newPassword !== confirmPassword) {
        setInlineMessage('sec-message', 'Mật khẩu xác nhận không khớp!', false);
        return;
    }
    try {
        const r = await secApi('POST', '/api/auth/change-password',
            { currentPassword: currentPassword, newPassword: newPassword, confirmPassword: confirmPassword });
        if (r.status === 401) return;
        const ok = !!(r.ok && r.data && r.data.success);
        setInlineMessage('sec-message', (r.data && r.data.message) || (ok ? 'Đã đổi mật khẩu!' : 'Không thể đổi mật khẩu!'), ok);
        if (ok) {
            document.getElementById('sec-pw-form').reset();
            updateStrength('sec-new-pw', 'sec-strength-bar', 'sec-strength-label');
        }
    } catch (err) {
        setInlineMessage('sec-message', 'Lỗi kết nối Server!', false);
    }
}

async function handleSaveRecovery(event) {
    event.preventDefault();
    const recoveryEmail = document.getElementById('sec-rec-email').value.trim();
    const currentPassword = document.getElementById('sec-rec-pw').value;
    try {
        const r = await secApi('POST', '/api/auth/recovery-settings',
            { currentPassword: currentPassword, recoveryEmail: recoveryEmail });
        if (r.status === 401) return;
        const ok = !!(r.ok && r.data && r.data.success);
        setInlineMessage('sec-message', (r.data && r.data.message) || (ok ? 'Đã gửi mã xác minh.' : 'Không thể lưu email khôi phục!'), ok);
        document.getElementById('sec-rec-pw').value = '';
        if (ok) {
            document.getElementById('sec-rec-form').classList.add('hidden');
            document.getElementById('sec-verify-form').classList.remove('hidden');
            document.getElementById('sec-verify-code').focus();
        }
    } catch (err) {
        setInlineMessage('sec-message', 'Lỗi kết nối Server!', false);
    }
}

async function handleVerifyRecoveryEmail(event) {
    event.preventDefault();
    const code = document.getElementById('sec-verify-code').value.replace(/[\s-]/g, '');
    if (!code) { setInlineMessage('sec-message', 'Vui lòng nhập mã xác minh!', false); return; }
    try {
        const r = await secApi('POST', '/api/auth/recovery-email/verify', { code: code });
        if (r.status === 401) return;
        const ok = !!(r.ok && r.data && r.data.success);
        setInlineMessage('sec-message', (r.data && r.data.message) || (ok ? 'Đã xác minh email khôi phục!' : 'Mã xác minh không đúng hoặc đã hết hạn!'), ok);
        document.getElementById('sec-verify-code').value = '';
        if (ok) {
            document.getElementById('sec-verify-form').classList.add('hidden');
            document.getElementById('sec-rec-form').classList.remove('hidden');
            document.getElementById('sec-rec-form').reset();
            loadRecoveryStatus();
        }
    } catch (err) {
        setInlineMessage('sec-message', 'Lỗi kết nối Server!', false);
    }
}

async function handleGenerateBackupCodes(event) {
    event.preventDefault();
    const currentPassword = document.getElementById('sec-codes-pw').value;
    try {
        const r = await secApi('POST', '/api/auth/backup-codes/generate', { currentPassword: currentPassword });
        if (r.status === 401) return;
        document.getElementById('sec-codes-pw').value = '';
        if (r.ok && r.data && r.data.success && Array.isArray(r.data.codes)) {
            renderBackupCodes(r.data.codes.map(String));
            setInlineMessage('sec-message', (r.data.message) || 'Đã tạo mã dự phòng. Hãy lưu lại ngay!', true);
            loadRecoveryStatus();
        } else {
            setInlineMessage('sec-message', (r.data && r.data.message) || 'Không thể tạo mã dự phòng!', false);
        }
    } catch (err) {
        setInlineMessage('sec-message', 'Lỗi kết nối Server!', false);
    }
}

function renderBackupCodes(codes) {
    secCodes = codes.slice();
    const grid = document.getElementById('sec-codes-grid');
    grid.textContent = '';
    secCodes.forEach((c, i) => {
        const cell = document.createElement('div');
        cell.className = 'code-cell';
        cell.textContent = (i + 1) + '. ' + c;
        grid.appendChild(cell);
    });
    document.getElementById('sec-codes-result').classList.remove('hidden');
    document.getElementById('sec-codes-form').classList.add('hidden');
}

function clearBackupCodes(announce) {
    secCodes = [];
    const grid = document.getElementById('sec-codes-grid');
    if (grid) grid.textContent = '';
    const result = document.getElementById('sec-codes-result');
    if (result) result.classList.add('hidden');
    const form = document.getElementById('sec-codes-form');
    if (form) form.classList.remove('hidden');
    if (announce) setInlineMessage('sec-message', 'Đã ẩn mã dự phòng. Mã không thể xem lại.', true);
}

function backupCodesText() {
    const user = localStorage.getItem('currentUser') || '';
    return 'MÃ DỰ PHÒNG KHÔI PHỤC MẬT KHẨU - DUT Mail\n'
        + 'Tài khoản: ' + user + '\n'
        + 'Mỗi mã chỉ dùng được MỘT LẦN. Hãy cất ở nơi an toàn, không chia sẻ cho ai.\n\n'
        + secCodes.map((c, i) => (i + 1) + '. ' + c).join('\n') + '\n';
}

function downloadBackupCodes() {
    if (secCodes.length === 0) return;
    const blob = new Blob([backupCodesText()], { type: 'text/plain;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = 'ma-du-phong-dutmail.txt';
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function printBackupCodes() {
    if (secCodes.length === 0) return;
    const w = window.open('', '_blank');
    if (!w) {
        showToast('Trình duyệt chặn cửa sổ in. Hãy dùng nút Tải về.', 'error');
        return;
    }
    w.document.title = 'Mã dự phòng DUT Mail';
    const pre = w.document.createElement('pre');
    pre.style.fontSize = '16px';
    pre.textContent = backupCodesText();
    w.document.body.appendChild(pre);
    w.focus();
    w.print();
}