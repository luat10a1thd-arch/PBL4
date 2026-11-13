// FILE: src/main/resources/web/js/app.js
let selectedFiles = [];
let activeMailbox = 'inbox';
let currentSelectedMail = null;
let currentDetailMail = null;
let replyContext = null;          // { id, folder, mode: 'reply' | 'forward' }
let confirmCallback = null;
let loadedMailsCache = [];

let currentPage = 1;
const PAGE_LIMIT = 30;
let totalMailCount = 0;
let currentRequestId = 0;
let searchActive = false;
let sendingMail = false;

let sseSource = null;
let sseReconnectTimer = null;
let sseRetryDelay = 3000;
let sseGeneration = 0;

const MAILBOXES = {
    inbox:   { title: 'Hộp thư đến',  endpoint: '/api/mails/inbox',   nav: 'nav-inbox' },
    starred: { title: 'Thư gắn sao',  endpoint: '/api/mails/starred', nav: 'nav-starred' },
    sent:    { title: 'Thư đã gửi',   endpoint: '/api/mails/sent',    nav: 'nav-sent' },
    drafts:  { title: 'Thư nháp',     endpoint: '/api/mails/drafts',  nav: 'nav-drafts' },
    spam:    { title: 'Thư rác',      endpoint: '/api/mails/spam',    nav: 'nav-spam' },
    trash:   { title: 'Thùng rác',    endpoint: '/api/mails/trash',   nav: 'nav-trash' }
};
const REAL_FOLDERS = ['inbox', 'sent', 'spam', 'drafts', 'trash'];

document.addEventListener('DOMContentLoaded', () => {
    const currentUser = localStorage.getItem('currentUser');
    const token = localStorage.getItem('authToken');

    if (!currentUser || !token) {
        window.location.href = '/';
        return;
    }

    const userEmailSpan = document.getElementById('current-user-email');
    if (userEmailSpan) {
        userEmailSpan.textContent = currentUser;
    }

    initComposeAutosave();
    loadInbox();
    updateStorageUsage();
    startRealtimeListener();
});

// ================= Gọi API (dùng chung, token lấy từ phiên đăng nhập sẵn có) =================

function formEncode(params) {
    return Object.keys(params || {})
        .filter(k => params[k] !== undefined && params[k] !== null)
        .map(k => encodeURIComponent(k) + '=' + encodeURIComponent(params[k]))
        .join('&');
}

async function apiCall(method, url, params) {
    const token = localStorage.getItem('authToken');
    const headers = { 'Authorization': 'Bearer ' + token };
    const options = { method: method, headers: headers };
    if (params && method !== 'GET') {
        headers['Content-Type'] = 'application/x-www-form-urlencoded';
        options.body = formEncode(params);
    }
    const response = await fetch(url, options);
    if (response.status === 401) {
        logout();
        return { ok: false, status: 401, data: null, headers: response.headers };
    }
    let data = null;
    try { data = await response.json(); } catch (e) { data = null; }
    return { ok: response.ok, status: response.status, data: data, headers: response.headers };
}

function actionOk(r) {
    return !!(r && r.ok && r.data && r.data.success !== false);
}

async function mailAction(url, params, okMsg, errMsg) {
    try {
        const r = await apiCall('POST', url, params);
        if (r.status === 401) return false;
        if (actionOk(r)) {
            showToast((r.data && r.data.message) || okMsg, 'success');
            return true;
        }
        showToast((r.data && r.data.message) || errMsg, 'error');
    } catch (e) {
        showToast('Lỗi kết nối Server!', 'error');
    }
    return false;
}

function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = text;
    return node;
}

function setText(id, text) {
    const node = document.getElementById(id);
    if (node) node.textContent = text;
}

// ================= Thông báo realtime (SSE) =================

async function startRealtimeListener() {
    stopRealtimeListener();

    const token = localStorage.getItem('authToken');
    if (!token) return;

    const generation = ++sseGeneration;

    let ticket = null;
    try {
        const res = await fetch('/api/mails/stream-ticket', {
            method: 'POST',
            headers: { 'Authorization': 'Bearer ' + token }
        });
        if (res.status === 401) return;
        if (!res.ok) throw new Error('ticket');
        const data = await res.json();
        ticket = data.ticket;
    } catch (e) {
        scheduleSseReconnect();
        return;
    }
    if (!ticket || generation !== sseGeneration) return;

    sseSource = new EventSource(`/api/mails/stream?ticket=${encodeURIComponent(ticket)}`);

    sseSource.onopen = () => {
        sseRetryDelay = 3000;
    };

    sseSource.onmessage = (event) => {
        if (activeMailbox === 'inbox') {
            refreshMails();
        }
    };

    sseSource.addEventListener('new_mail', (event) => {
        try {
            const data = JSON.parse(event.data);
            showToast(`Bạn có email mới từ: ${data.sender || 'Người gửi'}`, 'success');
        } catch (e) {
            showToast('Bạn có email mới!', 'success');
        }

        if (activeMailbox === 'inbox') {
            refreshMails();
        }
    });

    sseSource.addEventListener('evicted', () => {
        sseGeneration++;
        if (sseSource) { sseSource.close(); sseSource = null; }
    });

    sseSource.onerror = () => {
        if (sseSource) { sseSource.close(); sseSource = null; }
        scheduleSseReconnect();
    };
}

function scheduleSseReconnect() {
    if (sseReconnectTimer) return;
    const delay = sseRetryDelay;
    sseRetryDelay = Math.min(sseRetryDelay * 2, 60000);
    sseReconnectTimer = setTimeout(() => {
        sseReconnectTimer = null;
        startRealtimeListener();
    }, delay + Math.floor(Math.random() * 1000));
}

