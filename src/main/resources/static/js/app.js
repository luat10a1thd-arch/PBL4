let selectedFiles = [];
let activeMailbox = 'inbox';
let currentSelectedMail = null;
let confirmCallback = null;

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

    loadInbox();
});

function showToast(message, type = 'success') {
    const container = document.getElementById('toast-container');
    if (!container) return;

    const toast = document.createElement('div');
    toast.className = `toast ${type}`;

    const iconClass = type === 'success'
        ? 'fa-circle-check'
        : 'fa-circle-exclamation';

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

function showConfirmModal(onConfirm) {
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

function openComposeModal() {
    document.getElementById('compose-modal').classList.remove('hidden');
}

function closeComposeModal() {
    document.getElementById('compose-modal').classList.add('hidden');
    resetComposeForm();
}

function resetComposeForm() {
    document.getElementById('compose-form').reset();
    selectedFiles = [];
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

    previewContainer.innerHTML = '';

    if (selectedFiles.length === 0) return;

    selectedFiles.forEach((file, index) => {
        const fileChip = document.createElement('div');

        fileChip.style.cssText = `
            display: inline-flex;
            align-items: center;
            gap: 6px;
            background: #e2e8f0;
            border: 1px solid #cbd5e1;
            padding: 4px 10px;
            border-radius: 16px;
            font-size: 12px;
            color: #1e293b;
            font-weight: 500;
        `;

        fileChip.innerHTML = `
            <i class="fa-solid fa-file" style="color: #2563eb;"></i>
            <span>${escapeHtml(file.name)}</span>
            <span style="color: #64748b; font-size: 11px;">
                (${formatFileSize(file.size)})
            </span>
            <i class="fa-solid fa-xmark"
               style="cursor: pointer; color: #ef4444; margin-left: 4px; font-size: 14px;"
               onclick="removeFile(${index})"
               title="Xóa file này"></i>
        `;

        previewContainer.appendChild(fileChip);
    });
}

function formatFileSize(bytes) {
    if (bytes === 0) return '0 B';

    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));

    return parseFloat((bytes / Math.pow(k, i)).toFixed(1))
        + ' ' + sizes[i];
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

    const token = localStorage.getItem('authToken');
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

    let attachmentData = '';

    if (selectedFiles.length > 0) {
        for (let i = 0; i < selectedFiles.length; i++) {
            const file = selectedFiles[i];

            try {
                const base64 = await convertFileToBase64(file);

                attachmentData +=
                    `\n[ATTACHMENT:${file.name}]${base64}[/ATTACHMENT]`;
            } catch (err) {
                showToast(`Lỗi đọc file: ${file.name}`, 'error');
                return;
            }
        }
    }

    const fullContent = bodyText + attachmentData;

    try {
        const response = await fetch('/api/mails/send', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded',
                'Authorization': 'Bearer ' + token
            },
            body:
                `to=${encodeURIComponent(recipients.join(','))}` +
                `&subject=${encodeURIComponent(subject)}` +
                `&body=${encodeURIComponent(fullContent)}`
        });

        const data = await response.json();

        if (data.success) {
            closeComposeModal();
            showToast(data.message || 'Gửi thư thành công!', 'success');
            refreshMails();
        } else {
            showToast(data.message || 'Gửi thư thất bại!', 'error');
        }
    } catch (err) {
        showToast('Gửi thư thất bại, lỗi kết nối Server!', 'error');
    }
}

async function loadInbox(event) {
    if (event) event.preventDefault();

    activeMailbox = 'inbox';
    updateActiveNav('nav-inbox', 'Hộp thư đến');
    resetEmailDetail();

    const token = localStorage.getItem('authToken');

    try {
        const response = await fetch('/api/mails/inbox', {
            method: 'GET',
            headers: {
                'Authorization': 'Bearer ' + token
            }
        });

        if (response.status === 401) {
            logout();
            return;
        }

        const mails = await response.json();
        renderEmailList(mails);
    } catch (err) {
        console.error('Không thể tải hộp thư đến');
    }
}

async function loadSentMail(event) {
    if (event) event.preventDefault();

    activeMailbox = 'sent';
    updateActiveNav('nav-sent', 'Thư đã gửi');
    resetEmailDetail();

    const token = localStorage.getItem('authToken');

    try {
        const response = await fetch('/api/mails/sent', {
            method: 'GET',
            headers: {
                'Authorization': 'Bearer ' + token
            }
        });

        if (response.status === 401) {
            logout();
            return;
        }

        const mails = await response.json();
        renderEmailList(mails);
    } catch (err) {
        console.error('Không thể tải thư đã gửi');
    }
}

