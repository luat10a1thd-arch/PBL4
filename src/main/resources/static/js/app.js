// Đóng/Mở Modal Soạn Thư
function openComposeModal() {
    document.getElementById('compose-modal').classList.remove('hidden');
}

function closeComposeModal() {
    document.getElementById('compose-modal').classList.add('hidden');
}

// Giả lập gửi thư từ Modal
function handleSendMail(event) {
    event.preventDefault();
    alert('Gửi thư thành công!');
    closeComposeModal();
}

// Giả lập chọn email trong danh sách để xem
function selectEmail(id, element) {
    // Bỏ class active ở các item cũ
    document.querySelectorAll('.email-item').forEach(item => item.classList.remove('active'));
    
    // Active item vừa click
    element.classList.add('active');
    
    // Ẩn thông báo trống, hiện khung nội dung
    document.getElementById('no-email-selected').classList.add('hidden');
    document.getElementById('email-detail').classList.remove('hidden');
}

// Đăng xuất
function logout() {
    window.location.href = 'index.html';
}