function stopRealtimeListener() {
    sseGeneration++;
    if (sseReconnectTimer) {
        clearTimeout(sseReconnectTimer);
        sseReconnectTimer = null;
    }
    if (sseSource) {
        sseSource.close();
        sseSource = null;
    }
}

// ================= Toast + hộp xác nhận =================

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

// options (tùy chọn): { title, message, yesLabel }
function showConfirmModal(onConfirm, options) {
    const opts = options || {};
    setText('confirm-title', opts.title || 'Xác nhận xóa thư');
    setText('confirm-msg', opts.message || 'Bạn có chắc chắn muốn xóa lá thư này vĩnh viễn?');
    setText('confirm-yes-btn', opts.yesLabel || 'Đồng ý xóa');
    confirmCallback = onConfirm;
    document.getElementById('confirm-modal').classList.remove('hidden');
}

function closeConfirmModal(result) {
    document.getElementById('confirm-modal').classList.add('hidden');
    if (result && typeof confirmCallback === 'function') {
        confirmCallback();
    }
    confirmCallback = null;
}

// ================= Soạn thư + tự lưu nháp =================

const DRAFT_DELAY_MS = 3000;
let composeSession = createComposeSession();
let draftQueue = Promise.resolve();
let draftTimer = null;

function createComposeSession() {
    return { draftId: null, dirty: false, sent: false, lastSig: '' };
}

function isComposeOpen() {
    return !document.getElementById('compose-modal').classList.contains('hidden');
}

function initComposeAutosave() {
    ['compose-to', 'compose-subject', 'compose-body'].forEach(id => {
        const node = document.getElementById(id);
        if (node) node.addEventListener('input', onComposeInput);
    });
}

function readComposeFields() {
    return {
        to: document.getElementById('compose-to').value.trim(),
        subject: document.getElementById('compose-subject').value.trim(),
        body: document.getElementById('compose-body').value
    };
}

function onComposeInput() {
    composeSession.dirty = true;
    clearTimeout(draftTimer);
    const session = composeSession;
    draftTimer = setTimeout(() => {
        draftTimer = null;
        if (session !== composeSession) return;
        queueDraftSave(session, readComposeFields(), false);
    }, DRAFT_DELAY_MS);
    setText('draft-status', '');
}

// Mọi lần lưu nháp xếp hàng tuần tự để không tạo trùng nháp
function queueDraftSave(session, snap, announce) {
    if (!session.dirty || session.sent) return;
    if (!snap.to && !snap.subject && !snap.body.trim()) return;
    draftQueue = draftQueue.then(() => saveDraftNow(session, snap, announce)).catch(() => {});
}

async function saveDraftNow(session, snap, announce) {
    if (session.sent) return;
    const sig = snap.to + '\u0000' + snap.subject + '\u0000' + snap.body;
    if (sig === session.lastSig) return;

    const params = { to: snap.to, subject: snap.subject, body: snap.body };
    if (session.draftId) params.id = session.draftId;

    let r;
    try {
        r = await apiCall('POST', '/api/mails/save-draft', params);
    } catch (e) {
        if (announce) showToast('Không lưu được thư nháp!', 'error');
        return;
    }
    if (session.sent) return;
    if (actionOk(r) && r.data.id) {
        session.draftId = r.data.id;
        session.lastSig = sig;
        if (session === composeSession) {
            setText('draft-status', 'Đã lưu nháp ' + new Date().toLocaleTimeString('vi-VN'));
        }
        if (announce) {
            showToast('Đã lưu vào Thư nháp', 'success');
            if (activeMailbox === 'drafts') loadDrafts();
            updateStorageUsage();
        }
    } else if (announce && r.status !== 401) {
        showToast((r.data && r.data.message) || 'Không lưu được thư nháp!', 'error');
    }
}

async function deleteDraftOnServer(session) {
    if (!session.draftId) return;
    const id = session.draftId;
    session.draftId = null;
    try {
        await apiCall('POST', '/api/mails/delete-forever', { id: id, folder: 'drafts' });
    } catch (e) { /* bỏ qua: nháp mồ côi sẽ nằm trong mục Thư nháp để người dùng tự xóa */ }
    if (activeMailbox === 'drafts') loadDrafts();
    updateStorageUsage();
}

function openComposeModal() {
    if (!isComposeOpen()) composeSession = createComposeSession();
    document.getElementById('compose-modal').classList.remove('hidden');
}

// Mở cửa sổ soạn MỚI HẲN (đóng + lưu nháp cái đang mở nếu có)
function beginCompose() {
    if (isComposeOpen()) closeComposeModal();
    composeSession = createComposeSession();
    document.getElementById('compose-modal').classList.remove('hidden');
}

function closeComposeModal(skipDraft) {
    clearTimeout(draftTimer);
    draftTimer = null;
    const session = composeSession;
    const snap = readComposeFields();
    document.getElementById('compose-modal').classList.add('hidden');
    resetComposeForm();
    composeSession = createComposeSession();
    if (skipDraft !== true) queueDraftSave(session, snap, true);
}

function resetComposeForm() {
    document.getElementById('compose-form').reset();
    selectedFiles = [];
    replyContext = null;
    const subj = document.getElementById('compose-subject');
    subj.readOnly = false;
    subj.removeAttribute('title');
    setText('draft-status', '');
    renderFilePreview();
}

