let selectedFiles = [];

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

async function handleSendMail(event) {
    event.preventDefault();
    
    // --- [ĐÃ SỬA] CHỐNG DOUBLE-CLICK (Khóa nút gửi ngay khi bấm) ---
    const submitBtn = event.target.querySelector('button[type="submit"]') || document.getElementById('btn-send-mail');
    if (submitBtn) {
        submitBtn.disabled = true;
        submitBtn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> Đang gửi...';
    }

    const token = localStorage.getItem('authToken');
    const to = document.getElementById('compose-to').value;
    const subject = document.getElementById('compose-subject').value;
    const bodyText = document.getElementById('compose-body').value;

    let attachmentData = '';

    if (selectedFiles.length > 0) {
        for (let i = 0; i < selectedFiles.length; i++) {
            const file = selectedFiles[i];
            try {
                const base64 = await convertFileToBase64(file);
                attachmentData += `\n[ATTACHMENT:${file.name}]${base64}[/ATTACHMENT]`;
            } catch (err) {
                showToast(`Lỗi đọc file: ${file.name}`, 'error');
                // Phục hồi lại nút nếu lỗi
                if (submitBtn) {
                    submitBtn.disabled = false;
                    submitBtn.innerHTML = '<i class="fa-solid fa-paper-plane"></i> Gửi thư';
                }
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
            body: `to=${encodeURIComponent(to)}&subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(fullContent)}`
        });

        const data = await response.json();
        
        if (data.success) {
            closeComposeModal();
            showToast(data.message || 'Gửi thư thành công!', 'success');
            loadInbox();
        } else {
            showToast(data.message || 'Gửi thư thất bại!', 'error');
        }
    } catch (err) {
        showToast('Gửi thư thất bại, lỗi kết nối Server!', 'error');
    } finally {
        // --- [ĐÃ SỬA] MỞ KHÓA NÚT SAU KHI GỬI XONG ---
        if (submitBtn) {
            submitBtn.disabled = false;
            submitBtn.innerHTML = '<i class="fa-solid fa-paper-plane"></i> Gửi thư';
        }
    }
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
            <span style="color: #64748b; font-size: 11px;">(${formatFileSize(file.size)})</span>
            <i class="fa-solid fa-xmark" style="cursor: pointer; color: #ef4444; margin-left: 4px; font-size: 14px;" onclick="removeFile(${index})" title="Xóa file này"></i>
        `;

        previewContainer.appendChild(fileChip);
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
    const token = localStorage.getItem('authToken');
    const to = document.getElementById('compose-to').value;
    const subject = document.getElementById('compose-subject').value;
    const bodyText = document.getElementById('compose-body').value;

    let attachmentData = '';

    if (selectedFiles.length > 0) {
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
    }

    const fullContent = bodyText + attachmentData;

    try {
        const response = await fetch('/api/mails/send', {
            method: 'POST',
            headers: { 
                'Content-Type': 'application/x-www-form-urlencoded',
                'Authorization': 'Bearer ' + token
            },
            body: `to=${encodeURIComponent(to)}&subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(fullContent)}`
        });

        const data = await response.json();
        
        if (data.success) {
            closeComposeModal();
            showToast(data.message || 'Gửi thư thành công!', 'success');
            loadInbox();
        } else {
            showToast(data.message || 'Gửi thư thất bại!', 'error');
        }
    } catch (err) {
        showToast('Gửi thư thất bại, lỗi kết nối Server!', 'error');
    }
}

async function loadInbox(event) {
    if (event) event.preventDefault();
    updateActiveNav('nav-inbox', 'Hộp thư đến');

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
    updateActiveNav('nav-sent', 'Thư đã gửi');

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
        emailListDiv.innerHTML = '<div style="padding: 20px; text-align: center; color: #94a3b8;">Không có email nào.</div>';
        return;
    }

    const sortedMails = [...mails].reverse();

    sortedMails.forEach(mail => {
        const item = document.createElement('div');
        item.className = 'email-item';
        item.onclick = () => selectEmail(mail, item);

        item.innerHTML = `
            <div class="email-sender">${escapeHtml(mail.sender)}</div>
            <div class="email-subject">${escapeHtml(mail.subject)}</div>
            <div class="email-date">${mail.timestamp || ''}</div>
        `;
        emailListDiv.appendChild(item);
    });
}

function selectEmail(mail, element) {
    document.querySelectorAll('.email-item').forEach(item => item.classList.remove('active'));
    element.classList.add('active');

    document.getElementById('no-email-selected').classList.add('hidden');
    document.getElementById('email-detail').classList.remove('hidden');

    document.getElementById('detail-subject').textContent = mail.subject;
    document.getElementById('detail-sender').textContent = mail.sender;
    document.getElementById('detail-receiver').textContent = mail.recipient || localStorage.getItem('currentUser');
    document.getElementById('detail-time').textContent = mail.timestamp || '';
    
    const detailBodyElem = document.getElementById('detail-body');
    let bodyText = mail.body || '';

    if (bodyText.includes('[ATTACHMENT:')) {
        const parts = bodyText.split('[ATTACHMENT:');
        const textContent = parts[0];
        let attachmentsHTML = '';

        for (let i = 1; i < parts.length; i++) {
            const attachParts = parts[i].split(']');
            const fileName = attachParts[0];
            const base64Data = attachParts[1].replace('[/ATTACHMENT]', '').trim();

            attachmentsHTML += `
                <div style="margin-top: 10px; padding: 10px 14px; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; display: inline-block; margin-right: 10px;">
                    <div style="font-weight: 600; color: #334155; font-size: 13px; margin-bottom: 4px;">
                        <i class="fa-solid fa-paperclip"></i> ${escapeHtml(fileName)}
                    </div>
                    <a href="${base64Data}" download="${escapeHtml(fileName)}" class="btn btn-outline" style="font-size: 11px; padding: 3px 8px; text-decoration: none; color: #2563eb; display: inline-flex; align-items: center; gap: 4px;">
                        <i class="fa-solid fa-download"></i> Tải về
                    </a>
                </div>`;
        }

        detailBodyElem.innerHTML = `
            <div style="white-space: pre-wrap;">${escapeHtml(textContent)}</div>
            <div style="margin-top: 25px; border-top: 1px dashed #cbd5e1; padding-top: 15px;">
                <strong style="color: #475569; font-size: 13px;">📎 Danh sách file đính kèm:</strong><br/>
                <div style="margin-top: 8px;">${attachmentsHTML}</div>
            </div>`;
    } else {
        detailBodyElem.textContent = bodyText;
    }
}

function escapeHtml(text) {
    if (!text) return '';
    return text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

function updateActiveNav(activeId, title) {
    document.querySelectorAll('.nav-item').forEach(nav => nav.classList.remove('active'));
    const activeNav = document.getElementById(activeId);
    if (activeNav) activeNav.classList.add('active');
    
    const boxTitle = document.getElementById('box-title');
    if (boxTitle) boxTitle.textContent = title;
}

function refreshMails() {
    loadInbox();
}

// DUY NHẤT 1 HÀM LOGOUT CHUẨN GỌI API ĐĂNG XUẤT
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