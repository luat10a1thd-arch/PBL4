/**
 * FILE: src/main/resources/static/dut_mail_features.js
 * BẢN NÂNG CẤP V4 - HOÀN THIỆN TRIỆT ĐỂ CÁC TÍNH NĂNG:
 *  1. Hộp thoại "Thư mới": Nhận diện chính xác ô "Ví dụ: nguoinhan1@pbl4.com...", "Chủ đề", "Nội dung thư...", nút "×" và "Hủy".
 *     - Chỉ cần bắt đầu nhập mail người nhận (dù Chủ đề & Nội dung còn trống) là KÍCH HOẠT NGAY chế độ Thư nháp & nút Thư rác.
 *     - Khi bấm dấu "×" (hoặc "Hủy") đóng khung soạn thư -> Tự động đưa ngay vào mục "📝 Thư nháp".
 *  2. Chức năng "⚠️ Thư rác":
 *     - Bấm 1 chạm vào nút Tam giác cảnh báo (⚠️) trên dòng thư, trong chi tiết thư, hoặc ngay trong khung "Thư mới" khi đã nhập người nhận -> Đưa thẳng vào mục "⚠️ Thư rác" và hạn chế địa chỉ đó (mọi thư gửi/nhận tiếp theo với địa chỉ đó tự động vào thẳng "Thư rác").
 *  3. Đặt dấu sao (☆ / ★): Ưu tiên đẩy thư gắn sao lên ĐẦU danh sách (trước thư không sao) và đồng bộ vào mục "⭐ Thư gắn sao".
 *  4. Nút Research (Tìm kiếm toàn văn), Trả lời thư (Reply + Threading), Xóa mềm vào Thùng rác & Khôi phục, Quota 100 MB.
 */