function handleFileSelect(event) {
    const files = Array.from(event.target.files);

    files.forEach(file => {
        const isDuplicate = selectedFiles.some(
            f => f.name === file.name && f.size === file.size
        );

        if (!isDuplicate) {
            selectedFiles.push(file);
        }
    });

    event.target.value = '';
    renderFilePreview();
}

function removeFile(index) {
    selectedFiles.splice(index, 1);
    renderFilePreview();
}

function renderFilePreview() {
    const previewContainer = document.getElementById('file-preview-list');
    if (!previewContainer) return;

    previewContainer.textContent = '';
    if (selectedFiles.length === 0) return;

    selectedFiles.forEach((file, index) => {
        const chip = el('div', 'file-chip');
        chip.appendChild(el('i', 'fa-solid fa-file file-chip-icon'));
        chip.appendChild(el('span', null, file.name));
        chip.appendChild(el('span', 'file-chip-size', '(' + formatFileSize(file.size) + ')'));
        const rm = el('i', 'fa-solid fa-xmark file-chip-remove');
        rm.title = 'Xóa file này';
        rm.onclick = () => removeFile(index);
        chip.appendChild(rm);
        previewContainer.appendChild(chip);
    });
}

function formatFileSize(bytes) {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}

function convertFileToBase64(file) {
    return new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.readAsDataURL(file);
        reader.onload = () => resolve(reader.result);
        reader.onerror = error => reject(error);
    });
}

async function handleSendMail(event) {
    event.preventDefault();
    if (sendingMail) return;

    const rawTo = document.getElementById('compose-to').value.trim();
    const subject = document.getElementById('compose-subject').value.trim();
    const bodyText = document.getElementById('compose-body').value;

    if (!rawTo || !subject || !bodyText) {
        showToast('Vui lòng điền đầy đủ thông tin người nhận, tiêu đề và nội dung!', 'error');
        return;
    }

    const recipients = rawTo.split(/[\s,;]+/).filter(email => email.length > 0);
    if (recipients.length === 0) {
        showToast('Địa chỉ email người nhận không hợp lệ!', 'error');
        return;
    }

    sendingMail = true;
    const submitBtn = document.querySelector('#compose-form button[type="submit"]');
    if (submitBtn) submitBtn.disabled = true;
    clearTimeout(draftTimer);
    draftTimer = null;

    try {
        let attachmentData = '';
        for (let i = 0; i < selectedFiles.length; i++) {
            const file = selectedFiles[i];
            try {
                const base64 = await convertFileToBase64(file);
                attachmentData += `\n[ATTACHMENT:${file.name}]${base64}[/ATTACHMENT]`;
            } catch (err) {
                showToast(`Lỗi đọc file: ${file.name}`, 'error');
                return;
            }
        }

        const fullContent = bodyText + attachmentData;
        let url;
        let params;
        if (replyContext) {
            url = '/api/mails/reply';
            params = {
                id: replyContext.id,
                folder: replyContext.folder,
                mode: replyContext.mode,
                to: recipients.join(','),
                body: fullContent
            };
        } else {
            url = '/api/mails/send';
            params = { to: recipients.join(','), subject: subject, body: fullContent };
        }

        const session = composeSession;
        const r = await apiCall('POST', url, params);
        if (r.status === 401) return;

        if (actionOk(r)) {
            session.sent = true;
            // Xóa nháp (nếu có) sau khi mọi lần lưu đang chờ đã xong
            draftQueue = draftQueue.then(() => deleteDraftOnServer(session)).catch(() => {});
            closeComposeModal(true);
            showToast((r.data && r.data.message) || 'Gửi thư thành công!', 'success');
            refreshMails();
            updateStorageUsage();
        } else {
            showToast((r.data && r.data.message) || 'Gửi thư thất bại!', 'error');
        }
    } catch (err) {
        showToast('Gửi thư thất bại, lỗi kết nối Server!', 'error');
    } finally {
        sendingMail = false;
        if (submitBtn) submitBtn.disabled = false;
    }
}

// ================= Danh sách thư =================

function mailFolder(mail) {
    if (mail && REAL_FOLDERS.indexOf(mail.folder) >= 0) return mail.folder;
    return activeMailbox === 'starred' ? 'inbox' : activeMailbox;
}

async function switchMailbox(box, event) {
    if (event && event.preventDefault) event.preventDefault();
    const info = MAILBOXES[box];
    if (!info) return;

    activeMailbox = box;
    searchActive = false;
    const searchInput = document.getElementById('mail-search-input');
    if (searchInput) searchInput.value = '';
    updateActiveNav(info.nav, info.title);
    updateEmptyButton();
    resetEmailDetail();

    currentPage = 1;
    loadedMailsCache = [];
    currentRequestId++;

    await fetchMailsPaged(currentRequestId, false);
}

function loadInbox(event)    { return switchMailbox('inbox', event); }
function loadSentMail(event) { return switchMailbox('sent', event); }
function loadStarred(event)  { return switchMailbox('starred', event); }
function loadDrafts(event)   { return switchMailbox('drafts', event); }
function loadSpam(event)     { return switchMailbox('spam', event); }
function loadTrash(event)    { return switchMailbox('trash', event); }

