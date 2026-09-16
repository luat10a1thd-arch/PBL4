// Tự động chạy khi giao diện main.html được tải xong
document.addEventListener('DOMContentLoaded', () => {
    // 1. Lấy thông tin tài khoản đang đăng nhập từ LocalStorage
    const currentUser = localStorage.getItem('currentUser');

    // Nếu chưa đăng nhập mà cố tình truy cập /main -> Đá về trang đăng nhập /
    if (!currentUser) {
        window.location.href = '/';
        return;
    }

    // 2. Hiển thị email người dùng lên góc phải Header
    const userEmailSpan = document.getElementById('current-user-email');
    if (userEmailSpan) {
        userEmailSpan.textContent = currentUser;
    }

    // 3. Tải danh sách email của chính tài khoản này
    loadInbox();
});

// Đóng / Mở Modal Soạn Thư
function openComposeModal() {
    document.getElementById('compose-modal').classList.remove('hidden');
}

function closeComposeModal() {
    document.getElementById('compose-modal').classList.add('hidden');
}

// Xử lý đọc File đính kèm thành chuỗi Base64
function convertFileToBase64(file) {
    return new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.readAsDataURL(file);
        reader.onload = () => resolve(reader.result);
        reader.onerror = error => reject(error);
    });
}

// Xử lý gửi thư từ Modal (Có hỗ trợ File đính kèm)
async function handleSendMail(event) {
    event.preventDefault();
    const currentUser = localStorage.getItem('currentUser');
    const to = document.getElementById('compose-to').value;
    const subject = document.getElementById('compose-subject').value;
    const bodyText = document.getElementById('compose-body').value;
    const fileInput = document.getElementById('compose-file');

    let attachmentData = '';

    // Nếu người dùng chọn file -> Chuyển thành Base64
    if (fileInput && fileInput.files.length > 0) {
        const file = fileInput.files[0];
        try {
            const base64 = await convertFileToBase64(file);
            attachmentData = `\n[ATTACHMENT:${file.name}]${base64}[/ATTACHMENT]`;
        } catch (err) {
            alert('Lỗi đọc file đính kèm!');
            return;
        }
    }

    const fullContent = bodyText + attachmentData;

    try {
        const response = await fetch('/api/mails/send', {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: `from=${encodeURIComponent(currentUser)}&to=${encodeURIComponent(to)}&subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(fullContent)}`
        });

        const data = await response.json();
        alert(data.message);
        if (data.success) {
            closeComposeModal();
            // Reset form soạn thư
            document.getElementById('compose-form').reset();
            loadInbox();
        }
    } catch (err) {
        alert('Gửi thư thất bại, lỗi kết nối Server!');
    }
}

// Tải danh sách Hộp Thư Đến
async function loadInbox(event) {
    if (event) event.preventDefault();
    updateActiveNav('nav-inbox', 'Hộp thư đến');

    const currentUser = localStorage.getItem('currentUser');
    try {
        const response = await fetch(`/api/mails/inbox?user=${encodeURIComponent(currentUser)}`);
        const mails = await response.json();
        renderEmailList(mails);
    } catch (err) {
        console.error('Không thể tải hộp thư đến');
    }
}

// Tải danh sách Thư Đã Gửi
async function loadSentMail(event) {
    if (event) event.preventDefault();
    updateActiveNav('nav-sent', 'Thư đã gửi');

    const currentUser = localStorage.getItem('currentUser');
    try {
        const response = await fetch(`/api/mails/sent?user=${encodeURIComponent(currentUser)}`);
        const mails = await response.json();
        renderEmailList(mails);
    } catch (err) {
        console.error('Không thể tải thư đã gửi');
    }
}

// Hiển thị danh sách email lên cột giữa (Column 2)
function renderEmailList(mails) {
    const emailListDiv = document.getElementById('email-list');
    emailListDiv.innerHTML = '';

    if (!mails || mails.length === 0) {
        emailListDiv.innerHTML = '<div style="padding: 20px; text-align: center; color: #94a3b8;">Không có email nào.</div>';
        return;
    }

    mails.forEach(mail => {
        const item = document.createElement('div');
        item.className = 'email-item';
        item.onclick = () => selectEmail(mail, item);

        item.innerHTML = `
            <div class="email-sender">${mail.sender}</div>
            <div class="email-subject">${mail.subject}</div>
            <div class="email-date">${mail.timestamp || ''}</div>
        `;
        emailListDiv.appendChild(item);
    });
}

// Chọn email để xem chi tiết ở cột 3 (Hiển thị cả nút tải File đính kèm nếu có)
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

    // Kiểm tra xem email có chứa dữ liệu file đính kèm hay không
    if (bodyText.includes('[ATTACHMENT:')) {
        const parts = bodyText.split('[ATTACHMENT:');
        const textContent = parts[0];
        const attachParts = parts[1].split(']');
        const fileName = attachParts[0];
        const base64Data = attachParts[1].replace('[/ATTACHMENT]', '').trim();

        detailBodyElem.innerHTML = `
            <div style="white-space: pre-wrap;">${escapeHtml(textContent)}</div>
            <div style="margin-top: 25px; padding: 12px 16px; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; display: inline-block;">
                <div style="font-weight: 600; color: #334155; margin-bottom: 6px;">
                    <i class="fa-solid fa-paperclip"></i> File đính kèm: ${escapeHtml(fileName)}
                </div>
                <a href="${base64Data}" download="${escapeHtml(fileName)}" class="btn btn-outline" style="font-size: 12px; padding: 4px 10px; text-decoration: none; color: #2563eb; display: inline-flex; align-items: center; gap: 5px;">
                    <i class="fa-solid fa-download"></i> Tải về file
                </a>
            </div>
        `;
    } else {
        detailBodyElem.textContent = bodyText;
    }
}

// Tránh lỗi XSS cho nội dung HTML
function escapeHtml(text) {
    if (!text) return '';
    return text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

// Đổi trạng thái tab trên Sidebar
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

// Đăng xuất: Xóa session tài khoản khỏi LocalStorage và chuyển về trang đăng nhập
function logout() {
    localStorage.removeItem('currentUser');
    window.location.href = '/';
}