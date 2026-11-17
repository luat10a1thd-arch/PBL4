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

document.addEventListener('DOMContentLoaded', async () => {
    // Phiên nằm trong cookie HttpOnly: hỏi server thay vì đọc token ở localStorage.
    // Nếu 401, apiFetch đã dọn localStorage và chuyển về trang đăng nhập.
    const session = await checkSession();
    if (session.status === 401) return;
    if (!session.ok && !localStorage.getItem('currentUser')) {
        window.location.href = '/';
        return;
    }

    const currentUser = localStorage.getItem('currentUser') || '';

    const userEmailSpan = document.getElementById('current-user-email');
    if (userEmailSpan && currentUser) {
        userEmailSpan.textContent = currentUser;
    }

    initComposeAutosave();
    loadInbox();
    updateStorageUsage();
    startRealtimeListener();
});

// ================= Gọi API (dùng chung, phiên bằng cookie + CSRF qua apiFetch) =================

function formEncode(params) {
    return Object.keys(params || {})
        .filter(k => params[k] !== undefined && params[k] !== null)
        .map(k => encodeURIComponent(k) + '=' + encodeURIComponent(params[k]))
        .join('&');
}

async function apiCall(method, url, params) {
    const options = { method: method };
    if (params && method !== 'GET') {
        options.headers = { 'Content-Type': 'application/x-www-form-urlencoded' };
        options.body = formEncode(params);
    }
    // 401: apiFetch đã dọn phiên và chuyển về trang đăng nhập
    const response = await apiFetch(url, options);
    if (response.status === 401) {
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

    const generation = ++sseGeneration;

    let ticket = null;
    try {
        const res = await apiFetch('/api/mails/stream-ticket', { method: 'POST' });
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
        if (activeMailbox === 'spam') main.appendChild(buildSpamListInfo(mail));
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
    updateSpamInfo(mail);

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
    const senderAddr = extractEmail(mail.sender);
    const btn = document.getElementById('unspam-btn');
    setBusy(btn, true, 'Đang xử lý...');
    let ok = false;
    try {
        ok = await mailAction('/api/mails/unspam', { id: mail.id },
            'Đã đưa thư về Hộp thư đến!', 'Không thể bỏ báo thư rác!');
    } finally {
        setBusy(btn, false);
    }
    if (!ok) return;
    afterMailChanged();
    if (senderAddr) await trustSenderAfterUnspam(senderAddr);
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

    try {
        await apiFetch('/api/auth/logout', { method: 'POST', noAuthRedirect: true });
    } catch (err) {
        console.error('Lỗi khi gọi API đăng xuất:', err);
    } finally {
        localStorage.removeItem('currentUser');
        localStorage.removeItem('userRole');
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

// ================= Cài đặt thư: Chuyển tiếp tự động + Chống thư rác =================
// API: /api/settings/* (JSON, cookie phiên + CSRF qua apiFetch).
// Mật khẩu và mã xác minh chỉ nằm trong ô nhập, không lưu vào localStorage/sessionStorage.
// Mọi dữ liệu từ server được đưa vào giao diện bằng textContent (không chèn HTML thô).

const MS_EMAIL_RE = /^[^\s@<>"',;]+@[^\s@<>"',;]+\.[^\s@<>"',;]+$/;
const MS_LEVELS = ['LOW', 'MEDIUM', 'HIGH'];
let msFwdRequestId = 0;
let msSpamRequestId = 0;
let msReasonRequestId = 0;
let msConfirmState = null;   // { run(password) -> r, onSuccess(), busy }

async function settingsApi(method, path, body) {
    const options = { method: method };
    if (body !== undefined && body !== null) {
        options.headers = { 'Content-Type': 'application/json; charset=UTF-8' };
        options.body = JSON.stringify(body);
    }
    // 401: apiFetch đã dọn phiên và chuyển về trang đăng nhập
    const response = await apiFetch('/api/settings' + path, options);
    if (response.status === 401) return { ok: false, status: 401, data: null };
    let data = null;
    try { data = await response.json(); } catch (e) { data = null; }
    return { ok: response.ok, status: response.status, data: data };
}

function settingsOk(r) {
    return !!(r && r.ok && !(r.data && r.data.success === false));
}

function settingsMessage(r, fallback) {
    const d = r && r.data;
    if (d && typeof d === 'object') {
        const m = d.message || d.error;
        if (typeof m === 'string' && m) return m;
    }
    return fallback;
}

// Nút đang tải: khóa nút + biểu tượng quay; trả lại nguyên trạng khi xong
function setBusy(btn, busy, label) {
    if (!btn) return;
    if (busy) {
        if (!btn._idleNodes) btn._idleNodes = Array.from(btn.childNodes);
        btn.disabled = true;
        btn.replaceChildren(el('i', 'fa-solid fa-spinner fa-spin'),
            document.createTextNode(' ' + (label || 'Đang xử lý...')));
    } else {
        if (btn._idleNodes) {
            btn.replaceChildren(...btn._idleNodes);
            btn._idleNodes = null;
        }
        btn.disabled = false;
    }
}

function setFieldValue(id, value) {
    const node = document.getElementById(id);
    if (node) node.value = value;
}

function openMailSettings(event) {
    if (event && event.preventDefault) event.preventDefault();
    document.getElementById('mail-settings-modal').classList.remove('hidden');
    switchMailSettingsTab('forward');
}

function closeMailSettings() {
    closeMsConfirm();
    msFwdRequestId++;
    msSpamRequestId++;
    ['ms-fwd-pw', 'ms-fwd-target', 'ms-fwd-from', 'ms-fwd-subject', 'ms-allow-input']
        .forEach(id => setFieldValue(id, ''));
    document.getElementById('ms-fwd-copy-keep').checked = true;
    clearInlineMessage('ms-fwd-message');
    clearInlineMessage('ms-spam-message');
    document.getElementById('ms-fwd-list').textContent = '';     // xóa luôn các ô nhập mã xác minh
    document.getElementById('mail-settings-modal').classList.add('hidden');
}

function switchMailSettingsTab(tab) {
    clearInlineMessage('ms-fwd-message');
    clearInlineMessage('ms-spam-message');
    ['forward', 'spam'].forEach(name => {
        document.getElementById('ms-tab-' + name).classList.toggle('active', name === tab);
        document.getElementById('ms-pane-' + name).classList.toggle('hidden', name !== tab);
    });
    if (tab === 'forward') loadForwardRules();
    else loadSpamSettings(false);
}

// ---------- Chuyển tiếp tự động ----------

function normalizeRules(d) {
    const list = Array.isArray(d) ? d : (d && (d.rules || d.list || d.forwards)) || [];
    return Array.isArray(list) ? list.filter(x => x && typeof x === 'object' && x.id) : [];
}

function ruleStatus(rule) {
    if (!rule.verified) return { label: 'Chờ xác minh', cls: 'pending' };
    if (rule.enabled) return { label: 'Đang bật', cls: 'on' };
    return { label: 'Tắt', cls: 'off' };
}

function describeConditions(rule) {
    const parts = [];
    if (rule.fromContains) parts.push('người gửi chứa "' + rule.fromContains + '"');
    if (rule.subjectContains) parts.push('chủ đề chứa "' + rule.subjectContains + '"');
    return parts.length ? parts.join(' và ') : 'mọi thư đến';
}

async function loadForwardRules() {
    const box = document.getElementById('ms-fwd-list');
    const requestId = ++msFwdRequestId;
    box.textContent = '';
    box.appendChild(el('div', 'empty-list', 'Đang tải...'));
    try {
        const r = await settingsApi('GET', '/forward');
        if (r.status === 401 || requestId !== msFwdRequestId) return;
        if (!settingsOk(r)) {
            box.textContent = '';
            box.appendChild(el('div', 'empty-list', 'Không tải được danh sách luật.'));
            setInlineMessage('ms-fwd-message', settingsMessage(r, 'Không tải được danh sách luật!'), false);
            return;
        }
        renderForwardRules(normalizeRules(r.data));
    } catch (err) {
        if (requestId !== msFwdRequestId) return;
        box.textContent = '';
        box.appendChild(el('div', 'empty-list', 'Không tải được danh sách luật.'));
        setInlineMessage('ms-fwd-message', 'Lỗi kết nối Server!', false);
    }
}

function renderForwardRules(rules) {
    const box = document.getElementById('ms-fwd-list');
    box.textContent = '';
    if (rules.length === 0) {
        box.appendChild(el('div', 'empty-list', 'Chưa có luật chuyển tiếp nào.'));
        return;
    }
    rules.forEach(rule => box.appendChild(buildRuleCard(rule)));
}

function buildRuleCard(rule) {
    const card = el('div', 'ms-card');

    const head = el('div', 'ms-card-head');
    head.appendChild(el('span', 'ms-card-target', rule.target || ''));
    const st = ruleStatus(rule);
    head.appendChild(el('span', 'ms-badge ' + st.cls, st.label));
    card.appendChild(head);

    card.appendChild(el('div', 'ms-meta', 'Điều kiện: ' + describeConditions(rule)));
    card.appendChild(el('div', 'ms-meta',
        rule.keepCopy ? 'Giữ bản sao trong Hộp thư đến' : 'Thư gốc được chuyển vào Thùng rác'));

    if (!rule.verified) {
        const form = el('form', 'ms-verify-row');
        form.autocomplete = 'off';
        form.appendChild(el('div', 'ms-meta',
            'Mã xác minh đã được gửi tới địa chỉ đích. Nhập mã để bật luật này. Chưa nhận được mã? Hãy xóa luật rồi thêm lại để gửi mã mới.'));
        const line = el('div', 'ms-verify-input-line');
        const input = el('input', 'form-control');
        input.type = 'text';
        input.inputMode = 'numeric';
        input.autocomplete = 'off';
        input.maxLength = 16;
        input.placeholder = 'Mã xác minh';
        input.setAttribute('aria-label', 'Mã xác minh');
        const btn = el('button', 'btn btn-success btn-sm', 'Xác minh');
        btn.type = 'submit';
        line.appendChild(input);
        line.appendChild(btn);
        form.appendChild(line);
        form.onsubmit = (e) => {
            e.preventDefault();
            submitVerifyRule(rule, input, btn);
        };
        card.appendChild(form);
    }

    const actions = el('div', 'ms-actions');
    if (rule.verified) {
        const toggle = el('button', 'btn btn-outline btn-sm', rule.enabled ? 'Tắt' : 'Bật');
        toggle.type = 'button';
        toggle.onclick = () => askToggleRule(rule);
        actions.appendChild(toggle);
    }
    const del = el('button', 'btn btn-outline btn-sm ms-del-btn', 'Xóa');
    del.type = 'button';
    del.onclick = () => askDeleteRule(rule);
    actions.appendChild(del);
    card.appendChild(actions);
    return card;
}

async function submitVerifyRule(rule, input, btn) {
    const code = input.value.trim();
    if (!code) {
        setInlineMessage('ms-fwd-message', 'Vui lòng nhập mã xác minh.', false);
        return;
    }
    clearInlineMessage('ms-fwd-message');
    setBusy(btn, true, 'Đang xác minh...');
    try {
        const r = await settingsApi('POST', '/forward/verify', { id: rule.id, code: code });
        if (r.status === 401) return;
        if (settingsOk(r) && !(r.data && r.data.verified === false)) {
            input.value = '';
            setInlineMessage('ms-fwd-message', 'Đã xác minh địa chỉ nhận chuyển tiếp.', true);
            loadForwardRules();
        } else {
            setInlineMessage('ms-fwd-message', settingsMessage(r, 'Mã xác minh không đúng hoặc đã hết hạn!'), false);
        }
    } catch (err) {
        setInlineMessage('ms-fwd-message', 'Lỗi kết nối Server!', false);
    } finally {
        setBusy(btn, false);
    }
}

async function handleAddForwardRule(event) {
    event.preventDefault();
    const submit = document.getElementById('ms-fwd-submit');
    if (submit.disabled) return;

    const target = document.getElementById('ms-fwd-target').value.trim();
    const fromContains = document.getElementById('ms-fwd-from').value.trim();
    const subjectContains = document.getElementById('ms-fwd-subject').value.trim();
    const keepCopy = document.getElementById('ms-fwd-copy-keep').checked;
    const password = document.getElementById('ms-fwd-pw').value;

    if (!MS_EMAIL_RE.test(target) || target.length > 254) {
        setInlineMessage('ms-fwd-message', 'Địa chỉ nhận chuyển tiếp không hợp lệ.', false);
        return;
    }
    if (!password) {
        setInlineMessage('ms-fwd-message', 'Vui lòng nhập mật khẩu hiện tại.', false);
        return;
    }

    clearInlineMessage('ms-fwd-message');
    setBusy(submit, true, 'Đang thêm...');
    try {
        const r = await settingsApi('POST', '/forward', {
            target: target,
            keepCopy: keepCopy,
            fromContains: fromContains,
            subjectContains: subjectContains,
            currentPassword: password
        });
        if (r.status === 401) return;
        if (settingsOk(r)) {
            ['ms-fwd-target', 'ms-fwd-from', 'ms-fwd-subject', 'ms-fwd-pw'].forEach(id => setFieldValue(id, ''));
            document.getElementById('ms-fwd-copy-keep').checked = true;
            setInlineMessage('ms-fwd-message',
                'Đã thêm luật. Nếu địa chỉ nằm ngoài hệ thống, hãy nhập mã xác minh vừa được gửi tới địa chỉ đó vào luật bên trên.', true);
            loadForwardRules();
        } else {
            setInlineMessage('ms-fwd-message', settingsMessage(r, 'Không thể thêm luật chuyển tiếp!'), false);
        }
    } catch (err) {
        setInlineMessage('ms-fwd-message', 'Lỗi kết nối Server!', false);
    } finally {
        setBusy(submit, false);
    }
}

function askToggleRule(rule) {
    const enable = !rule.enabled;
    openMsConfirm({
        title: enable ? 'Bật luật chuyển tiếp' : 'Tắt luật chuyển tiếp',
        message: enable
            ? 'Thư phù hợp điều kiện sẽ được chuyển tiếp tới ' + rule.target + '.'
            : 'Thư sẽ không còn được chuyển tiếp tới ' + rule.target + '.',
        yesLabel: enable ? 'Bật luật' : 'Tắt luật',
        danger: false,
        run: (pw) => settingsApi('POST', '/forward/update',
            { id: rule.id, enabled: enable, currentPassword: pw }),
        errorText: 'Không thể cập nhật luật!',
        onSuccess: () => {
            setInlineMessage('ms-fwd-message', enable ? 'Đã bật luật chuyển tiếp.' : 'Đã tắt luật chuyển tiếp.', true);
            loadForwardRules();
        }
    });
}

function askDeleteRule(rule) {
    openMsConfirm({
        title: 'Xóa luật chuyển tiếp',
        message: 'Luật chuyển tiếp tới ' + rule.target + ' sẽ bị xóa. Bạn có chắc chắn không?',
        yesLabel: 'Xóa luật',
        danger: true,
        run: (pw) => settingsApi('DELETE',
            '/forward?id=' + encodeURIComponent(rule.id) + '&currentPassword=' + encodeURIComponent(pw)),
        errorText: 'Không thể xóa luật!',
        onSuccess: () => {
            setInlineMessage('ms-fwd-message', 'Đã xóa luật chuyển tiếp.', true);
            loadForwardRules();
        }
    });
}

// Hộp xác nhận có ô mật khẩu (bật/tắt/xóa luật)
function openMsConfirm(opts) {
    msConfirmState = { run: opts.run, onSuccess: opts.onSuccess, errorText: opts.errorText, busy: false };
    setText('ms-confirm-title', opts.title);
    setText('ms-confirm-text', opts.message);
    const yes = document.getElementById('ms-confirm-yes');
    yes.className = 'btn ' + (opts.danger ? 'btn-danger' : 'btn-primary');
    setText('ms-confirm-yes', opts.yesLabel || 'Xác nhận');
    setFieldValue('ms-confirm-pw', '');
    clearInlineMessage('ms-confirm-message');
    document.getElementById('ms-confirm-modal').classList.remove('hidden');
    document.getElementById('ms-confirm-pw').focus();
}

function closeMsConfirm() {
    if (msConfirmState && msConfirmState.busy) return;
    msConfirmState = null;
    setFieldValue('ms-confirm-pw', '');
    clearInlineMessage('ms-confirm-message');
    document.getElementById('ms-confirm-modal').classList.add('hidden');
}

async function submitMsConfirm(event) {
    event.preventDefault();
    const state = msConfirmState;
    if (!state || state.busy) return;
    const password = document.getElementById('ms-confirm-pw').value;
    if (!password) {
        setInlineMessage('ms-confirm-message', 'Vui lòng nhập mật khẩu hiện tại.', false);
        return;
    }
    const yes = document.getElementById('ms-confirm-yes');
    state.busy = true;
    clearInlineMessage('ms-confirm-message');
    setBusy(yes, true, 'Đang xử lý...');
    let success = false;
    try {
        const r = await state.run(password);
        if (r.status === 401) return;
        if (settingsOk(r)) {
            success = true;
        } else {
            setInlineMessage('ms-confirm-message', settingsMessage(r, state.errorText || 'Thao tác thất bại!'), false);
        }
    } catch (err) {
        setInlineMessage('ms-confirm-message', 'Lỗi kết nối Server!', false);
    } finally {
        state.busy = false;
        setBusy(yes, false);
    }
    if (success) {
        const done = state.onSuccess;
        closeMsConfirm();
        if (typeof done === 'function') done();
    }
}

// ---------- Chống thư rác ----------

function msSpamControls() {
    return [document.getElementById('ms-spam-enabled'), document.getElementById('ms-spam-learning')]
        .concat(Array.from(document.querySelectorAll('#ms-level-grid input[type="radio"]')));
}

function normalizeAllowlist(d) {
    const raw = Array.isArray(d) ? d : [];
    return raw
        .map(x => typeof x === 'string' ? x : (x && (x.address || x.email)) || '')
        .filter(s => s);
}

// silent = true: không hiện "Đang tải" và không xóa thông báo (dùng khi đồng bộ lại sau thao tác)
async function loadSpamSettings(silent) {
    const requestId = ++msSpamRequestId;
    const loading = document.getElementById('ms-spam-loading');
    const body = document.getElementById('ms-spam-body');
    if (!silent) {
        loading.textContent = 'Đang tải...';
        loading.classList.remove('hidden');
        body.classList.add('hidden');
    }
    try {
        const r = await settingsApi('GET', '/spam');
        if (r.status === 401 || requestId !== msSpamRequestId) return;
        if (!settingsOk(r) || !r.data || typeof r.data !== 'object') {
            if (!silent) loading.textContent = 'Không tải được cài đặt chống thư rác.';
            setInlineMessage('ms-spam-message', settingsMessage(r, 'Không tải được cài đặt chống thư rác!'), false);
            return;
        }
        renderSpamSettings(r.data);
        loading.classList.add('hidden');
        body.classList.remove('hidden');
    } catch (err) {
        if (requestId !== msSpamRequestId) return;
        if (!silent) loading.textContent = 'Không tải được cài đặt chống thư rác.';
        setInlineMessage('ms-spam-message', 'Lỗi kết nối Server!', false);
    }
}

function renderSpamSettings(d) {
    document.getElementById('ms-spam-enabled').checked = !!d.enabled;
    document.getElementById('ms-spam-learning').checked = !!d.learning;

    let level = String(d.level || 'MEDIUM').toUpperCase();
    if (MS_LEVELS.indexOf(level) < 0) level = 'MEDIUM';
    MS_LEVELS.forEach(name => {
        const label = document.getElementById('ms-level-' + name);
        const radio = label.querySelector('input');
        radio.checked = (name === level);
        label.classList.toggle('selected', name === level);
    });

    renderAllowlist(normalizeAllowlist(d.allowlist));
    renderSpamStats(d.stats);
}

function renderAllowlist(list) {
    const box = document.getElementById('ms-allow-list');
    box.textContent = '';
    if (list.length === 0) {
        box.appendChild(el('div', 'empty-list', 'Chưa có người gửi tin cậy nào.'));
        return;
    }
    list.forEach(addr => {
        const row = el('div', 'blocked-item');
        row.appendChild(el('span', 'blocked-addr', addr));
        const btn = el('button', 'btn btn-outline btn-sm', 'Xóa');
        btn.type = 'button';
        btn.onclick = () => removeAllow(addr, btn);
        row.appendChild(btn);
        box.appendChild(row);
    });
}

function renderSpamStats(stats) {
    const box = document.getElementById('ms-stats');
    box.textContent = '';
    const s = (stats && typeof stats === 'object') ? stats : {};
    const num = (k) => Number.isFinite(Number(s[k])) && s[k] !== null && s[k] !== undefined
        ? Number(s[k]).toLocaleString('vi-VN') : '—';
    [
        ['spam', 'Thư rác đã chặn', true],
        ['total', 'Tổng thư đã quét', false],
        ['ham', 'Thư thường', false],
        ['learnedSpam', 'Đã học là rác', false],
        ['learnedHam', 'Đã học là thường', false]
    ].forEach(item => {
        const cell = el('div', 'ms-stat' + (item[2] ? ' main' : ''));
        cell.appendChild(el('div', 'ms-stat-value', num(item[0])));
        cell.appendChild(el('div', 'ms-stat-label', item[1]));
        box.appendChild(cell);
    });
}

async function saveSpamSetting(patch, okText) {
    const controls = msSpamControls();
    controls.forEach(c => { c.disabled = true; });
    clearInlineMessage('ms-spam-message');
    let success = false;
    try {
        const r = await settingsApi('POST', '/spam', patch);
        if (r.status === 401) return;
        if (settingsOk(r)) {
            success = true;
            setInlineMessage('ms-spam-message', okText, true);
        } else {
            setInlineMessage('ms-spam-message', settingsMessage(r, 'Không thể lưu cài đặt!'), false);
        }
    } catch (err) {
        setInlineMessage('ms-spam-message', 'Lỗi kết nối Server!', false);
    } finally {
        controls.forEach(c => { c.disabled = false; });
    }
    if (!success) loadSpamSettings(true);   // trả giao diện về đúng trạng thái trên server
    else syncLevelHighlight();
}

function syncLevelHighlight() {
    MS_LEVELS.forEach(name => {
        const label = document.getElementById('ms-level-' + name);
        label.classList.toggle('selected', label.querySelector('input').checked);
    });
}

function onSpamEnabledChange(input) {
    saveSpamSetting({ enabled: !!input.checked },
        input.checked ? 'Đã bật chống thư rác.' : 'Đã tắt chống thư rác.');
}

function onSpamLearningChange(input) {
    saveSpamSetting({ learning: !!input.checked },
        input.checked ? 'Đã bật học từ thư bạn đánh dấu.' : 'Đã tắt học từ thư bạn đánh dấu.');
}

function onSpamLevelChange(input) {
    if (!input.checked) return;
    syncLevelHighlight();
    saveSpamSetting({ level: input.value }, 'Đã đổi độ nhạy.');
}

async function handleAddAllow(event) {
    event.preventDefault();
    const btn = document.getElementById('ms-allow-add-btn');
    if (btn.disabled) return;
    const input = document.getElementById('ms-allow-input');
    const address = input.value.trim().toLowerCase();
    if (!MS_EMAIL_RE.test(address) || address.length > 254) {
        setInlineMessage('ms-spam-message', 'Địa chỉ người gửi không hợp lệ.', false);
        return;
    }
    clearInlineMessage('ms-spam-message');
    setBusy(btn, true, 'Đang thêm...');
    try {
        const r = await settingsApi('POST', '/spam/allow', { address: address });
        if (r.status === 401) return;
        if (settingsOk(r)) {
            input.value = '';
            setInlineMessage('ms-spam-message', 'Đã thêm vào danh sách người gửi tin cậy.', true);
            loadSpamSettings(true);
        } else {
            setInlineMessage('ms-spam-message', settingsMessage(r, 'Không thể thêm người gửi tin cậy!'), false);
        }
    } catch (err) {
        setInlineMessage('ms-spam-message', 'Lỗi kết nối Server!', false);
    } finally {
        setBusy(btn, false);
    }
}

async function removeAllow(address, btn) {
    clearInlineMessage('ms-spam-message');
    setBusy(btn, true, 'Đang xóa...');
    try {
        const r = await settingsApi('DELETE', '/spam/allow?address=' + encodeURIComponent(address));
        if (r.status === 401) return;
        if (settingsOk(r)) {
            setInlineMessage('ms-spam-message', 'Đã xóa khỏi danh sách người gửi tin cậy.', true);
            loadSpamSettings(true);
        } else {
            setInlineMessage('ms-spam-message', settingsMessage(r, 'Không thể xóa người gửi tin cậy!'), false);
        }
    } catch (err) {
        setInlineMessage('ms-spam-message', 'Lỗi kết nối Server!', false);
    } finally {
        setBusy(btn, false);
    }
}

// ---------- Điểm + lý do trong mục Thư rác ----------

function formatSpamScore(value) {
    if (value === undefined || value === null || value === '') return '';
    const n = Number(value);
    return Number.isFinite(n) ? String(Math.round(n * 100) / 100) : '';
}

// Dòng nhãn điểm + nút "Vì sao là thư rác?" cho từng thư trong danh sách (chỉ gọi ở mục Thư rác)
function buildSpamListInfo(mail) {
    const row = el('div', 'spam-list-info');
    const score = formatSpamScore(mail.spamScore);
    if (score) row.appendChild(el('span', 'spam-score-badge', 'Điểm ' + score));
    const btn = el('button', 'spam-why-link', 'Vì sao là thư rác?');
    btn.type = 'button';
    btn.onclick = (e) => {
        e.stopPropagation();
        openSpamReasons(mail);
    };
    row.appendChild(btn);
    return row;
}

// Khung thông tin ở chi tiết thư: CHỈ hiện khi đang xem mục Thư rác.
// Thư thường (kể cả có điểm cao nhưng dưới ngưỡng) không hiển thị gì.
function updateSpamInfo(mail) {
    const box = document.getElementById('spam-info');
    if (!box) return;
    if (activeMailbox !== 'spam') {
        box.classList.add('hidden');
        return;
    }
    const score = formatSpamScore(mail && mail.spamScore);
    setText('spam-info-score', score ? 'Điểm thư rác: ' + score : 'Thư này nằm trong Thư rác');
    box.classList.remove('hidden');
}

function openSpamReasonsCurrent() {
    if (currentDetailMail) openSpamReasons(currentDetailMail);
}

async function openSpamReasons(mail) {
    if (!mail) return;
    const requestId = ++msReasonRequestId;
    const body = document.getElementById('spam-reason-body');
    const scoreBox = document.getElementById('spam-reason-score');
    body.textContent = '';
    scoreBox.classList.add('hidden');
    document.getElementById('spam-reason-modal').classList.remove('hidden');

    let score = mail.spamScore;
    let reasons = mail.spamReasons;
    if (!Array.isArray(reasons)) {
        body.appendChild(el('div', 'empty-list', 'Đang tải...'));
        try {
            const r = await apiCall('GET',
                `/api/mails/detail?id=${encodeURIComponent(mail.id)}&folder=${encodeURIComponent(mailFolder(mail))}`);
            if (r.status === 401 || requestId !== msReasonRequestId) return;
            if (r.ok && r.data && typeof r.data === 'object') {
                if (score === undefined || score === null) score = r.data.spamScore;
                reasons = r.data.spamReasons;
            } else {
                body.textContent = '';
                body.appendChild(el('div', 'empty-list', 'Không tải được lý do.'));
                return;
            }
        } catch (err) {
            if (requestId !== msReasonRequestId) return;
            body.textContent = '';
            body.appendChild(el('div', 'empty-list', 'Lỗi kết nối Server!'));
            return;
        }
    }
    if (requestId !== msReasonRequestId) return;

    body.textContent = '';
    const scoreText = formatSpamScore(score);
    if (scoreText) {
        scoreBox.textContent = 'Điểm thư rác: ' + scoreText;
        scoreBox.classList.remove('hidden');
    }
    const list = Array.isArray(reasons) ? reasons.filter(x => typeof x === 'string' && x) : [];
    if (list.length === 0) {
        body.appendChild(el('div', 'empty-list', 'Hệ thống không ghi lại lý do cụ thể cho thư này.'));
        return;
    }
    const ul = el('ul', 'spam-reason-list');
    list.forEach(reason => ul.appendChild(el('li', null, reason)));
    body.appendChild(ul);
}

function closeSpamReasons() {
    msReasonRequestId++;
    document.getElementById('spam-reason-modal').classList.add('hidden');
}

// Sau khi bấm "Không phải thư rác": thêm người gửi vào danh sách tin cậy
async function trustSenderAfterUnspam(address) {
    try {
        const r = await settingsApi('POST', '/spam/allow', { address: address });
        if (r.status === 401) return;
        if (settingsOk(r)) {
            showToast('Đã thêm người gửi vào danh sách tin cậy', 'success');
        } else {
            showToast('Đã đưa thư về Hộp thư đến nhưng chưa thêm được người gửi vào danh sách tin cậy.', 'error');
        }
    } catch (err) {
        showToast('Đã đưa thư về Hộp thư đến nhưng chưa thêm được người gửi vào danh sách tin cậy.', 'error');
    }
}