async function fetchMailsPaged(requestId, isAppend = false) {
    const endpoint = MAILBOXES[activeMailbox].endpoint;

    try {
        const r = await apiCall('GET', `${endpoint}?page=${currentPage}&limit=${PAGE_LIMIT}`);
        if (r.status === 401) return;
        if (requestId !== currentRequestId) return;

        if (!r.ok) {
            showToast('Không thể tải danh sách thư!', 'error');
            renderEmailList([]);
            return;
        }

        const newMails = Array.isArray(r.data) ? r.data : [];
        loadedMailsCache = isAppend ? loadedMailsCache.concat(newMails) : newMails;

        const totalHeader = r.headers.get('X-Total-Count');
        totalMailCount = totalHeader !== null ? IntegerParseSafe(totalHeader) : loadedMailsCache.length;

        renderEmailList(loadedMailsCache);
    } catch (err) {
        console.error('Không thể tải danh sách thư:', err);
    }
}

function IntegerParseSafe(str) {
    try {
        return parseInt(str, 10) || 0;
    } catch (e) {
        return 0;
    }
}

async function loadMoreMails() {
    currentPage++;
    await fetchMailsPaged(currentRequestId, true);
}

function renderEmailList(mails) {
    const emailListDiv = document.getElementById('email-list');
    emailListDiv.textContent = '';

    if (!mails || mails.length === 0) {
        emailListDiv.appendChild(el('div', 'empty-list',
            searchActive ? 'Không tìm thấy thư phù hợp.' : 'Không có email nào.'));
        return;
    }

    const showRecipient = (activeMailbox === 'sent' || activeMailbox === 'drafts');

    mails.forEach(mail => {
        const unread = mail.seen === false;
        const isActive = currentSelectedMail && currentSelectedMail.id === mail.id;
        const item = el('div', 'email-item' + (unread ? ' unread' : '') + (isActive ? ' active' : ''));
        item.onclick = () => selectEmail(mail, item);

        const main = el('div', 'email-item-main');
        const senderRow = el('div', 'email-sender');
        if (unread) senderRow.appendChild(el('span', 'unread-dot'));
        if (mail.starred) senderRow.appendChild(el('i', 'fa-solid fa-star star-on'));
        const who = showRecipient
            ? (mail.recipient || '(Chưa có người nhận)')
            : (mail.sender || mail.recipient || '(Không rõ người gửi)');
        senderRow.appendChild(el('span', null, who));
        main.appendChild(senderRow);
        main.appendChild(el('div', 'email-subject', mail.subject || '(Không có chủ đề)'));
        main.appendChild(el('div', 'email-date', formatMailDate(mail.timestamp)));
        item.appendChild(main);

        const delBtn = el('button', 'delete-mail-btn');
        const inPurgeBox = activeMailbox === 'trash' || activeMailbox === 'drafts';
        delBtn.title = inPurgeBox ? 'Xóa vĩnh viễn' : 'Chuyển vào Thùng rác';
        delBtn.appendChild(el('i', 'fa-solid fa-trash'));
        delBtn.onclick = (e) => {
            e.stopPropagation();
            handleDeleteMail(mail.id, e);
        };
        item.appendChild(delBtn);

        emailListDiv.appendChild(item);
    });

    const remaining = totalMailCount - loadedMailsCache.length;
    if (remaining > 0) {
        const wrapper = el('div', 'load-more-wrap');
        const loadMoreBtn = el('button', 'btn btn-outline load-more-btn');
        loadMoreBtn.appendChild(el('i', 'fa-solid fa-angles-down'));
        loadMoreBtn.appendChild(document.createTextNode(` Tải thêm (${remaining} thư còn lại)`));
        loadMoreBtn.onclick = () => loadMoreMails();
        wrapper.appendChild(loadMoreBtn);
        emailListDiv.appendChild(wrapper);
    }
}

function applyClientFilter() {
    const searchInput = document.getElementById('mail-search-input');
    const keyword = searchInput ? searchInput.value.trim().toLowerCase() : '';
    if (!keyword) {
        renderEmailList(loadedMailsCache);
        return;
    }
    const filtered = loadedMailsCache.filter(mail => {
        const sender = (mail.sender || '').toLowerCase();
        const recipient = (mail.recipient || '').toLowerCase();
        const subject = (mail.subject || '').toLowerCase();
        return sender.includes(keyword) || recipient.includes(keyword) || subject.includes(keyword);
    });
    renderEmailList(filtered);
}

// Gõ: lọc nhanh trong danh sách đang tải. Enter: tìm trên server trong mục hiện tại.
function filterMails(event) {
    const searchInput = document.getElementById('mail-search-input');
    if (!searchInput) return;
    const keyword = searchInput.value.trim();

    if (event && event.key === 'Enter') {
        serverSearch(keyword);
        return;
    }
    if (!keyword && searchActive) {
        switchMailbox(activeMailbox);
        return;
    }
    applyClientFilter();
}

async function serverSearch(keyword) {
    if (!keyword) {
        switchMailbox(activeMailbox);
        return;
    }
    searchActive = true;
    currentRequestId++;
    const requestId = currentRequestId;
    try {
        const r = await apiCall('GET',
            `/api/mails/search?q=${encodeURIComponent(keyword)}&folder=${encodeURIComponent(activeMailbox)}`);
        if (r.status === 401) return;
        if (requestId !== currentRequestId) return;
        if (!r.ok) {
            showToast((r.data && r.data.message) || 'Tìm kiếm thất bại!', 'error');
            return;
        }
        loadedMailsCache = Array.isArray(r.data) ? r.data : [];
        totalMailCount = loadedMailsCache.length;
        renderEmailList(loadedMailsCache);
    } catch (err) {
        showToast('Lỗi kết nối khi tìm kiếm!', 'error');
    }
}

// ================= Dung lượng =================

