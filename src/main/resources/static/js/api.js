// FILE: src/main/resources/web/js/api.js
// Lớp gọi API dùng chung cho cookie phiên (pbl4_session HttpOnly) + CSRF (pbl4_csrf).
// Nạp file này TRƯỚC mọi script khác. Không ghi đè window.fetch, không dùng thư viện ngoài.
(function () {
    'use strict';

    var LOGIN_URL = '/';
    var EXPIRED_MSG = 'Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại';
    var FLASH_KEY = 'pbl4_flash';
    var UNSAFE_METHOD = /^(POST|PUT|PATCH|DELETE)$/;
    var redirecting = false;

    // Di trú: token không còn được lưu ở localStorage nữa
    try { localStorage.removeItem('authToken'); } catch (e) { /* bỏ qua */ }

    function readCookie(name) {
        var all = document.cookie ? document.cookie.split('; ') : [];
        for (var i = 0; i < all.length; i++) {
            var idx = all[i].indexOf('=');
            if (idx < 0) continue;
            if (all[i].slice(0, idx) === name) {
                var value = all[i].slice(idx + 1);
                try { return decodeURIComponent(value); } catch (e) { return value; }
            }
        }
        return '';
    }

    function isLoginPage() {
        var p = window.location.pathname;
        return p === '/' || p === '/index.html';
    }

    function clearLocalSession() {
        try {
            localStorage.removeItem('currentUser');
            localStorage.removeItem('userRole');
            localStorage.removeItem('authToken');
        } catch (e) { /* bỏ qua */ }
    }

    function handleUnauthorized() {
        var hadUser = false;
        try { hadUser = !!localStorage.getItem('currentUser'); } catch (e) { hadUser = false; }
        clearLocalSession();

        if (isLoginPage()) {
            // Đang ở trang đăng nhập: không chuyển hướng, chỉ báo nếu trước đó có phiên
            if (hadUser && typeof window.showToast === 'function') {
                window.showToast(EXPIRED_MSG, 'error');
            }
            return;
        }
        if (redirecting) return;
        redirecting = true;
        if (hadUser) {
            try { sessionStorage.setItem(FLASH_KEY, EXPIRED_MSG); } catch (e) { /* bỏ qua */ }
        }
        window.location.replace(LOGIN_URL);
    }

    // Lấy (và xóa) thông báo chờ hiển thị ở trang đăng nhập
    function consumeFlash() {
        var msg = '';
        try {
            msg = sessionStorage.getItem(FLASH_KEY) || '';
            sessionStorage.removeItem(FLASH_KEY);
        } catch (e) { msg = ''; }
        return msg;
    }

    // options như fetch, thêm: noAuthRedirect:true => gặp 401 không dọn phiên/không chuyển trang
    // (dùng cho đăng nhập, đăng ký, quên mật khẩu, đăng xuất).
    function apiFetch(url, options) {
        var opts = Object.assign({}, options || {});
        var noRedirect = !!opts.noAuthRedirect;
        delete opts.noAuthRedirect;

        var method = String(opts.method || 'GET').toUpperCase();
        opts.method = method;
        opts.credentials = 'same-origin';

        var headers = new Headers(opts.headers || {});
        if (UNSAFE_METHOD.test(method) && !headers.has('X-CSRF-Token')) {
            var csrf = readCookie('pbl4_csrf');
            if (csrf) headers.set('X-CSRF-Token', csrf);
        }
        opts.headers = headers;

        return fetch(url, opts).then(function (res) {
            if (res.status === 401 && !noRedirect) handleUnauthorized();
            return res;
        });
    }

    // Kiểm tra phiên: GET /api/auth/me. Trả về
    // { ok, status, username, role, data, networkError }
    // status 0 = không kết nối được server (không coi là hết phiên).
    async function checkSession(options) {
        var res;
        try {
            res = await apiFetch('/api/auth/me', {
                method: 'GET',
                cache: 'no-store',
                noAuthRedirect: !!(options && options.noAuthRedirect)
            });
        } catch (e) {
            return { ok: false, status: 0, username: '', role: '', data: null, networkError: true };
        }
        var data = null;
        try { data = await res.json(); } catch (e) { data = null; }

        var ok = !!(res.ok && (!data || data.success !== false));
        var username = '';
        var role = '';
        if (data && typeof data === 'object') {
            username = String(data.username || data.user || data.email || '');
            role = String(data.role || '');
        }
        if (ok) {
            try {
                if (username) localStorage.setItem('currentUser', username);
                if (role) localStorage.setItem('userRole', role);
            } catch (e) { /* bỏ qua */ }
        }
        return { ok: ok, status: res.status, username: username, role: role, data: data, networkError: false };
    }

    window.apiFetch = apiFetch;
    window.checkSession = checkSession;
    window.consumeFlash = consumeFlash;
    window.readCookie = readCookie;
})();