function renderEmailList(mails) {
    const emailListDiv = document.getElementById('email-list');
    emailListDiv.innerHTML = '';

    if (!mails || mails.length === 0) {
        emailListDiv.innerHTML = `
            <div style="padding: 20px; text-align: center; color: #94a3b8;">
                Không có email nào.
            </div>
        `;
        return;
    }

    const sortedMails = [...mails].reverse();

    sortedMails.forEach(mail => {
        const item = document.createElement('div');

        item.className = 'email-item';
        item.style.position = 'relative';
        item.onclick = () => selectEmail(mail, item);

        item.innerHTML = `
            <div style="padding-right: 30px;">
                <div class="email-sender">${escapeHtml(activeMailbox === 'sent' ? mail.recipient : mail.sender)}</div>
                <div class="email-subject">${escapeHtml(mail.subject)}</div>
                <div class="email-date">${escapeHtml(formatMailDate(mail.timestamp))}</div>
            </div>
            <button onclick="handleDeleteMail('${escapeHtml(mail.id)}', event)" title="Xóa thư"
                    style="position: absolute; right: 12px; top: 12px; background: none; border: none; color: #ef4444; cursor: pointer; padding: 4px; font-size: 14px;">
                <i class="fa-solid fa-trash"></i>
            </button>
        `;

        emailListDiv.appendChild(item);
    });
}

function handleDeleteMail(mailId, event) {
    if (event) event.stopPropagation();

    showConfirmModal(async () => {
        const token = localStorage.getItem('authToken');

        try {
            const response = await fetch('/api/mails/delete', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/x-www-form-urlencoded',
                    'Authorization': 'Bearer ' + token
                },
                body: `id=${encodeURIComponent(mailId)}&folder=${encodeURIComponent(activeMailbox)}`
            });

            const data = await response.json();

            if (data.success) {
                showToast('Đã xóa thư thành công!', 'success');
                refreshMails();
            } else {
                showToast(data.message || 'Xóa thư thất bại!', 'error');
            }
        } catch (err) {
            showToast('Lỗi kết nối khi xóa thư!', 'error');
        }
    });
}

function resetEmailDetail() {
    currentSelectedMail = null;
    document.getElementById('no-email-selected').classList.remove('hidden');
    document.getElementById('email-detail').classList.add('hidden');
}

async function selectEmail(mail, element) {
    document.querySelectorAll('.email-item').forEach(item => {
        item.classList.remove('active');
    });

    element.classList.add('active');
    currentSelectedMail = mail;

    const token = localStorage.getItem('authToken');
    try {
        const response = await fetch(`/api/mails/detail?id=${encodeURIComponent(mail.id)}&folder=${encodeURIComponent(activeMailbox)}`, {
            method: 'GET',
            headers: {
                'Authorization': 'Bearer ' + token
            }
        });

        if (response.ok) {
            const detailMail = await response.json();
            renderEmailDetail(detailMail);
        } else {
            renderEmailDetail(mail);
        }
    } catch (err) {
        renderEmailDetail(mail);
    }
}

function renderEmailDetail(mail) {
    document.getElementById('no-email-selected').classList.add('hidden');
    document.getElementById('email-detail').classList.remove('hidden');

    document.getElementById('detail-subject').textContent = mail.subject;
    document.getElementById('detail-sender').textContent = mail.sender;
    document.getElementById('detail-receiver').textContent =
        mail.recipient || localStorage.getItem('currentUser');
    document.getElementById('detail-time').textContent =
        formatMailDate(mail.timestamp);

    const detailBodyElem = document.getElementById('detail-body');
    detailBodyElem.innerHTML = '';

    let bodyText = mail.body || '';

    if (bodyText.includes('[ATTACHMENT:')) {
        const parts = bodyText.split('[ATTACHMENT:');
        const textContent = parts[0];

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

        const DATA_URL_REGEX = /^data:[a-z0-9.+-]+\/[a-z0-9.+-]+;base64,[A-Za-z0-9+/=]+$/i;

        for (let i = 1; i < parts.length; i++) {
            const attachParts = parts[i].split(']');
            const fileName = attachParts[0];

            const base64Data = (attachParts[1] || '')
                .replace('[/ATTACHMENT]', '')
                .trim();

            const attachBox = document.createElement('div');
            attachBox.style.cssText = 'margin-top: 10px; padding: 10px 14px; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; display: inline-block; margin-right: 10px;';

            const nameDiv = document.createElement('div');
            nameDiv.style.cssText = 'font-weight: 600; color: #334155; font-size: 13px; margin-bottom: 4px;';
            nameDiv.innerHTML = '<i class="fa-solid fa-paperclip"></i> ';
            
            const nameSpan = document.createElement('span');
            nameSpan.textContent = fileName;
            nameDiv.appendChild(nameSpan);
            attachBox.appendChild(nameDiv);

            if (DATA_URL_REGEX.test(base64Data)) {
                const downloadLink = document.createElement('a');
                downloadLink.href = base64Data;
                downloadLink.download = fileName;
                downloadLink.className = 'btn btn-outline';
                downloadLink.style.cssText = 'font-size: 11px; padding: 3px 8px; text-decoration: none; color: #2563eb; display: inline-flex; align-items: center; gap: 4px;';
                downloadLink.innerHTML = '<i class="fa-solid fa-download"></i> Tải về';
                attachBox.appendChild(downloadLink);
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
    if (activeMailbox === 'sent') {
        loadSentMail();
    } else {
        loadInbox();
    }
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