async function updateStorageUsage() {
    try {
        let used = 0;
        let limit = 0;
        let percent = null;

        let r = await apiCall('GET', '/api/mails/quota');
        if (r.status === 401) return;
        if (r.ok && r.data && Number(r.data.limitBytes) > 0) {
            used = Number(r.data.usedBytes) || 0;
            limit = Number(r.data.limitBytes);
            if (typeof r.data.percent === 'number') percent = r.data.percent;
        } else {
            r = await apiCall('GET', '/api/mails/storage-usage');
            if (!(r.ok && r.data && r.data.success)) return;
            used = Number(r.data.usedBytes) || 0;
            limit = Number(r.data.quotaBytes) || 0;
        }
        if (percent === null) percent = limit > 0 ? (used / limit) * 100 : 0;
        percent = Math.min(100, Math.max(0, percent));

        const MB = 1024 * 1024;
        setText('storage-text', `${(used / MB).toFixed(2)} MB / ${parseFloat((limit / MB).toFixed(2))} MB`);
        const progress = document.getElementById('storage-progress');
        if (progress) {
            progress.style.width = `${percent.toFixed(1)}%`;
            progress.classList.toggle('danger', percent >= 90);
        }
    } catch (err) {
        console.error('Không thể cập nhật dung lượng hòm thư:', err);
    }
}

// ================= Xóa / chuyển thùng rác / dọn =================

function handleDeleteMail(mailId, event) {
    if (event) event.stopPropagation();
    const mail = loadedMailsCache.find(m => m.id === mailId);
    if (!mail) return;
    const folder = mailFolder(mail);
    if (folder === 'trash' || folder === 'drafts') {
        confirmPurge(mail);
    } else {
        trashMail(mail);
    }
}

function confirmPurge(mail) {
    showConfirmModal(() => purgeMail(mail), {
        title: 'Xóa vĩnh viễn',
        message: 'Thư sẽ bị xóa vĩnh viễn và không thể khôi phục. Bạn có chắc chắn không?',
        yesLabel: 'Xóa vĩnh viễn'
    });
}

async function trashMail(mail) {
    const ok = await mailAction('/api/mails/trash', { id: mail.id, folder: mailFolder(mail) },
        'Đã chuyển thư vào Thùng rác!', 'Không thể xóa thư!');
    if (ok) afterMailChanged();
}

async function purgeMail(mail) {
    const ok = await mailAction('/api/mails/delete-forever', { id: mail.id, folder: mailFolder(mail) },
        'Đã xóa vĩnh viễn thư!', 'Xóa thư thất bại!');
    if (ok) afterMailChanged();
}

function afterMailChanged() {
    resetEmailDetail();
    refreshMails();
}

function updateEmptyButton() {
    const btn = document.getElementById('empty-folder-btn');
    if (!btn) return;
    const canEmpty = activeMailbox === 'trash' || activeMailbox === 'spam';
    btn.classList.toggle('hidden', !canEmpty);
    setText('empty-folder-label', activeMailbox === 'trash' ? 'Dọn Thùng rác' : 'Dọn Thư rác');
}

function emptyCurrentFolder() {
    if (activeMailbox !== 'trash' && activeMailbox !== 'spam') return;
    const box = activeMailbox;
    const title = box === 'trash' ? 'Dọn sạch Thùng rác' : 'Dọn sạch Thư rác';
    showConfirmModal(async () => {
        const ok = await mailAction('/api/mails/empty', { folder: box },
            'Đã dọn sạch ' + MAILBOXES[box].title + '!', 'Không thể dọn thư mục này!');
        if (ok) afterMailChanged();
    }, {
        title: title,
        message: 'Toàn bộ thư trong mục này sẽ bị xóa vĩnh viễn và không thể khôi phục. Bạn có chắc chắn không?',
        yesLabel: 'Dọn sạch'
    });
}

// ================= Chi tiết thư + thanh công cụ =================

function resetEmailDetail() {
    currentSelectedMail = null;
    currentDetailMail = null;
    document.getElementById('no-email-selected').classList.remove('hidden');
    document.getElementById('email-detail').classList.add('hidden');
}

async function selectEmail(mail, element) {
    if (!mail || typeof mail !== 'object') return;

    document.querySelectorAll('.email-item').forEach(item => {
        item.classList.remove('active');
    });

    if (element) element.classList.add('active');
    currentSelectedMail = mail;

    try {
        const r = await apiCall('GET',
            `/api/mails/detail?id=${encodeURIComponent(mail.id)}&folder=${encodeURIComponent(mailFolder(mail))}`);
        if (r.status === 401) return;
        if (r.ok && r.data && typeof r.data === 'object') {
            renderEmailDetail(Object.assign({}, mail, r.data));
        } else {
            renderEmailDetail(mail);
        }
    } catch (err) {
        renderEmailDetail(mail);
    }

    if (mail.seen === false) {
        mail.seen = true;
        if (element) element.classList.remove('unread');
        apiCall('POST', '/api/mails/flags', { id: mail.id, folder: mailFolder(mail), seen: 'true' }).catch(() => {});
    }
}

function downloadAttachment(fileName, rawBase64) {
    try {
        if (!rawBase64) {
            showToast('Nội dung file đính kèm rỗng!', 'error');
            return;
        }

        let cleanBase64 = rawBase64.trim();
        let mimeType = 'application/octet-stream';

        const prefixMatch = cleanBase64.match(/^data:([^;]+);base64,/);
        if (prefixMatch) {
            if (prefixMatch[1]) mimeType = prefixMatch[1];
            cleanBase64 = cleanBase64.slice(prefixMatch[0].length);
        }

        cleanBase64 = cleanBase64.replace(/\s/g, '');
        cleanBase64 = cleanBase64.replace(/[^A-Za-z0-9+/=]/g, '');

        const byteCharacters = atob(cleanBase64);
        const byteArray = Uint8Array.from(byteCharacters, c => c.charCodeAt(0));
        const blob = new Blob([byteArray], { type: mimeType });

        const blobUrl = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = blobUrl;
        link.download = fileName;
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
        setTimeout(() => URL.revokeObjectURL(blobUrl), 1000);
    } catch (e) {
        console.error('Lỗi giải mã attachment:', e);
        showToast('Lỗi giải mã file đính kèm!', 'error');
    }
}

