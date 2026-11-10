(function () {
    'use strict';

    if (window.__DUT_MAIL_V7_CLEAN__) return;
    window.__DUT_MAIL_V7_CLEAN__ = true;

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

            const bulkHeader = (isSpamFolder || isTrashFolder) ? `
                <div style="padding:10px 15px;border-bottom:1px solid rgba(255,255,255,0.08);display:flex;justify-content:space-between;align-items:center;background:rgba(239,68,68,0.08);">
                    <span style="font-size:12.5px;color:#fca5a5;">Đang có ${sorted.length} thư trong ${escapeHtml(FOLDER_LABELS[STATE.currentFolder])}</span>
                    <button type="button" class="dut-btn dut-btn-danger" id="dut-empty-folder-btn" style="padding:4px 10px;font-size:12px;">
                        🧹 Dọn sạch ${escapeHtml(FOLDER_LABELS[STATE.currentFolder])}
                    </button>
                </div>
            ` : '';
            mailListEl.innerHTML = bulkHeader + sorted.map(m => {
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

            const emptyBtn = mailListEl.querySelector('#dut-empty-folder-btn');
            if (emptyBtn) {
                emptyBtn.addEventListener('click', (e) => {
                    e.stopPropagation();
                    emptyCurrentFolder();
                });
            }
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
        const regex = /\[ATTACHMENT:([^\]\r\n]+)\]([\s\S]*?)\[\/ATTACHMENT\]/g;
        let match;
        while ((match = regex.exec(text)) !== null) {
            let b64 = (match[2] || '').trim();
            const prefixMatch = b64.match(/^data:([^;]+);base64,/i);
            if (prefixMatch) {
                b64 = b64.slice(prefixMatch[0].length);
            }
            b64 = b64.replace(/\s+/g, '');
            attachments.push({
                name: match[1].trim(),
                base64: b64
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
                        ${attachments.map((a, idx) => {
                            const lower = (a.name || '').toLowerCase();
                            const isImg = /\.(png|jpg|jpeg|gif|webp|bmp|svg)$/.test(lower);
                            const isPdf = /\.pdf$/.test(lower);
                            return `
                            <div style="padding:10px 14px;border-radius:10px;background:rgba(255,255,255,0.04);border:1px solid rgba(255,255,255,0.1);display:inline-flex;flex-direction:column;gap:8px;">
                                <div style="font-size:13.5px;color:#e2e8f0;font-weight:500;">📎 ${escapeHtml(a.name)}</div>
                                <div style="display:flex;gap:6px;">
                                    ${(isImg || isPdf) ? `
                                        <button type="button" class="dut-btn" data-preview-idx="${idx}" style="font-size:12px;padding:5px 10px;">
                                            👁️ Xem trước
                                        </button>
                                    ` : ''}
                                    <a href="data:application/octet-stream;base64,${a.base64}" download="${escapeHtml(a.name)}"
                                       class="dut-btn" style="text-decoration:none;justify-content:center;font-size:12px;padding:5px 10px;">
                                       ⬇️ Tải về
                                    </a>
                                </div>
                            </div>
                            `;
                        }).join('')}
                    </div>
                    <div id="dut-attachment-preview-box" style="margin-top:12px;"></div>
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
                    <div style="margin-top:6px;">
                        <span style="display:inline-flex;align-items:center;gap:6px;padding:3px 10px;border-radius:999px;font-size:12px;font-weight:600;background:rgba(34,197,94,0.14);color:#86efac;border:1px solid rgba(34,197,94,0.35);">
                            🛡️ ${escapeHtml(mail.securityBadge || 'AES-256-CBC | Đã xác thực bảo mật (SPF/DKIM/DMARC)')}
                        </span>
                    </div>
                </div>
                <div style="margin-top: 16px; font-size: 14.5px; line-height: 1.65; color: #e2e8f0; white-space: pre-wrap; word-break: break-word;">${escapeHtml(cleanText)}</div>
                ${attHtml}

            </div>
        `;

        detailCol.querySelectorAll('[data-preview-idx]').forEach(btn => {
            btn.addEventListener('click', () => {
                const idx = Number(btn.getAttribute('data-preview-idx'));
                const att = attachments[idx];
                const box = detailCol.querySelector('#dut-attachment-preview-box');
                if (!att || !box) return;
                const lower = (att.name || '').toLowerCase();
                if (/\.pdf$/.test(lower)) {
                    box.innerHTML = `<iframe src="data:application/pdf;base64,${att.base64}" style="width:100%;height:480px;border:1px solid rgba(255,255,255,0.15);border-radius:10px;background:#fff;"></iframe>`;
                } else {
                    box.innerHTML = `<img src="data:image/png;base64,${att.base64}" alt="${escapeHtml(att.name)}" style="max-width:100%;max-height:480px;border-radius:10px;border:1px solid rgba(255,255,255,0.15);" />`;
                }
            });
        });

        injectDetailToolbar(detailCol, mail);

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
            ${!isDraft ? `
                <button type="button" class="dut-btn" id="dut-btn-forward">
                    ↪️ Chuyển tiếp
                </button>
            ` : ''}
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
        const fwdBtn = toolbar.querySelector('#dut-btn-forward');
        if (fwdBtn) fwdBtn.onclick = () => openForwardCompose(mail);
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

    function openForwardCompose(mail) {
        if (!mail) return;
        openComposeModal();
        setTimeout(() => {
            const { toInput, subjInput, bodyInput } = findComposeInputs();
            let subj = (mail.subject || '').trim();
            if (!/^Fwd:/i.test(subj)) subj = 'Fwd: ' + subj;

            const { cleanText } = parseAttachmentsFromBody(mail.body || '');
            const fwdText = `\n\n---------- Thư chuyển tiếp ----------\n`
                + `Từ: ${mail.sender || ''}\n`
                + `Thời gian: ${mail.timestamp || ''}\n`
                + `Chủ đề: ${mail.subject || ''}\n`
                + `Tới: ${mail.recipient || ''}\n\n`
                + cleanText;

            if (toInput) {
                toInput.value = '';
                toInput.focus();
            }
            if (subjInput) subjInput.value = subj;
            if (bodyInput) bodyInput.value = fwdText;
            syncInputsToDraftBuffer();
            showToast('↪️ Đang chuyển tiếp thư: Hãy nhập người nhận mới');
        }, 120);
    }

    async function emptyCurrentFolder() {
        const label = FOLDER_LABELS[STATE.currentFolder] || STATE.currentFolder;
        if (!confirm(`🧹 Dọn sạch toàn bộ thư trong mục "${label}"?`)) return;
        const body = new URLSearchParams({ folder: STATE.currentFolder, emptyFolder: 'true' });
        const res = await origFetch('/api/mails/delete', {
            method: 'POST',
            headers: authHeaders(true),
            body: body.toString()
        });
        if (!res.ok) return;
        const data = await res.json();
        if (data.success) {
            showToast(data.message || `🧹 Đã dọn sạch ${label}!`);
            STATE.selectedMail = null;
            const { detailCol } = getLayoutContainers();
            if (detailCol) {
                detailCol.innerHTML = '<div style="height:100%;display:flex;align-items:center;justify-content:center;color:#64748b;">Đã dọn sạch thư mục.</div>';
            }
            await loadFolderMails(STATE.currentFolder, false);
            await refreshQuotaBar();
        }
    }

    // =========================================================================
    // 12. BẢO MẬT KHÔI PHỤC MẬT KHẨU ONLINE:
    //  - Bộ 5 Mã dự phòng 8 số (8-Digit Backup Codes - Dùng 1 lần/mã)
    //  - Liên kết Email khôi phục (Nhận OTP 6 số thật qua hòm thư, KHÔNG lộ trên UI)
    //  - Câu hỏi bảo mật cá nhân
    //  - Đổi mật khẩu kiểm tra chặt ngoại lệ trùng mật khẩu cũ (SAME_AS_OLD_PASSWORD)
    // =========================================================================

    function enhanceLoginPageForgotPassword() {
        const uglyBox = document.getElementById('dut-forgot-trigger-btn');
        if (uglyBox) uglyBox.remove();
        const floatBtn = document.getElementById('dut-floating-forgot-btn');
        if (floatBtn) floatBtn.remove();

        if (document.querySelector('input[placeholder*="Tìm kiếm"]')) return;
        if (document.getElementById('dut-forgot-clean-link')) return;

        const passInput = document.querySelector('#login-password, #login-form input[type="password"]');
        if (!passInput) return;
        const anchor = passInput.closest('.input-group') || passInput;

        const linkWrap = document.createElement('div');
        linkWrap.id = 'dut-forgot-clean-link';
        linkWrap.style.cssText = 'text-align:right;margin:-6px 2px 12px 0;';
        linkWrap.innerHTML = `<a href="#" style="color:#a78bfa;font-size:13px;text-decoration:none;font-weight:500;">Quên mật khẩu?</a>`;
        anchor.insertAdjacentElement('afterend', linkWrap);

        linkWrap.querySelector('a').onclick = (e) => {
            e.preventDefault();
            openPublicForgotPasswordModal();
        };
    }

    function enhanceChangePasswordButton() {
        const trashBtn = document.getElementById('dut-folder-trash');
        if (!trashBtn || document.getElementById('dut-change-pass-btn')) return;

        const btn = trashBtn.cloneNode(true);
        btn.id = 'dut-change-pass-btn';
        btn.removeAttribute('data-folder');
        btn.classList.remove('active');
        btn.innerHTML = `<span style="margin-right:10px;">🛡️</span><span>Bảo mật & Mã dự phòng</span>`;
        btn.onclick = (e) => {
            e.preventDefault();
            e.stopPropagation();
            openAuthenticatedSecurityModal('backup');
        };
        trashBtn.parentElement.appendChild(btn);
    }

    /**
     * Tự động kiểm tra ngay khi người dùng đăng nhập vào main.html:
     * Nếu tài khoản chưa tạo Bộ 5 Mã dự phòng 8 số -> Hiển thị thanh nhắc nhở nổi bật để tạo ngay!
     */
    async function checkAndPromptBackupCodesOnLogin() {
        if (!getToken() || !document.querySelector('input[placeholder*="Tìm kiếm"]')) return;
        try {
            const res = await origFetch('/api/auth/recovery-status', { headers: authHeaders(false) });
            if (!res.ok) return;
            const status = await res.json();
            if (!status.success) return;

            const existingBanner = document.getElementById('dut-backup-prompt-banner');
            if (existingBanner) existingBanner.remove();

            if (!status.backupCodesConfigured || status.remainingBackupCodes === 0) {
                const { detailCol } = getLayoutContainers();
                const targetHost = (detailCol && detailCol.parentElement) ? detailCol.parentElement : document.body;

                const banner = document.createElement('div');
                banner.id = 'dut-backup-prompt-banner';
                banner.style.cssText = 'position:fixed;top:14px;right:20px;z-index:9998;max-width:460px;background:linear-gradient(135deg,#1e1b4b,#311042);border:1px solid #f59e0b;border-radius:12px;padding:12px 16px;color:#f8fafc;box-shadow:0 12px 32px rgba(0,0,0,0.55);display:flex;align-items:center;justify-content:space-between;gap:12px;';
                banner.innerHTML = `
                    <div style="font-size:12.5px;line-height:1.45;">
                        <strong style="color:#fde047;display:block;margin-bottom:2px;">🛡️ Thiết lập Mã dự phòng khôi phục mật khẩu!</strong>
                        Tài khoản của bạn chưa có <strong>5 Mã dự phòng (8 số)</strong> để lấy lại mật khẩu khi quên.
                    </div>
                    <div style="display:flex;gap:6px;flex-shrink:0;">
                        <button type="button" class="dut-btn dut-btn-primary" id="dut-banner-create-codes" style="padding:6px 11px;font-size:12px;background:#d97706;border-color:#f59e0b;">
                            ⚡ Tạo 5 mã ngay
                        </button>
                        <button type="button" id="dut-banner-dismiss" style="background:none;border:none;color:#94a3b8;font-size:18px;cursor:pointer;">×</button>
                    </div>
                `;
                document.body.appendChild(banner);

                banner.querySelector('#dut-banner-create-codes').onclick = () => {
                    banner.remove();
                    openAuthenticatedSecurityModal('backup');
                };
                banner.querySelector('#dut-banner-dismiss').onclick = () => banner.remove();
            }
        } catch (e) {}
    }

    /**
     * HỘP THOẠI DÀNH CHO NGƯỜI DÙNG ĐÃ ĐĂNG NHẬP (main.html):
     *  - Tab 1: Tạo & Lưu Bộ 5 Mã dự phòng 8 số + Cài đặt Email khôi phục & Câu hỏi bảo mật
     *  - Tab 2: Đổi mật khẩu cá nhân (Kiểm tra ngoại lệ trùng mật khẩu cũ)
     */
    async function openAuthenticatedSecurityModal(defaultTab) {
        let modal = document.getElementById('dut-auth-sec-modal');
        if (modal) modal.remove();

        let status = {
            username: '',
            backupCodesConfigured: false,
            remainingBackupCodes: 0,
            totalBackupCodes: 5,
            recoveryEmail: '',
            securityQuestion: '',
            hasSecurityQuestion: false
        };
        try {
            const res = await origFetch('/api/auth/recovery-status', { headers: authHeaders(false) });
            if (res.ok) status = await res.json();
        } catch (e) {}

        const activeTab = defaultTab || 'backup';

        modal = document.createElement('div');
        modal.id = 'dut-auth-sec-modal';
        modal.style.cssText = 'position:fixed;inset:0;background:rgba(0,0,0,0.74);z-index:99999;display:flex;align-items:center;justify-content:center;padding:16px;';
        modal.innerHTML = `
            <div style="background:#1e1b2e;border:1px solid rgba(139,92,246,0.45);border-radius:16px;max-width:520px;width:100%;max-height:92vh;overflow-y:auto;padding:22px 24px;color:#f8fafc;box-shadow:0 20px 50px rgba(0,0,0,0.65);">
                <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:14px;">
                    <h3 style="margin:0;font-size:18px;">🛡️ Bảo mật Tài khoản & Mã dự phòng Khôi phục</h3>
                    <button type="button" id="dut-as-close" style="background:none;border:none;color:#94a3b8;font-size:22px;cursor:pointer;line-height:1;">×</button>
                </div>

                <div style="display:flex;gap:8px;margin-bottom:16px;background:rgba(0,0,0,0.28);padding:5px;border-radius:10px;">
                    <button type="button" id="dut-as-tab-backup" style="flex:1;padding:8px 10px;border-radius:8px;border:none;font-weight:600;font-size:13px;cursor:pointer;background:${activeTab === 'backup' ? '#7c3aed' : 'transparent'};color:${activeTab === 'backup' ? '#fff' : '#94a3b8'};">
                        🔢 5 Mã dự phòng (8 số) & Khôi phục
                    </button>
                    <button type="button" id="dut-as-tab-change" style="flex:1;padding:8px 10px;border-radius:8px;border:none;font-weight:600;font-size:13px;cursor:pointer;background:${activeTab === 'change' ? '#7c3aed' : 'transparent'};color:${activeTab === 'change' ? '#fff' : '#94a3b8'};">
                        🔑 Đổi mật khẩu
                    </button>
                </div>

                <div id="dut-as-alert" style="display:none;margin-bottom:12px;padding:10px 12px;border-radius:10px;font-size:13px;line-height:1.5;"></div>

                <!-- TAB 1: BỘ 5 MÃ DỰ PHÒNG 8 SỐ & EMAIL KHÔI PHỤC / CÂU HỎI BẢO MẬT -->
                <div id="dut-as-pane-backup" style="display:${activeTab === 'backup' ? 'flex' : 'none'};flex-direction:column;gap:14px;">
                    <div style="padding:12px 14px;border-radius:10px;background:rgba(139,92,246,0.09);border:1px solid rgba(139,92,246,0.3);">
                        <div style="display:flex;justify-content:space-between;align-items:center;gap:8px;flex-wrap:wrap;">
                            <div>
                                <div style="font-weight:700;font-size:14px;color:#e9d5ff;">🔢 Phương thức 1: Bộ 5 Mã dự phòng (8 chữ số/mã)</div>
                                <div id="dut-backup-count-label" style="font-size:12.5px;color:${status.remainingBackupCodes > 0 ? '#86efac' : '#fde047'};margin-top:3px;">
                                    ${status.remainingBackupCodes > 0
                                        ? `✅ Đang có <strong>${status.remainingBackupCodes}/5</strong> mã dự phòng 8 số chưa sử dụng.`
                                        : `⚠️ Chưa có mã dự phòng! Hãy tạo ngay 5 mã dự phòng (8 số) để dùng khi quên mật khẩu.`
                                    }
                                </div>
                            </div>
                        </div>

                        ${status.remainingBackupCodes > 0 ? `
                            <div style="margin-top:10px;">
                                <input id="dut-gen-confirm-pass" type="password" placeholder="Nhập mật khẩu hiện tại để tạo bộ 5 mã mới (vô hiệu hóa mã cũ)" style="width:100%;box-sizing:border-box;padding:8px 11px;border-radius:8px;border:1px solid rgba(255,255,255,0.18);background:rgba(0,0,0,0.32);color:#fff;font-size:12.5px;" />
                            </div>
                        ` : ''}

                        <button type="button" class="dut-btn dut-btn-primary" id="dut-btn-gen-codes" style="width:100%;justify-content:center;margin-top:10px;padding:9px;">
                            ${status.remainingBackupCodes > 0 ? '🔄 Tạo lại bộ 5 Mã dự phòng (8 số) mới' : '⚡ Nhận ngay 5 Mã dự phòng (8 số)'}
                        </button>

                        <!-- Khung hiển thị 5 mã 8 số vừa tạo (Chỉ hiện cho chủ tài khoản đang đăng nhập) -->
                        <div id="dut-generated-codes-box" style="display:none;margin-top:12px;padding:12px;border-radius:10px;background:rgba(0,0,0,0.45);border:1px dashed #facc15;">
                            <div style="font-size:12px;color:#fde047;margin-bottom:8px;font-weight:600;">
                                📌 Hãy lưu lại 5 mã dự phòng (8 số) dưới đây vào nơi an toàn (mỗi mã dùng được 1 lần khi quên mật khẩu):
                            </div>
                            <div id="dut-codes-grid" style="display:grid;grid-template-columns:repeat(2, 1fr);gap:8px;font-family:monospace;font-size:15px;font-weight:700;color:#fff;"></div>
                            <div style="display:flex;gap:8px;margin-top:10px;">
                                <button type="button" class="dut-btn" id="dut-btn-copy-codes" style="flex:1;justify-content:center;font-size:12px;">📋 Sao chép 5 mã</button>
                                <button type="button" class="dut-btn" id="dut-btn-download-codes" style="flex:1;justify-content:center;font-size:12px;">⬇️ Tải file .txt về máy</button>
                            </div>
                        </div>
                    </div>

                    <!-- Phương thức 2 & 3: Liên kết Email khôi phục & Câu hỏi bảo mật cá nhân -->
                    <div style="padding:12px 14px;border-radius:10px;background:rgba(255,255,255,0.03);border:1px solid rgba(255,255,255,0.1);display:flex;flex-direction:column;gap:9px;">
                        <div style="font-weight:700;font-size:13.5px;color:#c4b5fd;">
                            📧 Phương thức 2 & 3 (Bổ sung): Email khôi phục nhận OTP & Câu hỏi bảo mật
                        </div>
                        <div style="font-size:12px;color:#94a3b8;">
                            Nếu quên mật khẩu mà không mang theo 5 mã dự phòng 8 số, bạn có thể nhận OTP 6 số qua Email khôi phục đã liên kết hoặc trả lời Câu hỏi bảo mật:
                        </div>
                        <input id="dut-rec-email" type="text" value="${escapeHtml(status.recoveryEmail || '')}" placeholder="Email khôi phục liên kết (VD: banbe@pbl4.com)" style="padding:9px 11px;border-radius:8px;border:1px solid rgba(255,255,255,0.16);background:rgba(0,0,0,0.3);color:#fff;font-size:13px;" />
                        <input id="dut-sec-question" type="text" value="${escapeHtml(status.securityQuestion || '')}" placeholder="Câu hỏi bảo mật (VD: Mã số sinh viên / Tên thú cưng của bạn?)" style="padding:9px 11px;border-radius:8px;border:1px solid rgba(255,255,255,0.16);background:rgba(0,0,0,0.3);color:#fff;font-size:13px;" />
                        <input id="dut-sec-answer" type="password" placeholder="${status.hasSecurityQuestion ? 'Nhập câu trả lời mới (Để trống nếu giữ nguyên)' : 'Câu trả lời bảo mật của bạn'}" style="padding:9px 11px;border-radius:8px;border:1px solid rgba(255,255,255,0.16);background:rgba(0,0,0,0.3);color:#fff;font-size:13px;" />
                        <input id="dut-rec-curpass" type="password" placeholder="Nhập mật khẩu hiện tại để xác nhận lưu cài đặt" style="padding:9px 11px;border-radius:8px;border:1px solid rgba(255,255,255,0.22);background:rgba(0,0,0,0.38);color:#fff;font-size:13px;" />
                        <button type="button" class="dut-btn dut-btn-primary" id="dut-btn-save-rec-settings" style="justify-content:center;padding:9px;background:#2563eb;border-color:#2563eb;">
                            💾 Lưu Email khôi phục & Câu hỏi bảo mật
                        </button>
                    </div>
                </div>

                <!-- TAB 2: ĐỔI MẬT KHẨU CÁ NHÂN (KIỂM TRA TRÙNG MẬT KHẨU CŨ) -->
                <div id="dut-as-pane-change" style="display:${activeTab === 'change' ? 'flex' : 'none'};flex-direction:column;gap:10px;">
                    <div style="font-size:12.5px;color:#cbd5e1;background:rgba(255,255,255,0.04);padding:9px 12px;border-radius:8px;">
                        ⚠️ <strong>Quy tắc bảo mật:</strong> Mật khẩu mới (8–32 ký tự, có chữ, số, ký tự đặc biệt) <strong>bắt buộc không được trùng với mật khẩu cũ</strong>.
                    </div>
                    <input id="dut-cp-old" type="password" placeholder="Nhập mật khẩu hiện tại (Mật khẩu cũ)" style="padding:10px 12px;border-radius:8px;border:1px solid rgba(255,255,255,0.18);background:rgba(0,0,0,0.32);color:#fff;" />
                    <input id="dut-cp-new" type="password" placeholder="Nhập mật khẩu mới (Khác mật khẩu cũ)" style="padding:10px 12px;border-radius:8px;border:1px solid rgba(255,255,255,0.18);background:rgba(0,0,0,0.32);color:#fff;" />
                    <input id="dut-cp-confirm" type="password" placeholder="Nhập lại mật khẩu mới để xác nhận" style="padding:10px 12px;border-radius:8px;border:1px solid rgba(255,255,255,0.18);background:rgba(0,0,0,0.32);color:#fff;" />
                    <button type="button" class="dut-btn dut-btn-primary" id="dut-cp-save" style="justify-content:center;padding:10px;font-size:14px;margin-top:4px;">
                        💾 Cập nhật mật khẩu mới
                    </button>
                </div>
            </div>
        `;
        document.body.appendChild(modal);

        const alertBox = modal.querySelector('#dut-as-alert');
        function showAlert(html, isErr) {
            alertBox.style.display = 'block';
            alertBox.style.background = isErr ? 'rgba(239,68,68,0.16)' : 'rgba(34,197,94,0.16)';
            alertBox.style.border = isErr ? '1px solid rgba(239,68,68,0.45)' : '1px solid rgba(34,197,94,0.45)';
            alertBox.style.color = isErr ? '#fca5a5' : '#86efac';
            alertBox.innerHTML = html;
        }

        modal.querySelector('#dut-as-close').onclick = () => modal.remove();

        const tabBackup = modal.querySelector('#dut-as-tab-backup');
        const tabChange = modal.querySelector('#dut-as-tab-change');
        const paneBackup = modal.querySelector('#dut-as-pane-backup');
        const paneChange = modal.querySelector('#dut-as-pane-change');

        tabBackup.onclick = () => {
            alertBox.style.display = 'none';
            paneBackup.style.display = 'flex';
            paneChange.style.display = 'none';
            tabBackup.style.background = '#7c3aed';
            tabBackup.style.color = '#fff';
            tabChange.style.background = 'transparent';
            tabChange.style.color = '#94a3b8';
        };
        tabChange.onclick = () => {
            alertBox.style.display = 'none';
            paneBackup.style.display = 'none';
            paneChange.style.display = 'flex';
            tabChange.style.background = '#7c3aed';
            tabChange.style.color = '#fff';
            tabBackup.style.background = 'transparent';
            tabBackup.style.color = '#94a3b8';
        };

        // Sinh bộ 5 Mã dự phòng 8 số
        let lastGeneratedCodes = [];
        modal.querySelector('#dut-btn-gen-codes').onclick = async () => {
            const passEl = modal.querySelector('#dut-gen-confirm-pass');
            const currentPassword = passEl ? passEl.value : '';
            const res = await origFetch('/api/auth/backup-codes/generate', {
                method: 'POST',
                headers: authHeaders(true),
                body: new URLSearchParams({ currentPassword }).toString()
            });
            const data = await res.json();
            if (!data.success) {
                showAlert(escapeHtml(data.message || 'Không thể tạo mã dự phòng!'), true);
                return;
            }
            lastGeneratedCodes = Array.isArray(data.codes) ? data.codes : [];
            const box = modal.querySelector('#dut-generated-codes-box');
            const grid = modal.querySelector('#dut-codes-grid');
            box.style.display = 'block';
            grid.innerHTML = lastGeneratedCodes.map((c, idx) => `
                <div style="padding:7px 10px;border-radius:6px;background:rgba(139,92,246,0.18);border:1px solid rgba(139,92,246,0.4);text-align:center;letter-spacing:1.5px;">
                    #${idx + 1}: ${escapeHtml(c.slice(0, 4) + ' ' + c.slice(4))}
                </div>
            `).join('');
            modal.querySelector('#dut-backup-count-label').innerHTML = `✅ Đang có <strong>5/5</strong> mã dự phòng 8 số chưa sử dụng.`;
            const banner = document.getElementById('dut-backup-prompt-banner');
            if (banner) banner.remove();
            showAlert(escapeHtml(data.message || 'Đã tạo 5 mã dự phòng 8 số!'), false);
        };

        modal.querySelector('#dut-btn-copy-codes').onclick = () => {
            if (!lastGeneratedCodes.length) return;
            navigator.clipboard.writeText(lastGeneratedCodes.join('\n')).then(() => {
                showToast('📋 Đã sao chép 5 mã dự phòng (8 số) vào Clipboard!');
            }).catch(() => {});
        };

        modal.querySelector('#dut-btn-download-codes').onclick = () => {
            if (!lastGeneratedCodes.length) return;
            const content = `=== BỘ 5 MÃ DỰ PHÒNG KHÔI PHỤC MẬT KHẨU (8 SỐ) ===\n`
                + `Tài khoản: ${status.username || ''}\n`
                + `Lưu ý: Mỗi mã gồm 8 chữ số và chỉ sử dụng được 1 lần khi quên mật khẩu.\n\n`
                + lastGeneratedCodes.map((c, i) => `Mã dự phòng #${i + 1}: ${c}`).join('\n') + `\n`;
            const blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
            const a = document.createElement('a');
            a.href = URL.createObjectURL(blob);
            a.download = `Ma_Du_Phong_8_So_${(status.username || 'dutmail').replace(/[^a-z0-9]/gi, '_')}.txt`;
            a.click();
            showToast('⬇️ Đã tải file 5 mã dự phòng 8 số về máy!');
        };

        // Lưu Email khôi phục & Câu hỏi bảo mật
        modal.querySelector('#dut-btn-save-rec-settings').onclick = async () => {
            const recoveryEmail = modal.querySelector('#dut-rec-email').value.trim();
            const securityQuestion = modal.querySelector('#dut-sec-question').value.trim();
            const securityAnswer = modal.querySelector('#dut-sec-answer').value.trim();
            const currentPassword = modal.querySelector('#dut-rec-curpass').value;

            const res = await origFetch('/api/auth/recovery-settings', {
                method: 'POST',
                headers: authHeaders(true),
                body: new URLSearchParams({ currentPassword, recoveryEmail, securityQuestion, securityAnswer }).toString()
            });
            const data = await res.json();
            showAlert(escapeHtml(data.message || ''), !data.success);
            if (data.success) showToast(data.message);
        };

        // Kiểm tra realtime ngoại lệ trùng mật khẩu cũ ở Tab Đổi mật khẩu
        const oldPassInput = modal.querySelector('#dut-cp-old');
        const newPassInput = modal.querySelector('#dut-cp-new');
        const checkSameRealtime = () => {
            const o = oldPassInput.value;
            const n = newPassInput.value;
            if (o && n && o === n) {
                showAlert('⚠️ <strong>Ngoại lệ trùng mật khẩu:</strong> Mật khẩu mới không được trùng với mật khẩu hiện tại (mật khẩu cũ)!', true);
            } else if (alertBox.textContent.includes('Ngoại lệ trùng mật khẩu')) {
                alertBox.style.display = 'none';
            }
        };
        oldPassInput.addEventListener('input', checkSameRealtime);
        newPassInput.addEventListener('input', checkSameRealtime);

        modal.querySelector('#dut-cp-save').onclick = async () => {
            const oldPassword = oldPassInput.value;
            const newPassword = newPassInput.value;
            const confirmPassword = modal.querySelector('#dut-cp-confirm').value;

            if (!oldPassword || !newPassword) {
                showAlert('⚠️ Vui lòng nhập đầy đủ mật khẩu hiện tại và mật khẩu mới!', true);
                return;
            }
            if (oldPassword === newPassword) {
                showAlert('⚠️ <strong>Ngoại lệ:</strong> Mật khẩu mới KHÔNG ĐƯỢC TRÙNG với mật khẩu cũ! Vui lòng chọn mật khẩu khác.', true);
                return;
            }
            if (confirmPassword && newPassword !== confirmPassword) {
                showAlert('⚠️ Mật khẩu xác nhận không khớp với mật khẩu mới!', true);
                return;
            }

            const res = await origFetch('/api/auth/change-password', {
                method: 'POST',
                headers: authHeaders(true),
                body: new URLSearchParams({ oldPassword, newPassword, confirmPassword }).toString()
            });
            const data = await res.json();
            showAlert(escapeHtml(data.message || ''), !data.success);
            if (data.success) {
                showToast(data.message);
                setTimeout(() => modal.remove(), 1200);
            }
        };
    }

    /**
     * HỘP THOẠI QUÊN MẬT KHẨU CÔNG KHAI NGOÀI TRANG ĐĂNG NHẬP (index.html):
     * Tuyệt đối KHÔNG hiển thị mã OTP trên hộp thoại!
     * Cho phép khôi phục an toàn qua 3 cách:
     *  - Cách 1: Nhập 1 trong 5 Mã dự phòng (8 chữ số) đã lưu.
     *  - Cách 2: Gửi mã OTP (6 số) về đúng Email khôi phục đã liên kết từ trước.
     *  - Cách 3: Trả lời Câu hỏi bảo mật cá nhân.
     */
    function openPublicForgotPasswordModal() {
        let modal = document.getElementById('dut-public-forgot-modal');
        if (modal) modal.remove();

        modal = document.createElement('div');
        modal.id = 'dut-public-forgot-modal';
        modal.style.cssText = 'position:fixed;inset:0;background:rgba(0,0,0,0.75);z-index:99999;display:flex;align-items:center;justify-content:center;padding:16px;';
        modal.innerHTML = `
            <div style="background:#1e1b2e;border:1px solid rgba(139,92,246,0.45);border-radius:16px;max-width:480px;width:100%;padding:22px 24px;color:#f8fafc;box-shadow:0 20px 50px rgba(0,0,0,0.68);">
                <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px;">
                    <h3 style="margin:0;font-size:18px;">🔐 Khôi phục mật khẩu an toàn</h3>
                    <button type="button" id="dut-pf-close" style="background:none;border:none;color:#94a3b8;font-size:22px;cursor:pointer;">×</button>
                </div>

                <div id="dut-pf-alert" style="display:none;margin-bottom:12px;padding:10px 12px;border-radius:10px;font-size:13px;line-height:1.5;"></div>

                <div style="display:flex;gap:8px;margin-bottom:12px;">
                    <input id="dut-pf-user" type="text" placeholder="Nhập tài khoản cần khôi phục (VD: user@pbl4.com)" style="flex:1;padding:10px 12px;border-radius:8px;border:1px solid rgba(255,255,255,0.18);background:rgba(0,0,0,0.32);color:#fff;" />
                    <button type="button" class="dut-btn dut-btn-primary" id="dut-pf-check" style="white-space:nowrap;">🔍 Kiểm tra</button>
                </div>

                <!-- Chọn phương thức xác thực -->
                <div style="display:flex;gap:6px;margin-bottom:12px;background:rgba(0,0,0,0.28);padding:4px;border-radius:9px;">
                    <button type="button" class="dut-pf-method-btn" data-method="BACKUP_CODE" style="flex:1;padding:7px 6px;border-radius:7px;border:none;font-size:12px;font-weight:600;cursor:pointer;background:#7c3aed;color:#fff;">
                        🔢 Mã dự phòng (8 số)
                    </button>
                    <button type="button" class="dut-pf-method-btn" data-method="RECOVERY_OTP" style="flex:1;padding:7px 6px;border-radius:7px;border:none;font-size:12px;font-weight:600;cursor:pointer;background:transparent;color:#94a3b8;">
                        📧 OTP Email liên kết
                    </button>
                    <button type="button" class="dut-pf-method-btn" data-method="SECURITY_QUESTION" style="flex:1;padding:7px 6px;border-radius:7px;border:none;font-size:12px;font-weight:600;cursor:pointer;background:transparent;color:#94a3b8;">
                        ❓ Câu hỏi bảo mật
                    </button>
                </div>

                <div id="dut-pf-method-desc" style="font-size:12.5px;color:#cbd5e1;margin-bottom:10px;padding:8px 11px;border-radius:8px;background:rgba(255,255,255,0.04);">
                    Nhập <strong>1 trong 5 Mã dự phòng (8 chữ số)</strong> mà bạn đã tạo và lưu khi đăng nhập (mỗi mã dùng 1 lần).
                </div>

                <div id="dut-pf-otp-send-row" style="display:none;margin-bottom:10px;">
                    <button type="button" class="dut-btn" id="dut-pf-send-email-otp" style="width:100%;justify-content:center;border-color:#a78bfa;color:#ddd6fe;">
                        📨 Gửi mã OTP (6 số) tới Email khôi phục đã liên kết
                    </button>
                </div>

                <div style="display:flex;flex-direction:column;gap:10px;">
                    <input id="dut-pf-code" type="text" placeholder="Nhập Mã dự phòng 8 chữ số (VD: 48291037)" style="padding:10px 12px;border-radius:8px;border:1px solid rgba(255,255,255,0.18);background:rgba(0,0,0,0.32);color:#fff;font-weight:700;letter-spacing:1.5px;" />
                    <input id="dut-pf-sec-ans" type="password" placeholder="Nhập câu trả lời bảo mật của bạn" style="display:none;padding:10px 12px;border-radius:8px;border:1px solid rgba(255,255,255,0.18);background:rgba(0,0,0,0.32);color:#fff;" />
                    <input id="dut-pf-newpass" type="password" placeholder="Mật khẩu mới (8-32 ký tự, khác mật khẩu cũ)" style="padding:10px 12px;border-radius:8px;border:1px solid rgba(255,255,255,0.18);background:rgba(0,0,0,0.32);color:#fff;" />
                    <input id="dut-pf-confirm" type="password" placeholder="Nhập lại mật khẩu mới để xác nhận" style="padding:10px 12px;border-radius:8px;border:1px solid rgba(255,255,255,0.18);background:rgba(0,0,0,0.32);color:#fff;" />
                    <button type="button" class="dut-btn dut-btn-primary" id="dut-pf-submit" style="justify-content:center;padding:10px;font-size:14px;background:#16a34a;border-color:#16a34a;">
                        ✅ Xác thực & Đặt lại mật khẩu mới
                    </button>
                </div>
            </div>
        `;
        document.body.appendChild(modal);

        let selectedMethod = 'BACKUP_CODE';
        let accountOptions = null;

        const alertBox = modal.querySelector('#dut-pf-alert');
        function showPfAlert(html, isErr) {
            alertBox.style.display = 'block';
            alertBox.style.background = isErr ? 'rgba(239,68,68,0.16)' : 'rgba(34,197,94,0.16)';
            alertBox.style.border = isErr ? '1px solid rgba(239,68,68,0.45)' : '1px solid rgba(34,197,94,0.45)';
            alertBox.style.color = isErr ? '#fca5a5' : '#86efac';
            alertBox.innerHTML = html;
        }

        modal.querySelector('#dut-pf-close').onclick = () => modal.remove();

        const descEl = modal.querySelector('#dut-pf-method-desc');
        const otpSendRow = modal.querySelector('#dut-pf-otp-send-row');
        const codeInput = modal.querySelector('#dut-pf-code');
        const secAnsInput = modal.querySelector('#dut-pf-sec-ans');

        function updateMethodUI() {
            modal.querySelectorAll('.dut-pf-method-btn').forEach(b => {
                const active = b.dataset.method === selectedMethod;
                b.style.background = active ? '#7c3aed' : 'transparent';
                b.style.color = active ? '#fff' : '#94a3b8';
            });

            if (selectedMethod === 'BACKUP_CODE') {
                otpSendRow.style.display = 'none';
                codeInput.style.display = 'block';
                secAnsInput.style.display = 'none';
                codeInput.placeholder = 'Nhập Mã dự phòng 8 chữ số (VD: 48291037)';
                descEl.innerHTML = accountOptions
                    ? `🔢 Tài khoản này hiện còn <strong>${accountOptions.remainingBackupCodes}/5</strong> Mã dự phòng (8 số) khả dụng.`
                    : `Nhập <strong>1 trong 5 Mã dự phòng (8 chữ số)</strong> mà bạn đã tạo và lưu khi đăng nhập (mỗi mã chỉ dùng 1 lần).`;
            } else if (selectedMethod === 'RECOVERY_OTP') {
                otpSendRow.style.display = 'block';
                codeInput.style.display = 'block';
                secAnsInput.style.display = 'none';
                codeInput.placeholder = 'Nhập mã OTP 6 chữ số nhận được trong Email khôi phục';
                descEl.innerHTML = (accountOptions && accountOptions.hasRecoveryEmail)
                    ? `📧 Hệ thống sẽ gửi mã OTP (6 số) tới hộp thư liên kết: <strong>${escapeHtml(accountOptions.maskedRecoveryEmail)}</strong>.`
                    : `📧 Nhập tên tài khoản và bấm <strong>"🔍 Kiểm tra"</strong> để xem Email khôi phục đã liên kết.`;
            } else {
                otpSendRow.style.display = 'none';
                codeInput.style.display = 'none';
                secAnsInput.style.display = 'block';
                descEl.innerHTML = (accountOptions && accountOptions.hasSecurityQuestion)
                    ? `❓ Câu hỏi bảo mật: <strong style="color:#fde047;">${escapeHtml(accountOptions.securityQuestion)}</strong>`
                    : `❓ Nhập tên tài khoản và bấm <strong>"🔍 Kiểm tra"</strong> để tải Câu hỏi bảo mật của bạn.`;
            }
        }

        modal.querySelectorAll('.dut-pf-method-btn').forEach(btn => {
            btn.onclick = () => {
                selectedMethod = btn.dataset.method;
                updateMethodUI();
            };
        });

        modal.querySelector('#dut-pf-check').onclick = async () => {
            const username = modal.querySelector('#dut-pf-user').value.trim();
            if (!username) {
                showPfAlert('⚠️ Vui lòng nhập địa chỉ tài khoản cần khôi phục!', true);
                return;
            }
            const res = await origFetch(`/api/auth/recovery-options?username=${encodeURIComponent(username)}`);
            const data = await res.json();
            if (!data.success) {
                showPfAlert(escapeHtml(data.message || 'Không tìm thấy tài khoản!'), true);
                return;
            }
            accountOptions = data;
            showPfAlert(
                `✅ Đã tìm thấy tài khoản <strong>${escapeHtml(data.username)}</strong>:<br/>`
                + `• Mã dự phòng 8 số: <strong>${data.remainingBackupCodes}/5 mã</strong> | `
                + `Email liên kết: <strong>${data.hasRecoveryEmail ? escapeHtml(data.maskedRecoveryEmail) : 'Chưa đặt'}</strong> | `
                + `Câu hỏi bảo mật: <strong>${data.hasSecurityQuestion ? 'Đã đặt' : 'Chưa đặt'}</strong>`,
                false
            );
            updateMethodUI();
        };

        modal.querySelector('#dut-pf-send-email-otp').onclick = async () => {
            const username = modal.querySelector('#dut-pf-user').value.trim();
            if (!username) {
                showPfAlert('⚠️ Vui lòng nhập địa chỉ tài khoản trước!', true);
                return;
            }
            const res = await origFetch('/api/auth/forgot-password', {
                method: 'POST',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8' },
                body: new URLSearchParams({ username }).toString()
            });
            const data = await res.json();
            showPfAlert(escapeHtml(data.message || ''), !data.success);
        };

        modal.querySelector('#dut-pf-submit').onclick = async () => {
            const username = modal.querySelector('#dut-pf-user').value.trim();
            const code = codeInput.value.trim();
            const securityAnswer = secAnsInput.value.trim();
            const newPassword = modal.querySelector('#dut-pf-newpass').value;
            const confirmPassword = modal.querySelector('#dut-pf-confirm').value;

            if (!username || !newPassword) {
                showPfAlert('⚠️ Vui lòng nhập đầy đủ Tài khoản và Mật khẩu mới!', true);
                return;
            }
            if (confirmPassword && newPassword !== confirmPassword) {
                showPfAlert('⚠️ Mật khẩu xác nhận không khớp với mật khẩu mới!', true);
                return;
            }

            const res = await origFetch('/api/auth/reset-password', {
                method: 'POST',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8' },
                body: new URLSearchParams({
                    username,
                    method: selectedMethod,
                    code,
                    securityAnswer,
                    newPassword,
                    confirmPassword
                }).toString()
            });
            const data = await res.json();
            showPfAlert(escapeHtml(data.message || ''), !data.success);
            if (data.success) {
                showToast(data.message);
                setTimeout(() => modal.remove(), 1500);
            }
        };
    }

    async function enhanceAdminSecurityPanel() {
        if (!location.pathname.includes('admin') || document.getElementById('dut-admin-sec-panel')) return;
        const container = document.querySelector('main, .container, .admin-container') || document.body;
        const panel = document.createElement('div');
        panel.id = 'dut-admin-sec-panel';
        panel.style.cssText = 'margin:18px;padding:16px 20px;border-radius:12px;background:rgba(139,92,246,0.08);border:1px solid rgba(139,92,246,0.3);color:#f8fafc;';
        panel.innerHTML = `
            <div style="display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:10px;">
                <div>
                    <h3 style="margin:0;font-size:16px;">🛡️ Quản lý Chống tấn công & Chặn IP (AbuseGuard)</h3>
                    <small id="dut-sec-stats" style="color:#94a3b8;">Đang tải trạng thái bảo mật IP...</small>
                </div>
                <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap;">
                    <input id="dut-ban-ip-input" type="text" placeholder="Nhập IP (VD: 203.0.113.5)" style="padding:6px 10px;border-radius:8px;border:1px solid rgba(255,255,255,0.2);background:rgba(0,0,0,0.3);color:#fff;font-size:13px;" />
                    <input id="dut-ban-min-input" type="number" value="30" min="1" max="10080" style="width:75px;padding:6px 8px;border-radius:8px;border:1px solid rgba(255,255,255,0.2);background:rgba(0,0,0,0.3);color:#fff;font-size:13px;" title="Số phút chặn" />
                    <button type="button" class="dut-btn dut-btn-danger" id="dut-btn-ban-ip">🚫 Chặn IP</button>
                    <button type="button" class="dut-btn" id="dut-btn-unban-ip">✅ Mở khóa IP</button>
                </div>
            </div>
            <div id="dut-banned-ip-list" style="margin-top:10px;font-size:13px;color:#fca5a5;"></div>
        `;
        container.prepend(panel);

        async function loadSecStatus() {
            try {
                const res = await origFetch('/api/admin/security', { headers: authHeaders(false) });
                if (!res.ok) return;
                const d = await res.json();
                panel.querySelector('#dut-sec-stats').textContent = `Số IP đang theo dõi: ${d.trackedIps || 0} | Số IP đang bị khóa: ${(d.bannedIps || []).length}`;
                const listEl = panel.querySelector('#dut-banned-ip-list');
                if (d.bannedIps && d.bannedIps.length > 0) {
                    listEl.innerHTML = 'Các IP đang bị khóa: ' + d.bannedIps.map(x => `<span style="padding:2px 8px;margin-right:6px;border-radius:6px;background:rgba(239,68,68,0.2);">${escapeHtml(x.ip)} (${x.remainingSeconds}s)</span>`).join('');
                } else {
                    listEl.innerHTML = '<span style="color:#86efac;">Hiện không có IP nào đang bị cấm.</span>';
                }
            } catch (e) {}
        }

        panel.querySelector('#dut-btn-ban-ip').onclick = async () => {
            const ip = panel.querySelector('#dut-ban-ip-input').value.trim();
            const minutes = panel.querySelector('#dut-ban-min-input').value.trim();
            const res = await origFetch('/api/admin/security/ban', {
                method: 'POST',
                headers: authHeaders(true),
                body: new URLSearchParams({ ip, minutes }).toString()
            });
            const d = await res.json();
            showToast(d.message || '');
            loadSecStatus();
        };

        panel.querySelector('#dut-btn-unban-ip').onclick = async () => {
            const ip = panel.querySelector('#dut-ban-ip-input').value.trim();
            const res = await origFetch('/api/admin/security/unban', {
                method: 'POST',
                headers: authHeaders(true),
                body: new URLSearchParams({ ip }).toString()
            });
            const d = await res.json();
            showToast(d.message || '');
            loadSecStatus();
        };

        loadSecStatus();
    }

    function hookRealtimeSseAutoRefresh() {
        let boundSource = null;
        setInterval(() => {
            if (typeof sseSource !== 'undefined' && sseSource && sseSource !== boundSource) {
                boundSource = sseSource;
                boundSource.addEventListener('new_mail', () => {
                    if (STATE.currentFolder === 'inbox' || STATE.currentFolder === 'starred') {
                        loadFolderMails(STATE.currentFolder, false);
                        refreshQuotaBar();
                    }
                });
                boundSource.addEventListener('state_sync', () => {
                    loadFolderMails(STATE.currentFolder, false);
                    refreshQuotaBar();
                });
                boundSource.addEventListener('message', () => {
                    if (STATE.currentFolder === 'inbox') {
                        loadFolderMails('inbox', false);
                        refreshQuotaBar();
                    }
                });
            }
        }, 500);
    }

    function boot() {
        injectStyles();
        enhanceLoginPageForgotPassword();
        enhanceAdminSecurityPanel();
        if (document.querySelector('input[placeholder*="Tìm kiếm"]')) {
            enhanceSidebar();
            enhanceChangePasswordButton();
            enhanceSearchAndRefresh();
            hookComposeCloseForDrafts();
            observeMailListContainer();
            hookRealtimeSseAutoRefresh();
            switchFolder('inbox');
            setTimeout(checkAndPromptBackupCodesOnLogin, 600);
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', () => setTimeout(boot, 100));
    } else {
        setTimeout(boot, 100);
    }
})();