(function () {
    'use strict';

    if (window.__DUT_MAIL_V4__) return;
    window.__DUT_MAIL_V4__ = true;

    const STATE = {
        token: '',
        currentFolder: 'inbox',
        currentMails: [],
        selectedMail: null,
        replyContext: null,
        activeDraftId: null,
        isMailSent: false,
        draftBuffer: { to: '', subject: '', body: '', dirty: false },
        savingDraftLock: false,
        renderingLock: false,
        lastSavedSignature: '',
        lastSavedAt: 0
    };

    const FOLDER_LABELS = {
        inbox: 'Hộp thư đến',
        sent: 'Thư đã gửi',
        starred: 'Thư gắn sao',
        drafts: 'Thư nháp',
        spam: 'Thư rác',
        trash: 'Thùng rác'
    };

    // =========================================================================
    // 1. LẤY TOKEN XÁC THỰC & ĐÁNH CHẶN FETCH CỦA APP.JS
    // =========================================================================
    function getToken() {
        if (STATE.token) return STATE.token;
        const keys = ['token', 'authToken', 'jwt', 'access_token', 'sessionToken', 'pbl4_token'];
        for (const k of keys) {
            const v = localStorage.getItem(k) || sessionStorage.getItem(k);
            if (v && v.trim()) {
                STATE.token = v.replace(/^Bearer\s+/i, '').trim();
                return STATE.token;
            }
        }
        for (const store of [localStorage, sessionStorage]) {
            for (let i = 0; i < store.length; i++) {
                const raw = store.getItem(store.key(i)) || '';
                if (/^[A-Za-z0-9_-]{40,48}$/.test(raw.trim())) {
                    STATE.token = raw.trim();
                    return STATE.token;
                }
                try {
                    const obj = JSON.parse(raw);
                    if (obj && typeof obj.token === 'string' && obj.token.trim()) {
                        STATE.token = obj.token.trim();
                        return STATE.token;
                    }
                } catch (e) {}
            }
        }
        return '';
    }

    function authHeaders(isForm) {
        const h = { 'Authorization': 'Bearer ' + getToken() };
        if (isForm) h['Content-Type'] = 'application/x-www-form-urlencoded; charset=UTF-8';
        return h;
    }

    function sortMailsStarredFirst(mails) {
        if (!Array.isArray(mails)) return [];
        return [...mails].sort((a, b) => {
            const sa = Boolean(a && a.starred);
            const sb = Boolean(b && b.starred);
            if (sa !== sb) return sa ? -1 : 1;
            const ida = (a && a.id) ? String(a.id) : '';
            const idb = (b && b.id) ? String(b.id) : '';
            return idb.localeCompare(ida);
        });
    }

    const origFetch = window.fetch.bind(window);
    window.fetch = async function (input, init) {
        let url = typeof input === 'string' ? input : (input && input.url ? input.url : '');
        try {
            if (init && init.headers) {
                const auth = init.headers['Authorization'] || init.headers['authorization']
                    || (typeof init.headers.get === 'function' && init.headers.get('Authorization'));
                if (auth && /^Bearer\s+/i.test(auth)) {
                    STATE.token = auth.replace(/^Bearer\s+/i, '').trim();
                }
            }
            if (url.includes('/api/mails/send') || url.includes('/api/mails/reply')) {
                STATE.isMailSent = true;
                resetDraftBuffer();
                if (init && typeof init.body === 'string') {
                    const params = new URLSearchParams(init.body);
                    if (STATE.replyContext) {
                        if (STATE.replyContext.inReplyTo && !params.has('inReplyTo')) {
                            params.set('inReplyTo', STATE.replyContext.inReplyTo);
                        }
                        if (STATE.replyContext.references && !params.has('references')) {
                            params.set('references', STATE.replyContext.references);
                        }
                        STATE.replyContext = null;
                    }
                    if (STATE.activeDraftId && !params.has('draftId')) {
                        params.set('draftId', STATE.activeDraftId);
                        STATE.activeDraftId = null;
                    }
                    init.body = params.toString();
                }
            }
        } catch (e) {}

        const response = await origFetch(input, init);

        try {
            if ((url.includes('/api/mails/inbox') || url.includes('/api/mails/sent')) && response.ok) {
                const clone = response.clone();
                clone.json().then(mails => {
                    if (Array.isArray(mails)) {
                        STATE.currentMails = sortMailsStarredFirst(mails);
                        setTimeout(() => renderMailList(STATE.currentMails), 40);
                    }
                }).catch(() => {});
            } else if (url.includes('/api/mails/detail') && response.ok) {
                const clone = response.clone();
                clone.json().then(mail => {
                    if (mail && mail.id) {
                        STATE.selectedMail = mail;
                        setTimeout(() => {
                            const { detailCol } = getLayoutContainers();
                            if (detailCol) {
                                injectDetailToolbar(detailCol, mail);
                                loadAndRenderThread(detailCol, mail);
                            }
                        }, 50);
                    }
                }).catch(() => {});
            } else if (url.includes('/api/mails/send') && response.ok) {
                setTimeout(() => {
                    STATE.isMailSent = false;
                    refreshQuotaBar();
                    loadFolderMails(STATE.currentFolder, false);
                }, 350);
            }
        } catch (e) {}

        return response;
    };

    // =========================================================================
    // 2. NHẬN DIỆN CẤU TRÚC GIAO DIỆN 3 CỘT & HỘP THOẠI "THƯ MỚI"
    // =========================================================================
    function getLayoutContainers() {
        const searchInput = document.querySelector('input[placeholder*="Tìm kiếm"]');
        let middleCol = null;
        let searchRow = null;
        let mailListEl = null;
        let detailCol = null;
        let headerTitleEl = null;
        let refreshBtn = null;

        if (searchInput) {
            let cur = searchInput;
            for (let i = 0; i < 6 && cur && cur.parentElement; i++) {
                const parent = cur.parentElement;
                const h = parent.querySelector('h1, h2, h3, .folder-title, #folder-title, #current-folder-title');
                if (h && parent.contains(searchInput)) {
                    middleCol = parent;
                    searchRow = cur;
                    headerTitleEl = h;
                    break;
                }
                cur = parent;
            }

            if (middleCol && searchRow) {
                let sib = searchRow.nextElementSibling;
                while (sib) {
                    if (sib.tagName === 'DIV' || sib.tagName === 'UL') {
                        mailListEl = sib;
                        break;
                    }
                    sib = sib.nextElementSibling;
                }
                detailCol = middleCol.nextElementSibling;
                const headerBar = headerTitleEl.parentElement;
                if (headerBar) {
                    refreshBtn = headerBar.querySelector('button, i, span, a');
                    if (refreshBtn === headerTitleEl) {
                        refreshBtn = headerTitleEl.nextElementSibling;
                    }
                }
            }
        }

        if (!mailListEl) {
            mailListEl = document.querySelector('#mail-list, #mailList, #email-list, #inbox-list, .mail-list, .email-list');
        }
        if (!detailCol) {
            detailCol = document.querySelector('#mail-detail, #detail-view, .mail-detail, .email-detail, #email-detail');
        }
        if (!headerTitleEl) {
            headerTitleEl = document.querySelector('h2, h3, .folder-title');
        }
        return { searchInput, middleCol, mailListEl, detailCol, headerTitleEl, refreshBtn };
    }

    /**
     * Nhận diện chính xác 100% các ô trong hộp thoại "✉️ Thư mới" (image_2e95a9.png):
     *  - Ô người nhận: placeholder="Ví dụ: nguoinhan1@pbl4.com nguoinhan2@pbl4.com"
     *  - Ô chủ đề: placeholder="Chủ đề"
     *  - Ô nội dung: placeholder="Nội dung thư..."
     */
    function findComposeInputs() {
        let toInput = document.querySelector(
            'input[placeholder*="nguoinhan" i], input[placeholder*="Ví dụ" i], input[placeholder*="Người nhận" i], input[placeholder*="Đến" i], #compose-to, #to-input, #to, #recipient, #mail-to, input[name="to"]'
        );
        let subjInput = document.querySelector(
            'input[placeholder*="Chủ đề" i], input[placeholder*="Tiêu đề" i], #compose-subject, #subject-input, #subject, #mail-subject, input[name="subject"]'
        );
        let bodyInput = document.querySelector(
            'textarea[placeholder*="Nội dung" i], #compose-body, #body-input, #body, #mail-body, textarea[name="body"], textarea'
        );

        let modalEl = null;
        const anchor = toInput || subjInput || bodyInput;
        if (anchor) {
            modalEl = anchor.closest('.modal, .compose-modal, #compose-modal, #composeModal, [id*="compose" i], [id*="modal" i], [class*="modal" i]');
            if (!modalEl) {
                let p = anchor.parentElement;
                while (p && p !== document.body) {
                    const txt = p.textContent || '';
                    if (/Thư mới|Soạn thư/i.test(txt) && p.querySelectorAll('input').length >= 2) {
                        modalEl = p;
                        break;
                    }
                    p = p.parentElement;
                }
            }
        }

        // Dự phòng theo vị trí input bên trong hộp thoại "Thư mới"
        if (modalEl) {
            const textInputs = Array.from(modalEl.querySelectorAll('input')).filter(inp => {
                if (inp.placeholder && /Tìm kiếm/i.test(inp.placeholder)) return false;
                const t = (inp.type || 'text').toLowerCase();
                return t === 'text' || t === 'email' || t === '';
            });
            if (!toInput && textInputs.length >= 1) toInput = textInputs[0];
            if (!subjInput && textInputs.length >= 2) subjInput = textInputs[1];
            if (!bodyInput) bodyInput = modalEl.querySelector('textarea');
        }

        return { toInput, subjInput, bodyInput, modalEl };
    }

    function escapeHtml(str) {
        if (str === null || str === undefined) return '';
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    // =========================================================================
    // 3. CSS GIAO DIỆN NÂNG CẤP
    // =========================================================================
    function injectStyles() {
        if (document.getElementById('dut-upgrade-styles')) return;
        const style = document.createElement('style');
        style.id = 'dut-upgrade-styles';
        style.textContent = `
            .dut-action-toolbar {
                display: flex; gap: 8px; flex-wrap: wrap; align-items: center;
                padding: 12px 16px; margin-bottom: 16px;
                border: 1px solid rgba(139, 92, 246, 0.25);
                background: rgba(139, 92, 246, 0.08); border-radius: 10px;
            }
            .dut-btn {
                display: inline-flex; align-items: center; gap: 6px;
                padding: 7px 13px; border-radius: 8px; font-size: 13px; font-weight: 600;
                border: 1px solid rgba(255,255,255,0.14); background: rgba(255,255,255,0.06);
                color: #f1f5f9; cursor: pointer; transition: all 0.16s ease;
            }
            .dut-btn:hover { background: rgba(139, 92, 246, 0.28); border-color: #8b5cf6; }
            .dut-btn-primary { background: #7c3aed; border-color: #7c3aed; color: #fff; }
            .dut-btn-primary:hover { background: #6d28d9; }
            .dut-btn-warn { border-color: rgba(245, 158, 11, 0.4); color: #fbbf24; }
            .dut-btn-warn:hover { background: rgba(245, 158, 11, 0.24); border-color: #f59e0b; color: #fde68a; }
            .dut-btn-danger:hover { background: rgba(239, 68, 68, 0.25); border-color: #ef4444; color: #fca5a5; }
            .dut-search-btn {
                margin-left: 8px; padding: 7px 14px; border-radius: 999px;
                background: linear-gradient(135deg, #8b5cf6, #6d28d9); color: #fff;
                border: none; font-size: 12.5px; cursor: pointer; font-weight: 600;
                flex-shrink: 0; box-shadow: 0 2px 10px rgba(124, 58, 237, 0.35);
            }
            .dut-search-btn:hover { opacity: 0.92; transform: translateY(-1px); }
            .dut-mail-row {
                padding: 13px 15px; border-bottom: 1px solid rgba(255,255,255,0.06);
                cursor: pointer; display: flex; justify-content: space-between;
                align-items: flex-start; gap: 10px; transition: background 0.15s ease;
            }
            .dut-mail-row:hover { background: rgba(139, 92, 246, 0.11); }
            .dut-mail-row.active-mail { background: rgba(139, 92, 246, 0.2); border-left: 3px solid #8b5cf6; }
            .dut-mail-row.starred-priority { background: rgba(250, 204, 21, 0.06); border-left: 3px solid #facc15; }
            .dut-star-btn {
                cursor: pointer; font-size: 18px; color: #64748b; margin-right: 6px;
                user-select: none; background: none; border: none; padding: 0 2px;
                line-height: 1; transition: transform 0.15s ease, color 0.15s ease;
            }
            .dut-star-btn.starred { color: #facc15; text-shadow: 0 0 8px rgba(250, 204, 21, 0.45); }
            .dut-star-btn:hover { transform: scale(1.25); color: #fde047; }
            .dut-unread-dot {
                width: 8px; height: 8px; border-radius: 50%; background: #8b5cf6;
                display: inline-block; margin-right: 6px; vertical-align: middle;
            }
            .dut-row-action {
                background: none; border: none; cursor: pointer;
                font-size: 15px; padding: 4px 6px; border-radius: 6px; opacity: 0.85;
                transition: all 0.15s ease;
            }
            .dut-row-action:hover { opacity: 1; transform: scale(1.15); background: rgba(255,255,255,0.09); }
            .dut-badge-star {
                display: inline-block; font-size: 10.5px; padding: 1px 6px; border-radius: 4px;
                background: rgba(250, 204, 21, 0.16); color: #fde047; border: 1px solid rgba(250, 204, 21, 0.35);
                margin-left: 6px; font-weight: 600;
            }
            .dut-badge-spam {
                display: inline-block; font-size: 11px; padding: 1px 6px; border-radius: 4px;
                background: rgba(245, 158, 11, 0.18); color: #fbbf24; border: 1px solid rgba(245, 158, 11, 0.4);
                margin-left: 6px; font-weight: 600;
            }
            .dut-compose-helper {
                display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap;
                gap: 8px; margin-top: 10px; padding: 8px 12px; border-radius: 8px;
                background: rgba(139, 92, 246, 0.09); border: 1px dashed rgba(139, 92, 246, 0.35);
                font-size: 12.5px; color: #cbd5e1;
            }
            .dut-thread-box {
                margin-top: 20px; padding: 14px 16px; border-radius: 10px;
                background: rgba(139, 92, 246, 0.07); border: 1px solid rgba(139, 92, 246, 0.24);
            }
            .dut-thread-item {
                padding: 10px 12px; margin-top: 8px; border-radius: 8px;
                background: rgba(0,0,0,0.28); border: 1px solid rgba(255,255,255,0.06);
                font-size: 13px; color: #cbd5e1;
            }
            .dut-toast {
                position: fixed; bottom: 24px; right: 24px; z-index: 99999;
                background: #1e1b4b; color: #fff; border: 1px solid #8b5cf6;
                padding: 11px 18px; border-radius: 10px; font-size: 13.5px; font-weight: 500;
                box-shadow: 0 10px 28px rgba(0,0,0,0.55);
            }
        `;
        document.head.appendChild(style);
    }

    function showToast(msg) {
        const t = document.createElement('div');
        t.className = 'dut-toast';
        t.textContent = msg;
        document.body.appendChild(t);
        setTimeout(() => t.remove(), 3000);
    }

    // =========================================================================
    // 4. SIDEBAR 6 MỤC ĐỒNG BỘ HOÀN TOÀN
    // =========================================================================
    function enhanceSidebar() {
        const candidates = Array.from(document.querySelectorAll('a, button, div, li'));
        let inboxNode = candidates.find(el => el.children.length <= 2 && /^Hộp thư đến$/i.test((el.textContent || '').trim()));
        let sentNode = candidates.find(el => el.children.length <= 2 && /^Thư đã gửi$/i.test((el.textContent || '').trim()));
        if (!sentNode || !sentNode.parentElement) return;

        const sidebarList = sentNode.parentElement;

        if (inboxNode && !inboxNode.dataset.dutBound) {
            const cleanInbox = inboxNode.cloneNode(true);
            cleanInbox.dataset.dutBound = '1';
            cleanInbox.dataset.folder = 'inbox';
            cleanInbox.removeAttribute('onclick');
            cleanInbox.addEventListener('click', (e) => {
                e.preventDefault();
                e.stopImmediatePropagation();
                switchFolder('inbox');
            }, true);
            inboxNode.replaceWith(cleanInbox);
            inboxNode = cleanInbox;
        }

        if (sentNode && !sentNode.dataset.dutBound) {
            const cleanSent = sentNode.cloneNode(true);
            cleanSent.dataset.dutBound = '1';
            cleanSent.dataset.folder = 'sent';
            cleanSent.removeAttribute('onclick');
            cleanSent.addEventListener('click', (e) => {
                e.preventDefault();
                e.stopImmediatePropagation();
                switchFolder('sent');
            }, true);
            sentNode.replaceWith(cleanSent);
            sentNode = cleanSent;
        }

        if (document.getElementById('dut-folder-trash')) return;

        const extraFolders = [
            { id: 'starred', icon: '⭐', label: 'Thư gắn sao' },
            { id: 'drafts',  icon: '📝', label: 'Thư nháp' },
            { id: 'spam',    icon: '⚠️', label: 'Thư rác' },
            { id: 'trash',   icon: '🗑️', label: 'Thùng rác' }
        ];

        extraFolders.forEach(f => {
            const clone = sentNode.cloneNode(true);
            clone.id = 'dut-folder-' + f.id;
            clone.removeAttribute('onclick');
            clone.removeAttribute('href');
            clone.classList.remove('active');
            clone.dataset.folder = f.id;
            clone.dataset.dutBound = '1';
            clone.innerHTML = `<span style="margin-right:10px;">${f.icon}</span><span>${f.label}</span>`;
            clone.addEventListener('click', (e) => {
                e.preventDefault();
                e.stopImmediatePropagation();
                switchFolder(f.id);
            }, true);
            sidebarList.appendChild(clone);
        });
    }

    // =========================================================================
    // 5. THANH TÌM KIẾM (RESEARCH) & NÚT LÀM MỚI (REFRESH ↻)
    // =========================================================================
    function enhanceSearchAndRefresh() {
        const { searchInput, refreshBtn } = getLayoutContainers();
        if (searchInput && !document.getElementById('dut-research-btn')) {
            const wrapper = searchInput.parentElement;
            if (wrapper) {
                wrapper.style.display = 'flex';
                wrapper.style.alignItems = 'center';
            }
            const btn = document.createElement('button');
            btn.id = 'dut-research-btn';
            btn.type = 'button';
            btn.className = 'dut-search-btn';
            btn.innerHTML = '🔍 Research';
            btn.title = 'Tìm kiếm sâu toàn văn (Người gửi, Tiêu đề, Nội dung)';
            btn.onclick = (e) => {
                e.preventDefault();
                e.stopPropagation();
                executeResearch(searchInput.value);
            };
            searchInput.addEventListener('keydown', (e) => {
                if (e.key === 'Enter') {
                    e.preventDefault();
                    e.stopPropagation();
                    executeResearch(searchInput.value);
                }
            });
            wrapper.appendChild(btn);
        }

        if (refreshBtn && !refreshBtn.dataset.dutRefreshBound) {
            const cleanRefresh = refreshBtn.cloneNode(true);
            cleanRefresh.dataset.dutRefreshBound = '1';
            cleanRefresh.removeAttribute('onclick');
            cleanRefresh.addEventListener('click', (e) => {
                e.preventDefault();
                e.stopImmediatePropagation();
                loadFolderMails(STATE.currentFolder, true);
                refreshQuotaBar();
            }, true);
            refreshBtn.replaceWith(cleanRefresh);
        }
    }

    // =========================================================================
    // 6. CHUYỂN THƯ MỤC & TẢI DANH SÁCH THƯ
    // =========================================================================
    async function switchFolder(folder) {
        STATE.currentFolder = folder;
        STATE.selectedMail = null;

        document.querySelectorAll('[data-folder]').forEach(btn => {
            if (btn.dataset.folder === folder) btn.classList.add('active');
            else btn.classList.remove('active');
        });

        const { headerTitleEl, detailCol, searchInput } = getLayoutContainers();
        if (headerTitleEl && FOLDER_LABELS[folder]) {
            headerTitleEl.textContent = FOLDER_LABELS[folder];
        }
        if (searchInput) {
            searchInput.value = '';
        }
        if (detailCol) {
            detailCol.innerHTML = `
                <div style="height:100%;display:flex;align-items:center;justify-content:center;color:#64748b;font-size:15px;">
                    Chọn một thư trong mục "${escapeHtml(FOLDER_LABELS[folder] || folder)}" để xem nội dung
                </div>
            `;
        }

        await loadFolderMails(folder, false);
        await refreshQuotaBar();
    }

    function endpointForFolder(folder) {
        switch (folder) {
            case 'sent':    return '/api/mails/sent?page=1&limit=50';
            case 'starred': return '/api/mails/starred';
            case 'drafts':  return '/api/mails/drafts?page=1&limit=50';
            case 'spam':    return '/api/mails/spam?page=1&limit=50';
            case 'trash':   return '/api/mails/trash?page=1&limit=50';
            default:        return '/api/mails/inbox?folder=inbox&page=1&limit=50';
        }
    }

    async function loadFolderMails(folder, showNotification) {
        try {
            const res = await origFetch(endpointForFolder(folder), {
                headers: authHeaders(false)
            });
            if (!res.ok) return;
            const mails = await res.json();
            STATE.currentMails = sortMailsStarredFirst(Array.isArray(mails) ? mails : []);
            renderMailList(STATE.currentMails);
            if (showNotification) {
                showToast(`↻ Đã làm mới "${FOLDER_LABELS[folder] || folder}" (${STATE.currentMails.length} thư)`);
            }
        } catch (err) {
            console.error('[DUT Mail] Lỗi tải thư mục:', err);
        }
    }

    async function executeResearch(query) {
        const q = (query || '').trim();
        if (!q) {
            await loadFolderMails(STATE.currentFolder, true);
            return;
        }
        try {
            const url = `/api/mails/search?folder=${encodeURIComponent(STATE.currentFolder)}&q=${encodeURIComponent(q)}`;
            const res = await origFetch(url, { headers: authHeaders(false) });
            if (!res.ok) return;
            const mails = await res.json();
            STATE.currentMails = sortMailsStarredFirst(Array.isArray(mails) ? mails : []);
            renderMailList(STATE.currentMails);
            showToast(`🔍 Tìm thấy ${STATE.currentMails.length} thư khớp với "${q}"`);
        } catch (err) {
            console.error('[DUT Mail] Lỗi tìm kiếm:', err);
        }
    }

    // =========================================================================
    // 7. HIỂN THỊ DANH SÁCH THƯ (CÓ NGÔI SAO ☆/★, TAM GIÁC RÁC ⚠️ 1 CHẠM, XÓA 🗑️)
    // =========================================================================
    function renderMailList(mails) {
        const { mailListEl } = getLayoutContainers();
        if (!mailListEl) return;

        STATE.renderingLock = true;
        try {
            const sorted = sortMailsStarredFirst(mails);
            if (!sorted || sorted.length === 0) {
                mailListEl.innerHTML = `
                    <div class="dut-empty-box" style="padding: 36px 16px; text-align: center; color: #64748b; font-size: 14px;">
                        Không có thư nào trong mục "${escapeHtml(FOLDER_LABELS[STATE.currentFolder] || STATE.currentFolder)}".
                    </div>
                `;
                return;
            }

            const showRecipient = (STATE.currentFolder === 'sent' || STATE.currentFolder === 'drafts');
            const isSpamFolder = (STATE.currentFolder === 'spam');
            const isTrashFolder = (STATE.currentFolder === 'trash');

            mailListEl.innerHTML = sorted.map(m => {
                const peer = showRecipient
                    ? ('Tới: ' + (m.recipient || '(Chưa có người nhận)'))
                    : (m.sender || m.recipient || '(Không rõ người gửi)');
                const isSelected = STATE.selectedMail && STATE.selectedMail.id === m.id;
                const seen = m.seen !== false;
                const starred = Boolean(m.starred);

                return `
                    <div class="dut-mail-row ${isSelected ? 'active-mail' : ''} ${starred ? 'starred-priority' : ''}" data-mail-id="${escapeHtml(m.id)}">
                        <div style="min-width:0;flex:1;">
                            <div style="display:flex;align-items:center;gap:4px;flex-wrap:wrap;">
                                <button type="button" class="dut-star-btn ${starred ? 'starred' : ''}" data-star-id="${escapeHtml(m.id)}" title="${starred ? 'Bỏ dấu sao' : 'Đặt dấu sao (Ưu tiên lên đầu & thêm vào Thư gắn sao)'}">
                                    ${starred ? '★' : '☆'}
                                </button>
                                ${!seen ? '<span class="dut-unread-dot" title="Chưa đọc"></span>' : ''}
                                <strong style="color:#f8fafc;font-size:14px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">
                                    ${escapeHtml(peer)}
                                </strong>
                                ${starred ? '<span class="dut-badge-star">★ Ưu tiên</span>' : ''}
                                ${isSpamFolder ? '<span class="dut-badge-spam">⚠️ Thư rác</span>' : ''}
                            </div>
                            <div style="color:${!seen ? '#ffffff' : '#cbd5e1'};font-weight:${!seen ? '700' : '400'};font-size:13.5px;margin-top:4px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">
                                ${escapeHtml(m.subject || '(Không có chủ đề)')}
                            </div>
                            <div style="color:#64748b;font-size:12px;margin-top:4px;">
                                ${escapeHtml(m.timestamp || '')}
                            </div>
                        </div>
                        <div style="display:flex;align-items:center;gap:2px;flex-shrink:0;">
                            ${!isSpamFolder && !isTrashFolder ? `
                                <button type="button" class="dut-row-action" style="color:#f59e0b;" data-spam-id="${escapeHtml(m.id)}" title="⚠️ Đưa vào Thư rác & Hạn chế địa chỉ này (1 chạm)">⚠️</button>
                            ` : ''}
                            ${isSpamFolder ? `
                                <button type="button" class="dut-row-action" style="color:#22c55e;" data-unspam-id="${escapeHtml(m.id)}" title="✅ Bỏ hạn chế & Đưa về Hộp thư đến">✅</button>
                            ` : ''}
                            ${isTrashFolder ? `
                                <button type="button" class="dut-row-action" style="color:#38bdf8;" data-restore-id="${escapeHtml(m.id)}" title="♻️ Khôi phục về Hộp thư đến">♻️</button>
                            ` : ''}
                            <button type="button" class="dut-row-action" style="color:#ef4444;" data-del-id="${escapeHtml(m.id)}" title="${isTrashFolder ? 'Xóa vĩnh viễn' : 'Chuyển vào Thùng rác'}">🗑️</button>
                        </div>
                    </div>
                `;
            }).join('');

            mailListEl.querySelectorAll('.dut-mail-row').forEach(row => {
                row.addEventListener('click', () => {
                    const id = row.getAttribute('data-mail-id');
                    if (id) openDetail(id);
                });
            });

            mailListEl.querySelectorAll('[data-star-id]').forEach(btn => {
                btn.addEventListener('click', (e) => {
                    e.stopPropagation();
                    const id = btn.getAttribute('data-star-id');
                    const targetMail = STATE.currentMails.find(x => x.id === id);
                    const nextStar = !(targetMail && targetMail.starred);
                    toggleStar(id, nextStar);
                });
            });

            mailListEl.querySelectorAll('[data-spam-id]').forEach(btn => {
                btn.addEventListener('click', (e) => {
                    e.stopPropagation();
                    const id = btn.getAttribute('data-spam-id');
                    if (id) reportSpamAndBlockSender(id);
                });
            });

            mailListEl.querySelectorAll('[data-unspam-id]').forEach(btn => {
                btn.addEventListener('click', (e) => {
                    e.stopPropagation();
                    const id = btn.getAttribute('data-unspam-id');
                    if (id) unspamAndUnblockSender(id);
                });
            });

            mailListEl.querySelectorAll('[data-del-id]').forEach(btn => {
                btn.addEventListener('click', (e) => {
                    e.stopPropagation();
                    const id = btn.getAttribute('data-del-id');
                    if (id) deleteMail(id);
                });
            });

            mailListEl.querySelectorAll('[data-restore-id]').forEach(btn => {
                btn.addEventListener('click', (e) => {
                    e.stopPropagation();
                    const id = btn.getAttribute('data-restore-id');
                    if (id) restoreMail(id);
                });
            });
        } finally {
            setTimeout(() => { STATE.renderingLock = false; }, 30);
        }
    }

    function observeMailListContainer() {
        const { mailListEl } = getLayoutContainers();
        if (!mailListEl || mailListEl.dataset.dutObserved) return;
        mailListEl.dataset.dutObserved = '1';

        const obs = new MutationObserver(() => {
            if (STATE.renderingLock) return;
            const hasOurRows = mailListEl.querySelector('.dut-mail-row, .dut-empty-box');
            if (!hasOurRows && STATE.currentMails.length > 0) {
                renderMailList(STATE.currentMails);
            }
        });
        obs.observe(mailListEl, { childList: true });
    }

    // =========================================================================
    // 8. MỞ CHI TIẾT THƯ & THANH CÔNG CỤ ĐẦY ĐỦ
    // =========================================================================
    async function openDetail(mailId) {
        try {
            const url = `/api/mails/detail?id=${encodeURIComponent(mailId)}&folder=${encodeURIComponent(STATE.currentFolder)}`;
            const res = await origFetch(url, { headers: authHeaders(false) });
            if (!res.ok) return;
            const mail = await res.json();
            STATE.selectedMail = mail;

            const item = STATE.currentMails.find(x => x.id === mailId);
            if (item) item.seen = true;
            renderMailList(STATE.currentMails);

            const { detailCol } = getLayoutContainers();
            if (detailCol) {
                renderFullDetailView(detailCol, mail);
            }

            if (STATE.currentFolder === 'drafts') {
                openDraftToEdit(mail);
            }
        } catch (err) {
            console.error('[DUT Mail] Lỗi mở chi tiết thư:', err);
        }
    }

    function parseAttachmentsFromBody(rawBody) {
        const text = rawBody || '';
        const attachments = [];
        const regex = /\[ATTACHMENT:([^\]]+)\]([\s\S]*?)\[\/ATTACHMENT\]/g;
        let match;
        while ((match = regex.exec(text)) !== null) {
            attachments.push({
                name: match[1].trim(),
                base64: match[2].replace(/\s+/g, '')
            });
        }
        const cleanText = text.replace(regex, '').trim();
        return { cleanText, attachments };
    }

    function renderFullDetailView(detailCol, mail) {
        const { cleanText, attachments } = parseAttachmentsFromBody(mail.body || '');

        let attHtml = '';
        if (attachments.length > 0) {
            attHtml = `
                <div style="margin-top:20px;padding-top:16px;border-top:1px solid rgba(255,255,255,0.08);">
                    <div style="font-size:13.5px;color:#94a3b8;margin-bottom:10px;">📎 Danh sách file đính kèm:</div>
                    <div style="display:flex;gap:12px;flex-wrap:wrap;">
                        ${attachments.map(a => `
                            <div style="padding:10px 14px;border-radius:10px;background:rgba(255,255,255,0.04);border:1px solid rgba(255,255,255,0.1);display:inline-flex;flex-direction:column;gap:8px;">
                                <div style="font-size:13.5px;color:#e2e8f0;font-weight:500;">📎 ${escapeHtml(a.name)}</div>
                                <a href="data:application/octet-stream;base64,${a.base64}" download="${escapeHtml(a.name)}"
                                   class="dut-btn" style="text-decoration:none;justify-content:center;font-size:12px;padding:5px 10px;">
                                   ⬇️ Tải về
                                </a>
                            </div>
                        `).join('')}
                    </div>
                </div>
            `;
        }

        detailCol.innerHTML = `
            <div style="padding: 20px 24px; color: #f8fafc;">
                <div id="dut-detail-toolbar-slot"></div>
                <h2 style="margin: 0 0 12px 0; font-size: 22px; font-weight: 700; color: #ffffff;">
                    ${escapeHtml(mail.subject || '(Không có chủ đề)')}
                </h2>
                <div style="font-size: 14px; color: #cbd5e1; line-height: 1.7; padding-bottom: 14px; border-bottom: 1px solid rgba(255,255,255,0.08);">
                    <div><strong>👥 Từ:</strong> ${escapeHtml(mail.sender || '')}</div>
                    <div><strong>✈️ Tới:</strong> ${escapeHtml(mail.recipient || '')}</div>
                    <div><strong>🕒 Thời gian:</strong> ${escapeHtml(mail.timestamp || '')}</div>
                </div>
                <div style="margin-top: 16px; font-size: 14.5px; line-height: 1.65; color: #e2e8f0; white-space: pre-wrap; word-break: break-word;">${escapeHtml(cleanText)}</div>
                ${attHtml}
                <div id="dut-thread-slot"></div>
            </div>
        `;

        injectDetailToolbar(detailCol, mail);
        loadAndRenderThread(detailCol, mail);
    }

    function injectDetailToolbar(detailContainer, mail) {
        if (!detailContainer || !mail) return;
        let toolbar = detailContainer.querySelector('#dut-detail-toolbar');
        if (!toolbar) {
            toolbar = document.createElement('div');
            toolbar.id = 'dut-detail-toolbar';
            toolbar.className = 'dut-action-toolbar';
            const slot = detailContainer.querySelector('#dut-detail-toolbar-slot');
            if (slot) slot.replaceWith(toolbar);
            else detailContainer.prepend(toolbar);
        }

        const isTrash = STATE.currentFolder === 'trash';
        const isSpam = STATE.currentFolder === 'spam';
        const isDraft = STATE.currentFolder === 'drafts';

        toolbar.innerHTML = `
            <button type="button" class="dut-btn dut-btn-primary" id="dut-btn-reply">
                ${isDraft ? '📝 Tiếp tục soạn & Gửi' : '↩️ Trả lời'}
            </button>
            <button type="button" class="dut-btn" id="dut-btn-star" style="${mail.starred ? 'border-color:#facc15;color:#fde047;' : ''}">
                ${mail.starred ? '★ Đã gắn sao (Ưu tiên)' : '☆ Đặt dấu sao'}
            </button>
            <button type="button" class="dut-btn" id="dut-btn-unread">
                📩 Đánh dấu chưa đọc
            </button>
            ${!isSpam && !isTrash ? `
                <button type="button" class="dut-btn dut-btn-warn" id="dut-btn-spam" title="Chuyển vào Thư rác và hạn chế địa chỉ này">
                    ⚠️ Báo thư rác & Hạn chế
                </button>
            ` : ''}
            ${isSpam ? `
                <button type="button" class="dut-btn" id="dut-btn-unspam" style="border-color:#22c55e;color:#86efac;">
                    ✅ Không phải rác (Bỏ hạn chế)
                </button>
            ` : ''}
            ${isTrash ? `<button type="button" class="dut-btn" id="dut-btn-restore">♻️ Khôi phục</button>` : ''}
            <button type="button" class="dut-btn dut-btn-danger" id="dut-btn-delete">
                🗑️ ${isTrash ? 'Xóa vĩnh viễn' : 'Xóa'}
            </button>
        `;

        toolbar.querySelector('#dut-btn-reply').onclick = () => {
            if (isDraft) openDraftToEdit(mail);
            else openReplyCompose(mail);
        };
        toolbar.querySelector('#dut-btn-star').onclick = () => toggleStar(mail.id, !mail.starred);
        toolbar.querySelector('#dut-btn-unread').onclick = () => markUnread(mail.id);
        const spamBtn = toolbar.querySelector('#dut-btn-spam');
        if (spamBtn) spamBtn.onclick = () => reportSpamAndBlockSender(mail.id);
        const unspamBtn = toolbar.querySelector('#dut-btn-unspam');
        if (unspamBtn) unspamBtn.onclick = () => unspamAndUnblockSender(mail.id);
        const restoreBtn = toolbar.querySelector('#dut-btn-restore');
        if (restoreBtn) restoreBtn.onclick = () => restoreMail(mail.id);
        toolbar.querySelector('#dut-btn-delete').onclick = () => deleteMail(mail.id);
    }

    async function loadAndRenderThread(detailContainer, mail) {
        if (!detailContainer || !mail || !mail.subject) return;
        try {
            const res = await origFetch(`/api/mails/thread?subject=${encodeURIComponent(mail.subject)}`, {
                headers: authHeaders(false)
            });
            if (!res.ok) return;
            const threadMails = await res.json();
            if (!Array.isArray(threadMails) || threadMails.length <= 1) return;

            let box = detailContainer.querySelector('#dut-thread-box');
            if (!box) {
                box = document.createElement('div');
                box.id = 'dut-thread-box';
                box.className = 'dut-thread-box';
                const slot = detailContainer.querySelector('#dut-thread-slot');
                if (slot) slot.replaceWith(box);
                else detailContainer.appendChild(box);
            }

            box.innerHTML = `
                <div style="font-weight:700;font-size:13.5px;color:#c4b5fd;margin-bottom:6px;">
                    💬 Luồng hội thoại (${threadMails.length} thư liên quan)
                </div>
                ${threadMails.map(tm => {
                    const { cleanText } = parseAttachmentsFromBody(tm.body || '');
                    return `
                        <div class="dut-thread-item">
                            <div style="display:flex;justify-content:space-between;color:#f8fafc;font-weight:600;margin-bottom:4px;">
                                <span>${escapeHtml(tm.sender || '')} → ${escapeHtml(tm.recipient || '')}</span>
                                <small style="color:#94a3b8;">${escapeHtml(tm.timestamp || '')}</small>
                            </div>
                            <div style="white-space:pre-wrap;color:#cbd5e1;max-height:120px;overflow-y:auto;">${escapeHtml(cleanText)}</div>
                        </div>
                    `;
                }).join('')}
            `;
        } catch (e) {}
    }

    // =========================================================================
    // 9. ĐẶT SAO (ƯU TIÊN LÊN ĐẦU), BÁO THƯ RÁC 1 CHẠM (⚠️), XÓA & KHÔI PHỤC
    // =========================================================================
    async function toggleStar(mailId, starred) {
        const target = STATE.currentMails.find(x => x.id === mailId);
        if (target) {
            target.starred = starred;
            if (!starred && STATE.currentFolder === 'starred') {
                STATE.currentMails = STATE.currentMails.filter(x => x.id !== mailId);
            } else {
                STATE.currentMails = sortMailsStarredFirst(STATE.currentMails);
            }
            renderMailList(STATE.currentMails);
        }

        const body = new URLSearchParams({
            id: mailId,
            folder: STATE.currentFolder,
            starred: String(starred)
        });
        const res = await origFetch('/api/mails/flags', {
            method: 'POST',
            headers: authHeaders(true),
            body: body.toString()
        });
        if (!res.ok) return;

        if (STATE.selectedMail && STATE.selectedMail.id === mailId) {
            STATE.selectedMail.starred = starred;
            const { detailCol } = getLayoutContainers();
            if (detailCol) injectDetailToolbar(detailCol, STATE.selectedMail);
        }
        showToast(starred
            ? '⭐ Đã đặt sao: Thư được ưu tiên lên đầu & thêm vào "Thư gắn sao"!'
            : 'Đã bỏ dấu sao khỏi thư');
        await loadFolderMails(STATE.currentFolder, false);
    }

    /**
     * Báo cáo Thư rác 1 chạm (không hỏi confirm gây cản trở) -> Chuyển ngay vào Thư rác & Hạn chế địa chỉ đó.
     */
    async function reportSpamAndBlockSender(mailId) {
        const body = new URLSearchParams({ id: mailId, folder: STATE.currentFolder });
        const res = await origFetch('/api/mails/report-spam', {
            method: 'POST',
            headers: authHeaders(true),
            body: body.toString()
        });
        if (!res.ok) return;
        const data = await res.json();
        if (data.success) {
            showToast(data.message || '⚠️ Đã chuyển vào Thư rác & hạn chế địa chỉ này!');
            if (STATE.selectedMail && STATE.selectedMail.id === mailId) {
                STATE.selectedMail = null;
                const { detailCol } = getLayoutContainers();
                if (detailCol) {
                    detailCol.innerHTML = '<div style="height:100%;display:flex;align-items:center;justify-content:center;color:#fbbf24;font-size:15px;">⚠️ Đã chuyển thư vào mục Thư rác & kích hoạt hạn chế tự động.</div>';
                }
            }
            await loadFolderMails(STATE.currentFolder, false);
        }
    }

    async function unspamAndUnblockSender(mailId) {
        const body = new URLSearchParams({ id: mailId });
        const res = await origFetch('/api/mails/unspam', {
            method: 'POST',
            headers: authHeaders(true),
            body: body.toString()
        });
        if (!res.ok) return;
        const data = await res.json();
        if (data.success) {
            showToast(data.message || '✅ Đã bỏ hạn chế và đưa thư về Hộp thư đến!');
            if (STATE.selectedMail && STATE.selectedMail.id === mailId) {
                STATE.selectedMail = null;
                const { detailCol } = getLayoutContainers();
                if (detailCol) {
                    detailCol.innerHTML = '<div style="height:100%;display:flex;align-items:center;justify-content:center;color:#22c55e;">✅ Thư đã được chuyển về Hộp thư đến.</div>';
                }
            }
            await loadFolderMails(STATE.currentFolder, false);
        }
    }

    async function deleteMail(mailId) {
        const isTrash = STATE.currentFolder === 'trash';
        if (isTrash && !confirm('Xóa vĩnh viễn thư này khỏi Thùng rác?')) return;

        const body = new URLSearchParams({ id: mailId, folder: STATE.currentFolder });
        const res = await origFetch('/api/mails/delete', {
            method: 'POST',
            headers: authHeaders(true),
            body: body.toString()
        });
        if (!res.ok) return;
        const data = await res.json();
        if (data.success) {
            showToast(data.message || '🗑️ Đã xóa thư!');
            if (STATE.selectedMail && STATE.selectedMail.id === mailId) {
                STATE.selectedMail = null;
                const { detailCol } = getLayoutContainers();
                if (detailCol) {
                    detailCol.innerHTML = '<div style="height:100%;display:flex;align-items:center;justify-content:center;color:#64748b;">Thư đã được xóa.</div>';
                }
            }
            await loadFolderMails(STATE.currentFolder, false);
            await refreshQuotaBar();
        }
    }

    async function restoreMail(mailId) {
        const body = new URLSearchParams({ id: mailId, targetFolder: 'inbox' });
        const res = await origFetch('/api/mails/restore', {
            method: 'POST',
            headers: authHeaders(true),
            body: body.toString()
        });
        if (!res.ok) return;
        const data = await res.json();
        if (data.success) {
            showToast('♻️ Đã khôi phục thư về Hộp thư đến!');
            await loadFolderMails(STATE.currentFolder, false);
        }
    }

    async function markUnread(mailId) {
        const body = new URLSearchParams({
            id: mailId,
            folder: STATE.currentFolder,
            seen: 'false'
        });
        const res = await origFetch('/api/mails/flags', {
            method: 'POST',
            headers: authHeaders(true),
            body: body.toString()
        });
        if (!res.ok) return;
        showToast('📩 Đã đánh dấu là chưa đọc');
        await loadFolderMails(STATE.currentFolder, false);
    }

    // =========================================================================
    // 10. HỘP THOẠI "THƯ MỚI": KÍCH HOẠT NGAY KHI NHẬP NGƯỜI NHẬN & LƯU NHÁP KHI BẤM DẤU "X"
    // =========================================================================
    function resetDraftBuffer() {
        STATE.draftBuffer = { to: '', subject: '', body: '', dirty: false };
    }

    function syncInputsToDraftBuffer() {
        const { toInput, subjInput, bodyInput, modalEl } = findComposeInputs();
        if (!toInput && !subjInput && !bodyInput) return;

        const to = toInput ? toInput.value.trim() : '';
        const subject = subjInput ? subjInput.value.trim() : '';
        const body = bodyInput ? bodyInput.value.trim() : '';

        // Chỉ cần bắt đầu có mail người nhận (hoặc chủ đề / nội dung) là kích hoạt ngay!
        if (to.length > 0 || subject.length > 0 || body.length > 0) {
            STATE.draftBuffer = { to, subject, body, dirty: true };
        }
        updateComposeHelperBar(modalEl, to, subject, body);
    }

    function updateComposeHelperBar(modalEl, to, subject, body) {
        if (!modalEl) return;
        let bar = modalEl.querySelector('#dut-compose-helper');
        if (!bar) {
            bar = document.createElement('div');
            bar.id = 'dut-compose-helper';
            bar.className = 'dut-compose-helper';
            const bodyArea = modalEl.querySelector('textarea');
            if (bodyArea && bodyArea.parentElement) {
                bodyArea.insertAdjacentElement('afterend', bar);
            } else {
                modalEl.appendChild(bar);
            }
        }

        const hasRecipient = Boolean(to && to.trim().length > 0);
        const hasAny = hasRecipient || Boolean((subject && subject.trim()) || (body && body.trim()));

        bar.innerHTML = `
            <div>
                ${hasAny
                    ? `<span style="color:#a78bfa;font-weight:600;">📝 Đã kích hoạt Thư nháp:</span> Bấm dấu <strong>×</strong> (hoặc Hủy) sẽ tự động lưu vào <strong>Thư nháp</strong>.`
                    : `<span style="color:#94a3b8;">Nhập địa chỉ người nhận để kích hoạt tự động lưu Thư nháp & kiểm soát Thư rác.</span>`
                }
            </div>
            ${hasRecipient ? `
                <button type="button" class="dut-btn dut-btn-warn" id="dut-compose-spam-btn" style="padding:4px 10px;font-size:12px;">
                    ⚠️ Đưa vào Thư rác & Hạn chế (${escapeHtml(to.split(/[\s,;]+/)[0])})
                </button>
            ` : ''}
        `;

        const spamBtn = bar.querySelector('#dut-compose-spam-btn');
        if (spamBtn) {
            spamBtn.onclick = async (e) => {
                e.preventDefault();
                e.stopPropagation();
                await triggerComposeSpamDirectly();
            };
        }
    }

    async function triggerComposeSpamDirectly() {
        syncInputsToDraftBuffer();
        const { to, subject, body } = STATE.draftBuffer;
        if (!to) {
            showToast('Vui lòng nhập mail người nhận trước!');
            return;
        }
        STATE.isMailSent = true;
        resetDraftBuffer();
        const formData = new URLSearchParams({
            to,
            subject: subject || '(Thư bị hạn chế / Thư rác)',
            body: body || ''
        });
        const res = await origFetch('/api/mails/report-spam', {
            method: 'POST',
            headers: authHeaders(true),
            body: formData.toString()
        });
        if (res.ok) {
            const data = await res.json();
            showToast(data.message || `⚠️ Đã kích hoạt Thư rác cho ${to}!`);
            // Đóng hộp thoại Thư mới
            const { modalEl, toInput, subjInput, bodyInput } = findComposeInputs();
            if (toInput) toInput.value = '';
            if (subjInput) subjInput.value = '';
            if (bodyInput) bodyInput.value = '';
            if (modalEl) {
                const closeBtn = Array.from(modalEl.querySelectorAll('button, span, a, i')).find(el => {
                    const t = (el.textContent || '').trim();
                    return t === '×' || t === '✕' || t === 'Hủy';
                });
                if (closeBtn) closeBtn.click();
            }
            setTimeout(() => { STATE.isMailSent = false; }, 300);
            await loadFolderMails(STATE.currentFolder, false);
        }
    }

    async function saveDraftIfUnsent() {
        if (STATE.savingDraftLock || STATE.isMailSent) return;
        syncInputsToDraftBuffer();
        const { to, subject, body, dirty } = STATE.draftBuffer;
        if (!dirty || (!to && !subject && !body)) return;

        const sig = `${to}|${subject}|${body}`;
        const now = Date.now();
        if (sig === STATE.lastSavedSignature && now - STATE.lastSavedAt < 2000) {
            resetDraftBuffer();
            return;
        }

        STATE.savingDraftLock = true;
        STATE.lastSavedSignature = sig;
        STATE.lastSavedAt = now;
        resetDraftBuffer();

        try {
            const formData = new URLSearchParams({ to, subject, body });
            if (STATE.activeDraftId) {
                formData.append('draftId', STATE.activeDraftId);
                STATE.activeDraftId = null;
            }
            const res = await origFetch('/api/mails/save-draft', {
                method: 'POST',
                headers: authHeaders(true),
                body: formData.toString()
            });
            if (res.ok) {
                showToast('📝 Đã tự động lưu vào mục "Thư nháp"!');
                if (STATE.currentFolder === 'drafts') {
                    await loadFolderMails('drafts', false);
                }
                await refreshQuotaBar();
            }
        } catch (err) {
            console.error('[DUT Mail] Lỗi lưu nháp:', err);
        } finally {
            setTimeout(() => { STATE.savingDraftLock = false; }, 350);
        }
    }

    function openComposeModal() {
        STATE.isMailSent = false;
        const composeBtn = Array.from(document.querySelectorAll('button, a, div')).find(b =>
            b.children.length <= 2 && /Soạn thư mới/i.test((b.textContent || '').trim())
        );
        if (composeBtn) composeBtn.click();
    }

    function openReplyCompose(mail) {
        if (!mail) return;
        openComposeModal();
        setTimeout(() => {
            const { toInput, subjInput, bodyInput } = findComposeInputs();
            let subj = (mail.subject || '').trim();
            if (!/^Re:/i.test(subj)) subj = 'Re: ' + subj;

            const { cleanText } = parseAttachmentsFromBody(mail.body || '');
            const quoted = `\n\n--- Vào ${mail.timestamp || ''}, ${mail.sender || ''} đã viết: ---\n` +
                cleanText.split('\n').map(l => `> ${l}`).join('\n');

            if (toInput) toInput.value = mail.sender || '';
            if (subjInput) subjInput.value = subj;
            if (bodyInput) {
                bodyInput.value = quoted;
                bodyInput.focus();
                bodyInput.setSelectionRange(0, 0);
            }
            syncInputsToDraftBuffer();

            STATE.replyContext = {
                inReplyTo: mail.messageId || mail.id,
                references: mail.references ? (mail.references + ' ' + (mail.messageId || mail.id)) : (mail.messageId || mail.id)
            };
            showToast('↩️ Đang trả lời thư của ' + (mail.sender || ''));
        }, 120);
    }

    function openDraftToEdit(mail) {
        if (!mail) return;
        openComposeModal();
        setTimeout(() => {
            const { toInput, subjInput, bodyInput } = findComposeInputs();
            const { cleanText } = parseAttachmentsFromBody(mail.body || '');
            if (toInput) toInput.value = mail.recipient || '';
            if (subjInput) subjInput.value = (mail.subject === '(Thư nháp)' ? '' : (mail.subject || ''));
            if (bodyInput) bodyInput.value = cleanText;
            STATE.activeDraftId = mail.id;
            syncInputsToDraftBuffer();
            showToast('📝 Đã mở lại thư nháp để tiếp tục soạn');
        }, 120);
    }

    function hookComposeCloseForDrafts() {
        // Lắng nghe mọi thao tác gõ/dán vào các ô trong hộp thoại "Thư mới"
        const onInputChange = (e) => {
            const t = e.target;
            if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA')) {
                const { toInput, subjInput, bodyInput } = findComposeInputs();
                if (t === toInput || t === subjInput || t === bodyInput) {
                    STATE.isMailSent = false;
                    syncInputsToDraftBuffer();
                }
            }
        };
        document.addEventListener('input', onInputChange, true);
        document.addEventListener('keyup', onInputChange, true);
        document.addEventListener('change', onInputChange, true);
        document.addEventListener('paste', () => setTimeout(syncInputsToDraftBuffer, 20), true);

        // Bắt ngay hành động bấm dấu "×" (góc trên phải) hoặc nút "Hủy" (góc dưới phải) TRƯỚC KHI app.js xóa trắng ô input
        const onCaptureCloseAction = (e) => {
            const { modalEl, toInput, subjInput, bodyInput } = findComposeInputs();
            if (!toInput && !subjInput && !bodyInput) return;

            const btn = e.target.closest('button, span, a, i, svg, div');
            if (!btn) return;

            const rawText = (btn.textContent || '').trim();
            if (/Gửi thư|^Gửi$|^Send$/i.test(rawText) || btn.type === 'submit') {
                STATE.isMailSent = true;
                return;
            }
            if (/Đính kèm|Đưa vào Thư rác/i.test(rawText) || btn.id === 'dut-compose-spam-btn') {
                return;
            }

            const cls = ((btn.className && typeof btn.className === 'string') ? btn.className : '') + ' ' +
                        ((btn.parentElement && typeof btn.parentElement.className === 'string') ? btn.parentElement.className : '');
            const onclickStr = (btn.getAttribute('onclick') || '') + ' ' + (btn.parentElement ? btn.parentElement.getAttribute('onclick') || '' : '');
            const hasCloseIcon = Boolean(btn.querySelector && btn.querySelector('.fa-times, .fa-xmark, .fa-close, [class*="times"], [class*="close"]'));

            const isCloseBtn =
                rawText === '×' || rawText === '✕' || rawText === '✖' || rawText === 'x' || rawText === 'X' ||
                /^(Hủy|Đóng|Cancel|Close)$/i.test(rawText) ||
                /\b(close|cancel|fa-times|fa-xmark|btn-close|modal-close)\b/i.test(cls) ||
                /close|hide|cancel/i.test(onclickStr) ||
                hasCloseIcon;

            if (isCloseBtn && (!modalEl || modalEl.contains(btn))) {
                syncInputsToDraftBuffer();
                saveDraftIfUnsent();
            }
        };

        document.addEventListener('pointerdown', onCaptureCloseAction, true);
        document.addEventListener('mousedown', onCaptureCloseAction, true);
        document.addEventListener('click', onCaptureCloseAction, true);

        // Vòng lặp giám sát trạng thái hiển thị của hộp thoại "Thư mới":
        // Vừa giúp gắn trực tiếp sự kiện vào nút × / Hủy, vừa tự lưu nháp nếu hộp thoại đóng bằng phím ESC hoặc click ra ngoài
        setInterval(() => {
            const { modalEl, toInput, subjInput, bodyInput } = findComposeInputs();
            if (!modalEl) return;

            const rect = modalEl.getBoundingClientRect();
            const style = window.getComputedStyle(modalEl);
            const isVisible = rect.width > 0 && rect.height > 0 && style.display !== 'none' && style.visibility !== 'hidden' && style.opacity !== '0';
            const wasVisible = modalEl.dataset.dutWasVisible === '1';

            if (isVisible) {
                modalEl.dataset.dutWasVisible = '1';
                const toVal = toInput ? toInput.value.trim() : '';
                const subjVal = subjInput ? subjInput.value.trim() : '';
                const bodyVal = bodyInput ? bodyInput.value.trim() : '';
                if (toVal || subjVal || bodyVal) {
                    STATE.draftBuffer = { to: toVal, subject: subjVal, body: bodyVal, dirty: true };
                }
                updateComposeHelperBar(modalEl, toVal, subjVal, bodyVal);
            } else if (wasVisible && !isVisible) {
                modalEl.dataset.dutWasVisible = '0';
                saveDraftIfUnsent();
            }
        }, 200);
    }

    // =========================================================================
    // 11. CẬP NHẬT THANH DUNG LƯỢNG HỘP THƯ (QUOTA 100 MB)
    // =========================================================================
    async function refreshQuotaBar() {
        try {
            const res = await origFetch('/api/mails/storage-usage', {
                headers: authHeaders(false)
            });
            if (!res.ok) return;
            const data = await res.json();
            if (!data.success) return;

            const allNodes = Array.from(document.querySelectorAll('span, div, p, small'));
            const quotaLabel = allNodes.find(el => el.children.length === 0 && /MB\s*\/\s*\d+\s*MB/i.test(el.textContent || ''));
            if (quotaLabel) {
                quotaLabel.textContent = `${Number(data.usedMb || 0).toFixed(2)} MB / ${data.quotaMb || 100} MB`;
            }
        } catch (err) {}
    }

    window.DUTMail = {
        switchFolder,
        executeResearch,
        openDetail,
        openReplyCompose,
        deleteMail,
        restoreMail,
        toggleStar,
        reportSpamAndBlockSender,
        unspamAndUnblockSender,
        markUnread,
        refreshQuotaBar
    };

    function boot() {
        injectStyles();
        enhanceSidebar();
        enhanceSearchAndRefresh();
        hookComposeCloseForDrafts();
        observeMailListContainer();
        switchFolder('inbox');
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', () => setTimeout(boot, 100));
    } else {
        setTimeout(boot, 100);
    }
})();