function updateDetailToolbar(mail) {
    const show = (id, visible) => {
        const node = document.getElementById(id);
        if (node) node.classList.toggle('hidden', !visible);
    };
    const box = activeMailbox;
    const inboxLike = box === 'inbox' || box === 'starred';

    show('reply-btn', inboxLike);
    show('forward-btn', inboxLike || box === 'sent');
    show('edit-draft-btn', box === 'drafts');
    show('star-btn', inboxLike || box === 'sent');
    show('unread-btn', inboxLike);
    show('spam-btn', inboxLike);
    show('unspam-btn', box === 'spam');
    show('trash-btn', box === 'inbox' || box === 'starred' || box === 'sent' || box === 'spam');
    show('restore-btn', box === 'trash');
    show('purge-btn', box === 'trash' || box === 'drafts');

    const starred = !!(mail && mail.starred);
    setText('star-btn-label', starred ? 'Bỏ sao' : 'Gắn sao');
    const icon = document.getElementById('star-btn-icon');
    if (icon) icon.className = (starred ? 'fa-solid' : 'fa-regular') + ' fa-star' + (starred ? ' star-on' : '');
}

function renderEmailDetail(mail) {
    currentDetailMail = mail;

    document.getElementById('no-email-selected').classList.add('hidden');
    document.getElementById('email-detail').classList.remove('hidden');

    updateDetailToolbar(mail);

    document.getElementById('detail-subject').textContent = mail.subject || '';
    document.getElementById('detail-sender').textContent = mail.sender || '';
    document.getElementById('detail-receiver').textContent =
        mail.recipient || localStorage.getItem('currentUser') || '';
    document.getElementById('detail-time').textContent =
        formatMailDate(mail.timestamp);

    const detailBodyElem = document.getElementById('detail-body');
    detailBodyElem.innerHTML = '';
    detailBodyElem.style.whiteSpace = 'pre-wrap';

    let bodyText = mail.body || '';

    if (bodyText.includes('[ATTACHMENT:')) {
        const firstAttachIndex = bodyText.indexOf('[ATTACHMENT:');
        const textContent = bodyText.slice(0, firstAttachIndex);

        const attachRegex = /\[ATTACHMENT:([^\]\r\n]+)\](?:data:[^;]+;base64,)?([A-Za-z0-9+/=\s]+)\[\/ATTACHMENT\]/g;
        const attachments = [];
        let match;

        while ((match = attachRegex.exec(bodyText)) !== null) {
            attachments.push({
                fileName: match[1],
                base64Data: match[2].trim()
            });
        }

        const textDiv = document.createElement('div');
        textDiv.style.whiteSpace = 'pre-wrap';
        textDiv.textContent = textContent;
        detailBodyElem.appendChild(textDiv);

        const attachSection = document.createElement('div');
        attachSection.style.cssText = 'margin-top: 25px; border-top: 1px dashed #cbd5e1; padding-top: 15px;';

        const attachTitle = document.createElement('strong');
        attachTitle.style.cssText = 'color: #475569; font-size: 13px;';
        attachTitle.textContent = '📎 Danh sách file đính kèm:';
        attachSection.appendChild(attachTitle);

        const attachContainer = document.createElement('div');
        attachContainer.style.marginTop = '8px';

        for (const att of attachments) {
            const attachBox = document.createElement('div');
            attachBox.style.cssText = 'margin-top: 10px; padding: 10px 14px; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; display: inline-block; margin-right: 10px;';

            const nameDiv = document.createElement('div');
            nameDiv.style.cssText = 'font-weight: 600; color: #334155; font-size: 13px; margin-bottom: 4px;';
            nameDiv.innerHTML = '<i class="fa-solid fa-paperclip"></i> ';

            const nameSpan = document.createElement('span');
            nameSpan.textContent = att.fileName;
            nameDiv.appendChild(nameSpan);
            attachBox.appendChild(nameDiv);

            if (att.base64Data.length > 0) {
                const downloadBtn = document.createElement('button');
                downloadBtn.className = 'btn btn-outline';
                downloadBtn.style.cssText = 'font-size: 11px; padding: 3px 8px; color: #2563eb; cursor: pointer; display: inline-flex; align-items: center; gap: 4px; border-radius: 4px;';
                downloadBtn.innerHTML = '<i class="fa-solid fa-download"></i> Tải về';
                downloadBtn.onclick = () => downloadAttachment(att.fileName, att.base64Data);
                attachBox.appendChild(downloadBtn);
            } else {
                const errorSpan = document.createElement('span');
                errorSpan.style.cssText = 'font-size: 11px; color: #ef4444;';
                errorSpan.textContent = '(File đính kèm không hợp lệ)';
                attachBox.appendChild(errorSpan);
            }

            attachContainer.appendChild(attachBox);
        }

        attachSection.appendChild(attachContainer);
        detailBodyElem.appendChild(attachSection);
    } else {
        detailBodyElem.textContent = bodyText;
    }
}

