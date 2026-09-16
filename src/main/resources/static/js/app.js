// Tự động chạy khi giao diện main.html được tải xong
document.addEventListener('DOMContentLoaded', () => {
    // 1. Lấy thông tin tài khoản đang đăng nhập từ LocalStorage
    const currentUser = localStorage.getItem('currentUser');

    // Nếu chưa đăng nhập mà cố tình truy cập main.html -> Đá về trang đăng nhập
    if (!currentUser) {
        window.location.href = 'index.html';
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

// Xử lý gửi thư từ Modal
async function handleSendMail(event) {
    event.preventDefault();
    const currentUser = localStorage.getItem('currentUser');
    const to = document.getElementById('compose-to').value;
    const subject = document.getElementById('compose-subject').value;
    const body = document.getElementById('compose-body').value;

    try {
        const response = await fetch('/api/mails/send', {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: `from=${encodeURIComponent(currentUser)}&to=${encodeURIComponent(to)}&subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(body)}`
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

// Chọn email để xem chi tiết ở cột 3
function selectEmail(mail, element) {
    document.querySelectorAll('.email-item').forEach(item => item.classList.remove('active'));
    element.classList.add('active');

    document.getElementById('no-email-selected').classList.add('hidden');
    document.getElementById('email-detail').classList.remove('hidden');

    document.getElementById('detail-subject').textContent = mail.subject;
    document.getElementById('detail-sender').textContent = mail.sender;
    document.getElementById('detail-receiver').textContent = mail.recipient || localStorage.getItem('currentUser');
    document.getElementById('detail-time').textContent = mail.timestamp || '';
    document.getElementById('detail-body').textContent = mail.body;
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
    window.location.href = 'index.html';
}