function currentMailOrWarn() {
    if (!currentDetailMail) {
        showToast('Hãy chọn một thư trước!', 'error');
        return null;
    }
    return currentDetailMail;
}

function syncMailInCache(mail) {
    const cached = loadedMailsCache.find(m => m.id === mail.id);
    if (cached) {
        cached.starred = mail.starred;
        cached.seen = mail.seen;
    }
}

async function toggleStarCurrent() {
    const mail = currentMailOrWarn();
    if (!mail) return;
    const next = !mail.starred;
    const ok = await mailAction('/api/mails/flags',
        { id: mail.id, folder: mailFolder(mail), starred: String(next) },
        next ? 'Đã gắn sao!' : 'Đã bỏ sao!', 'Không thể cập nhật dấu sao!');
    if (!ok) return;
    mail.starred = next;
    syncMailInCache(mail);
    updateDetailToolbar(mail);
    if (activeMailbox === 'starred' && !next) {
        afterMailChanged();
    } else {
        applyClientFilter();
    }
}

async function markUnreadCurrent() {
    const mail = currentMailOrWarn();
    if (!mail) return;
    const ok = await mailAction('/api/mails/flags',
        { id: mail.id, folder: mailFolder(mail), seen: 'false' },
        'Đã đánh dấu chưa đọc!', 'Không thể đánh dấu chưa đọc!');
    if (!ok) return;
    mail.seen = false;
    syncMailInCache(mail);
    applyClientFilter();
}

function trashCurrent() {
    const mail = currentMailOrWarn();
    if (mail) trashMail(mail);
}

function purgeCurrent() {
    const mail = currentMailOrWarn();
    if (mail) confirmPurge(mail);
}

async function restoreCurrent() {
    const mail = currentMailOrWarn();
    if (!mail) return;
    const ok = await mailAction('/api/mails/restore', { id: mail.id },
        'Đã khôi phục thư!', 'Không thể khôi phục thư!');
    if (ok) afterMailChanged();
}

function reportSpamCurrent() {
    const mail = currentMailOrWarn();
    if (!mail) return;
    showConfirmModal(async () => {
        const ok = await mailAction('/api/mails/report-spam', { id: mail.id, folder: mailFolder(mail) },
            'Đã chuyển vào Thư rác!', 'Không thể báo thư rác!');
        if (ok) afterMailChanged();
    }, {
        title: 'Báo thư rác',
        message: 'Thư sẽ được chuyển vào Thư rác và người gửi có thể bị chặn (xem ở mục "Người đã chặn"). Tiếp tục?',
        yesLabel: 'Báo thư rác'
    });
}

async function unspamCurrent() {
    const mail = currentMailOrWarn();
    if (!mail) return;
    const ok = await mailAction('/api/mails/unspam', { id: mail.id },
        'Đã đưa thư về Hộp thư đến!', 'Không thể bỏ báo thư rác!');
    if (ok) afterMailChanged();
}

// ================= Người đã chặn =================

function openBlockedModal(event) {
    if (event && event.preventDefault) event.preventDefault();
    document.getElementById('blocked-modal').classList.remove('hidden');
    loadBlockedList();
}

function closeBlockedModal() {
    document.getElementById('blocked-modal').classList.add('hidden');
}

async function loadBlockedList() {
    const box = document.getElementById('blocked-list');
    box.textContent = '';
    box.appendChild(el('div', 'empty-list', 'Đang tải...'));
    try {
        const r = await apiCall('GET', '/api/mails/blocked');
        if (r.status === 401) return;
        if (!r.ok) {
            box.textContent = '';
            box.appendChild(el('div', 'empty-list', 'Không tải được danh sách.'));
            return;
        }
        let raw = Array.isArray(r.data) ? r.data
            : (r.data && (r.data.blocked || r.data.senders || r.data.list)) || [];
        const senders = raw
            .map(x => typeof x === 'string' ? x : (x && (x.sender || x.email)) || '')
            .filter(s => s);

        box.textContent = '';
        if (senders.length === 0) {
            box.appendChild(el('div', 'empty-list', 'Chưa chặn người gửi nào.'));
            return;
        }
        senders.forEach(sender => {
            const row = el('div', 'blocked-item');
            row.appendChild(el('span', 'blocked-addr', sender));
            const btn = el('button', 'btn btn-outline btn-sm', 'Bỏ chặn');
            btn.onclick = () => unblockSender(sender);
            row.appendChild(btn);
            box.appendChild(row);
        });
    } catch (err) {
        box.textContent = '';
        box.appendChild(el('div', 'empty-list', 'Lỗi kết nối Server!'));
    }
}

async function unblockSender(sender) {
    try {
        const r = await apiCall('DELETE', '/api/mails/blocked?sender=' + encodeURIComponent(sender));
        if (r.status === 401) return;
        if (actionOk(r)) {
            showToast((r.data && r.data.message) || 'Đã bỏ chặn!', 'success');
            loadBlockedList();
        } else {
            showToast((r.data && r.data.message) || 'Không thể bỏ chặn!', 'error');
        }
    } catch (err) {
        showToast('Lỗi kết nối Server!', 'error');
    }
}

// ================= Tiện ích chung =================

function escapeHtml(text) {
    if (!text) return '';
    return String(text)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function updateActiveNav(activeId, title) {
    document.querySelectorAll('.nav-item').forEach(nav => {
        nav.classList.remove('active');
    });

    const activeNav = document.getElementById(activeId);
    if (activeNav) activeNav.classList.add('active');

    const boxTitle = document.getElementById('box-title');
    if (boxTitle) boxTitle.textContent = title;
}

function refreshMails() {
    const searchInput = document.getElementById('mail-search-input');
    if (searchInput) searchInput.value = '';

    switchMailbox(activeMailbox);
    updateStorageUsage();
}

function formatMailDate(value) {
    if (!value) return '';

    let normalized = String(value).trim();
    normalized = normalized.replace(/\[[^\]]+\]$/, '');
    normalized = normalized.replace(
        /(\.\d{3})\d+(?=Z|[+-]\d{2}:?\d{2}$)/,
        '$1'
    );

    let date = new Date(normalized);
    if (Number.isNaN(date.getTime())) {
        date = new Date(
            normalized.replace(/^(\d{4}-\d{2}-\d{2})\s+/, '$1T')
        );
    }

    if (Number.isNaN(date.getTime())) {
        return String(value);
    }

    return new Intl.DateTimeFormat('vi-VN', {
        timeZone: 'Asia/Ho_Chi_Minh',
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hour12: false
    }).format(date);
}

async function logout() {
    stopRealtimeListener();
    const token = localStorage.getItem('authToken');

    try {
        if (token) {
            await fetch('/api/auth/logout', {
                method: 'POST',
                headers: {
                    'Authorization': 'Bearer ' + token
                }
            });
        }
    } catch (err) {
        console.error('Lỗi khi gọi API đăng xuất:', err);
    } finally {
        localStorage.removeItem('currentUser');
        localStorage.removeItem('authToken');
        window.location.href = '/';
    }
}

// ================= Trả lời / chuyển tiếp / sửa nháp =================

function extractEmail(text) {
    if (!text) return '';
    const angle = String(text).match(/<([^<>\s]+@[^<>\s]+)>/);
    if (angle) return angle[1];
    const plain = String(text).match(/[^\s<>"',;]+@[^\s<>"',;]+/);
    return plain ? plain[0] : '';
}

function stripAttachmentTags(body) {
    return String(body || '')
        .replace(/\[ATTACHMENT:[^\]\r\n]+\][\s\S]*?\[\/ATTACHMENT\]/g, '')
        .trim();
}

function buildReplySubject(subject) {
    const s = (subject || '').trim();
    return /^re\s*:/i.test(s) ? s : 'Re: ' + s;
}

function buildForwardSubject(subject) {
    const s = (subject || '').trim();
    return /^(fwd?|tr)\s*:/i.test(s) ? s : 'Fwd: ' + s;
}

function buildQuotedBody(mail) {
    const quoted = stripAttachmentTags(mail.body)
        .split(/\r?\n/)
        .map(line => '> ' + line)
        .join('\n');
    const when = formatMailDate(mail.timestamp);
    const who = mail.sender || 'người gửi';
    return `\n\n\nVào ${when}, ${who} đã viết:\n${quoted}`;
}

function buildForwardBody(mail) {
    return '\n\n---------- Thư được chuyển tiếp ----------\n'
        + `Từ: ${mail.sender || ''}\n`
        + `Ngày: ${formatMailDate(mail.timestamp)}\n`
        + `Chủ đề: ${mail.subject || ''}\n`
        + `Tới: ${mail.recipient || ''}\n\n`
        + stripAttachmentTags(mail.body);
}

function lockSubjectForReply() {
    const subj = document.getElementById('compose-subject');
    subj.readOnly = true;
    subj.title = 'Tiêu đề được máy chủ tự thêm tiền tố khi trả lời/chuyển tiếp';
}

function openReply() {
    const mail = currentDetailMail;
    if (!mail) {
        showToast('Hãy chọn một thư để trả lời!', 'error');
        return;
    }
    const replyTo = extractEmail(mail.sender);
    if (!replyTo) {
        showToast('Không xác định được địa chỉ người gửi để trả lời!', 'error');
        return;
    }

    beginCompose();
    document.getElementById('compose-to').value = replyTo;
    document.getElementById('compose-subject').value = buildReplySubject(mail.subject);
    lockSubjectForReply();

    const bodyEl = document.getElementById('compose-body');
    bodyEl.value = buildQuotedBody(mail);

    replyContext = { id: mail.id, folder: mailFolder(mail), mode: 'reply' };

    bodyEl.focus();
    bodyEl.setSelectionRange(0, 0);
    bodyEl.scrollTop = 0;
}

function openForward() {
    const mail = currentDetailMail;
    if (!mail) {
        showToast('Hãy chọn một thư để chuyển tiếp!', 'error');
        return;
    }

    beginCompose();
    document.getElementById('compose-to').value = '';
    document.getElementById('compose-subject').value = buildForwardSubject(mail.subject);
    lockSubjectForReply();
    document.getElementById('compose-body').value = buildForwardBody(mail);

    replyContext = { id: mail.id, folder: mailFolder(mail), mode: 'forward' };
    document.getElementById('compose-to').focus();
}

function openDraftEdit() {
    const mail = currentDetailMail;
    if (!mail) {
        showToast('Hãy chọn một thư nháp!', 'error');
        return;
    }

    beginCompose();
    const to = mail.recipient || '';
    const subject = mail.subject === '(Thư nháp)' ? '' : (mail.subject || '');
    const body = stripAttachmentTags(mail.body);
    document.getElementById('compose-to').value = to;
    document.getElementById('compose-subject').value = subject;
    document.getElementById('compose-body').value = body;

    composeSession.draftId = mail.id;
    composeSession.lastSig = to.trim() + '\u0000' + subject.trim() + '\u0000' + body;
    document.getElementById('compose-body').focus();
}