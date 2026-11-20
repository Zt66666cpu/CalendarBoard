const $ = (id) => document.getElementById(id);

const DEFAULT_CONFIG = {
  url: 'https://samnpjgnvtearyeejjtg.supabase.co',
  key: 'sb_publishable_x061oGvVUpjXv0PG1J-Kag_Voayt0Ux',
};

const isNative = Boolean(window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.native);
const pendingHttp = new Map();
let httpCounter = 0;

function postNative(payload) {
  if (isNative) {
    window.webkit.messageHandlers.native.postMessage(payload);
  }
}

function storeGet(key) {
  if (state.nativeStore && Object.prototype.hasOwnProperty.call(state.nativeStore, key)) {
    return state.nativeStore[key];
  }
  return localStorage.getItem(`cb_mac_${key}`);
}

function storeSet(key, value) {
  if (!state.nativeStore) state.nativeStore = {};
  if (value === null || value === undefined) {
    delete state.nativeStore[key];
    localStorage.removeItem(`cb_mac_${key}`);
    postNative({ action: 'saveStore', key, value: null });
  } else {
    const str = typeof value === 'string' ? value : JSON.stringify(value);
    state.nativeStore[key] = str;
    localStorage.setItem(`cb_mac_${key}`, str);
    postNative({ action: 'saveStore', key, value: str });
  }
}

function parseJsonSafe(raw, fallback) {
  if (!raw) return fallback;
  try { return JSON.parse(raw); } catch { return fallback; }
}

function localDate(date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

function parseDate(iso) {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d);
}

function addDays(baseDate, offset) {
  return new Date(baseDate.getFullYear(), baseDate.getMonth(), baseDate.getDate() + offset);
}

function makeUuid32() {
  if (crypto && crypto.randomUUID) return crypto.randomUUID().replaceAll('-', '');
  return Array.from({ length: 32 }, () => Math.floor(Math.random() * 16).toString(16)).join('');
}

function isPlaceholderConfig(cfg) {
  if (!cfg || !cfg.url || !cfg.key) return true;
  return cfg.url.includes('YOUR_PROJECT') || cfg.key.includes('REPLACE_WITH_YOUR_KEY');
}

function buildInitialDemoItems() {
  const now = new Date();
  const d0 = localDate(now);
  const d1 = localDate(addDays(now, 1));
  const d2 = localDate(addDays(now, 2));
  const d3 = localDate(addDays(now, 3));
  const d5 = localDate(addDays(now, 5));
  return [
    { id: '11111111111111111111111111111101', date: d0, type: '课程', title: '微积分Ⅰ', startTime: '08:30', endTime: '10:05', time: '08:30-10:05', place: '品学楼B308', done: false },
    { id: '11111111111111111111111111111102', date: d0, type: '课程', title: '数据结构与算法Ⅰ', startTime: '14:30', endTime: '16:05', time: '14:30-16:05', place: '立人楼A204', done: false },
    { id: '22222222222222222222222222222201', date: d0, type: '待办', title: '提交数据结构第 5 次实验报告', startTime: '', endTime: '', time: '', place: '学习通', done: false },
    { id: '22222222222222222222222222222202', date: d0, type: '待办', title: '整理微积分期中错题本', startTime: '', endTime: '', time: '', place: '', done: false },
    { id: '33333333333333333333333333333301', date: d0, type: '日程', title: '社团技术分享会', startTime: '19:30', endTime: '21:00', time: '19:30-21:00', place: '学生活动中心 302', done: false },
    { id: '11111111111111111111111111111103', date: d1, type: '课程', title: '大学英语（读写）', startTime: '10:20', endTime: '11:55', time: '10:20-11:55', place: '主楼C102', done: false },
    { id: '33333333333333333333333333333302', date: d1, type: '日程', title: '和同学去图书馆自习', startTime: '15:00', endTime: '18:00', time: '15:00-18:00', place: '图书馆四楼北区', done: false },
    { id: '33333333333333333333333333333303', date: d2, type: '日程', title: '羽毛球约球', startTime: '17:00', endTime: '18:30', time: '17:00-18:30', place: '体育馆 4 号场', done: false },
    { id: '11111111111111111111111111111104', date: d3, type: '课程', title: '线性代数与空间解析几何', startTime: '08:30', endTime: '10:05', time: '08:30-10:05', place: '品学楼A105', done: false },
    { id: '33333333333333333333333333333304', date: d5, type: '日程', title: '项目小组进度对齐会', startTime: '20:00', endTime: '21:00', time: '20:00-21:00', place: '线上腾讯会议', done: false },
  ];
}

const state = {
  nativeStore: null,
  config: { ...DEFAULT_CONFIG },
  session: null,
  mode: 'demo',
  items: [],
  offline: false,
  view: new Date(),
  selected: localDate(new Date()),
  todayIso: localDate(new Date()),
  sidebarTab: 'day',
  editing: null,
  panelState: 'sidebar', // 'peek' | 'sidebar' | 'expanded'
  pinned: false,
  hoverExpand: true,
  dockMode: 'autoHide', // 'autoHide' | 'desktop' | 'normal' | 'floating'
  glassStyle: 'regular', // 'regular' | 'clear'
};

// Lunar & Chinese Holiday helper
const LUNAR_DAYS = ['', '初一','初二','初三','初四','初五','初六','初七','初八','初九','初十',
  '十一','十二','十三','十四','十五','十六','十七','十八','十九','二十',
  '廿一','廿二','廿三','廿四','廿五','廿六','廿七','廿八','廿九','三十'];

const SOLAR_FESTIVALS = {
  '01-01': '元旦',
  '05-01': '劳动节',
  '06-01': '儿童节',
  '09-10': '教师节',
  '10-01': '国庆节',
  '12-25': '圣诞节',
};

let lunarFormatter = null;
try {
  lunarFormatter = new Intl.DateTimeFormat('zh-CN-u-ca-chinese', { month: 'long', day: 'numeric' });
} catch {
  lunarFormatter = null;
}

function getLunarInfo(date) {
  const mmdd = `${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
  if (SOLAR_FESTIVALS[mmdd]) return { label: SOLAR_FESTIVALS[mmdd], holiday: true };
  if (!lunarFormatter) return { label: '', holiday: false };
  try {
    const parts = lunarFormatter.formatToParts(date);
    const monthPart = parts.find((p) => p.type === 'month')?.value || '';
    const dayNum = Number(parts.find((p) => p.type === 'day')?.value || 0);
    const isLeap = monthPart.includes('闰');
    if (!isLeap) {
      if (monthPart === '正月' && dayNum === 1) return { label: '春节', holiday: true };
      if (monthPart === '正月' && dayNum === 15) return { label: '元宵', holiday: true };
      if (monthPart === '五月' && dayNum === 5) return { label: '端午', holiday: true };
      if (monthPart === '八月' && dayNum === 15) return { label: '中秋', holiday: true };
      if (monthPart === '腊月' && (dayNum === 29 || dayNum === 30)) {
        const nextParts = lunarFormatter.formatToParts(addDays(date, 1));
        const nextMonth = nextParts.find((p) => p.type === 'month')?.value || '';
        const nextDay = Number(nextParts.find((p) => p.type === 'day')?.value || 0);
        if (nextMonth === '正月' && nextDay === 1) return { label: '除夕', holiday: true };
      }
    }
    if (dayNum === 1) return { label: monthPart, holiday: false };
    return { label: LUNAR_DAYS[dayNum] || '', holiday: false };
  } catch {
    return { label: '', holiday: false };
  }
}

// Native HTTP bridge
window.__nativeHttpCallback = function (res) {
  const resolver = pendingHttp.get(res.id);
  if (!resolver) return;
  pendingHttp.delete(res.id);
  resolver(res);
};

async function rawRequest(path, { method = 'GET', body, token, config = state.config, extraHeaders = {} } = {}) {
  const headers = { apikey: config.key, Accept: 'application/json', ...extraHeaders };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (token) headers.Authorization = `Bearer ${token}`;
  const url = `${config.url}${path}`;
  const bodyStr = body === undefined ? undefined : JSON.stringify(body);

  let status = 0;
  let text = '';

  if (isNative) {
    const id = `req_${++httpCounter}_${Date.now()}`;
    const res = await new Promise((resolve) => {
      pendingHttp.set(id, resolve);
      postNative({ action: 'httpRequest', id, url, method, headers, body: bodyStr });
    });
    if (res.networkError) throw new Error('网络连接失败，请检查网络后重试。');
    status = res.status || 0;
    text = res.text || '';
  } else {
    let response;
    try {
      response = await fetch(url, { method, headers, body: bodyStr });
    } catch {
      throw new Error('网络连接失败，请检查网络后重试。');
    }
    status = response.status;
    text = await response.text();
  }

  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = text; }
  if (status < 200 || status >= 300) {
    throw new Error(data?.msg || data?.message || data?.error_description || `请求失败（${status}）`);
  }
  return data;
}

function tokenUserId(token) {
  try {
    const encoded = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    return JSON.parse(atob(encoded)).sub || null;
  } catch { return null; }
}

function saveSession(data, emailHint) {
  state.session = {
    access_token: data.access_token,
    refresh_token: data.refresh_token,
    expires_at: data.expires_at || Math.floor(Date.now() / 1000) + (data.expires_in || 3600),
    user_id: data.user?.id || tokenUserId(data.access_token),
    email: data.user?.email || emailHint || state.session?.email || '',
  };
  storeSet('session', state.session);
}

async function refreshSession() {
  if (!state.session?.refresh_token) throw new Error('登录已过期，请重新登录。');
  const data = await rawRequest('/auth/v1/token?grant_type=refresh_token', {
    method: 'POST',
    body: { refresh_token: state.session.refresh_token },
  });
  saveSession(data);
}

async function api(path, options = {}) {
  if (!state.session) throw new Error('请先登录云端账号。');
  if (Date.now() / 1000 > state.session.expires_at - 60) await refreshSession();
  try {
    return await rawRequest(path, { ...options, token: state.session.access_token });
  } catch (error) {
    if (/401|JWT|token/i.test(error.message)) {
      await refreshSession();
      return rawRequest(path, { ...options, token: state.session.access_token });
    }
    throw error;
  }
}

async function fetchRemoteItems() {
  const all = [];
  let offset = 0;
  while (true) {
    const rows = await api(`/rest/v1/calendar_items?select=id,payload&order=id.asc&limit=1000&offset=${offset}`);
    all.push(...rows);
    if (rows.length < 1000) break;
    offset += rows.length;
  }
  return all.map((row) => ({ ...row.payload, id: row.id })).filter((item) => item.id && item.date);
}

async function maintainRemote(items) {
  const today = localDate(new Date());
  let changed = false;
  for (const item of items) {
    if (item.date >= today) continue;
    if (item.type === '待办' && !item.done) {
      const updated = { ...item, date: today };
      await api(`/rest/v1/calendar_items?id=eq.${encodeURIComponent(item.id)}`, {
        method: 'PATCH',
        body: { payload: updated },
      });
      changed = true;
    } else if (item.type === '日程') {
      await api(`/rest/v1/calendar_items?id=eq.${encodeURIComponent(item.id)}`, { method: 'DELETE' });
      changed = true;
    }
  }
  return changed;
}

function setStatus(message, isError = false) {
  const el = $('statusText');
  el.textContent = message;
  el.classList.toggle('error', isError);
}

function isLocalMode() {
  return state.mode === 'demo' || state.mode === 'local';
}

function academicWeekLabel(date) {
  const termStart = new Date(2026, 7, 31); // 2026-08-31 Week 1 Mon
  const diffDays = Math.floor((new Date(date.getFullYear(), date.getMonth(), date.getDate()) - termStart) / 86400000);
  if (diffDays < 0) return '';
  const week = Math.floor(diffDays / 7) + 1;
  if (week >= 1 && week <= 20) return `第${week}周`;
  return '';
}

function maintainLocal(items) {
  const today = localDate(new Date());
  let changed = false;
  const kept = [];
  for (const item of items) {
    if (item.date < today) {
      if (item.type === '待办' && !item.done) {
        kept.push({ ...item, date: today });
        changed = true;
        continue;
      }
      if (item.type === '日程') {
        changed = true;
        continue;
      }
    }
    kept.push(item);
  }
  return { items: kept, changed };
}

async function syncItems() {
  if (isLocalMode()) {
    const res = maintainLocal(state.items);
    if (res.changed) {
      state.items = res.items;
      storeSet('demoItems', state.items);
    }
    renderAll();
    const courseCount = state.items.filter((it) => it.type === '课程').length;
    setStatus(state.mode === 'local'
      ? `已同步本地课表（${courseCount} 节）· 移开鼠标自动收缩`
      : '演示模式 · 移开鼠标自动收缩到右边缘');
    return;
  }
  if (!state.session) {
    setStatus('未登录云端 · 请点击「连接云端」', true);
    renderAll();
    return;
  }
  setStatus('正在同步云端…');
  try {
    let items = await fetchRemoteItems();
    if (await maintainRemote(items)) items = await fetchRemoteItems();
    state.items = items;
    state.offline = false;
    storeSet('cache', items);
    const t = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
    setStatus(`已同步 · ${t}`);
  } catch (err) {
    state.items = parseJsonSafe(storeGet('cache'), []);
    state.offline = true;
    setStatus(`离线缓存 · ${err.message}`, true);
  }
  renderAll();
}

// UI Rendering
function itemPlace(item) { return item.place || item.room || ''; }
function typeKey(type) { return { 课程: 'course', 待办: 'todo', 日程: 'schedule' }[type] || 'schedule'; }

function updateClock() {
  const now = new Date();
  $('clockTime').textContent = `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`;
  const currentIso = localDate(now);
  if (currentIso !== state.todayIso) {
    const wasToday = state.selected === state.todayIso;
    state.todayIso = currentIso;
    if (wasToday) {
      state.selected = currentIso;
      state.view = new Date(now.getFullYear(), now.getMonth(), 1);
    }
    syncItems();
  }
}

function renderModeBadge() {
  const badge = $('modeBadge');
  const connectBtn = $('connectBannerBtn');
  if (state.mode === 'local') {
    badge.textContent = '本地课表';
    badge.className = 'mode-badge local';
    connectBtn.classList.remove('hidden');
  } else if (state.mode === 'demo') {
    badge.textContent = '演示模式';
    badge.className = 'mode-badge demo';
    connectBtn.classList.remove('hidden');
  } else if (state.offline) {
    badge.textContent = '离线只读';
    badge.className = 'mode-badge offline';
    connectBtn.classList.add('hidden');
  } else {
    badge.textContent = '云端已同步';
    badge.className = 'mode-badge cloud';
    connectBtn.classList.add('hidden');
  }
}

function renderCalendar() {
  const y = state.view.getFullYear();
  const m = state.view.getMonth();
  $('monthTitle').textContent = `${y}年${m + 1}月`;

  const first = (new Date(y, m, 1).getDay() + 6) % 7;
  const days = new Date(y, m + 1, 0).getDate();
  const count = Math.ceil((first + days) / 7) * 7;
  const grid = $('monthGrid');
  grid.replaceChildren();
  const today = localDate(new Date());

  for (let i = 0; i < count; i++) {
    const date = new Date(y, m, i - first + 1);
    const iso = localDate(date);
    const cell = document.createElement('button');
    cell.type = 'button';
    cell.className = 'day-cell';
    if (date.getMonth() !== m) cell.classList.add('outside');
    if (iso === today) cell.classList.add('today');
    if (iso === state.selected) cell.classList.add('selected');

    const top = document.createElement('div');
    top.className = 'day-cell-top';
    const num = document.createElement('span');
    num.className = 'day-num';
    num.textContent = String(date.getDate());
    const lunar = getLunarInfo(date);
    const lunarEl = document.createElement('span');
    lunarEl.className = `day-lunar${lunar.holiday ? ' holiday' : ''}`;
    lunarEl.textContent = lunar.label;
    top.append(num, lunarEl);
    cell.append(top);

    const chips = document.createElement('div');
    chips.className = 'day-chips';
    const dayItems = state.items
      .filter((it) => it.date === iso)
      .sort((a, b) => {
        const order = { 课程: 0, 待办: 1, 日程: 2 };
        return (order[a.type] ?? 9) - (order[b.type] ?? 9) || (a.time || '').localeCompare(b.time || '');
      });

    for (const item of dayItems.slice(0, 3)) {
      const chip = document.createElement('div');
      chip.className = `day-chip ${typeKey(item.type)}`;
      chip.textContent = item.title;
      chips.append(chip);
    }
    if (dayItems.length > 3) {
      const more = document.createElement('div');
      more.className = 'day-more';
      more.textContent = `+${dayItems.length - 3} 项`;
      chips.append(more);
    }
    cell.append(chips);

    cell.addEventListener('click', () => {
      state.selected = iso;
      state.view = new Date(date.getFullYear(), date.getMonth(), 1);
      state.sidebarTab = 'day';
      renderAll();
    });
    cell.addEventListener('dblclick', () => {
      state.selected = iso;
      openEntryModal(null, '日程');
    });

    grid.append(cell);
  }
}

function renderSidebarHeader() {
  const date = parseDate(state.selected);
  const weekdays = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];
  const lunar = getLunarInfo(date);
  const weekLbl = academicWeekLabel(date);
  const isToday = state.selected === localDate(new Date());
  $('selectedTitle').textContent = `${date.getMonth() + 1}月${date.getDate()}日 · ${weekdays[date.getDay()]}${isToday ? '（今天）' : ''}`;
  const subParts = [];
  if (weekLbl) subParts.push(`2026秋 ${weekLbl}`);
  if (lunar.label) subParts.push(`农历 ${lunar.label}`);
  subParts.push('点击事项编辑');
  $('selectedSubtitle').textContent = subParts.join(' · ');
}

function renderDayList() {
  const map = {
    课程: { list: $('listCourse'), count: $('countCourse'), empty: '今天没有课程' },
    待办: { list: $('listTodo'), count: $('countTodo'), empty: '暂无待办事项' },
    日程: { list: $('listSchedule'), count: $('countSchedule'), empty: '暂无日程安排' },
  };

  for (const type of ['课程', '待办', '日程']) {
    const { list, count, empty } = map[type];
    list.replaceChildren();
    const items = state.items
      .filter((it) => it.date === state.selected && it.type === type)
      .sort((a, b) => (a.time || '').localeCompare(b.time || '') || a.title.localeCompare(b.title, 'zh-CN'));

    count.textContent = String(items.length);
    if (!items.length) {
      const hint = document.createElement('div');
      hint.className = 'empty-hint';
      hint.textContent = empty;
      list.append(hint);
      continue;
    }

    for (const item of items) {
      const card = document.createElement('div');
      card.className = 'entry-card';

      const main = document.createElement('div');
      main.className = 'entry-main';
      const title = document.createElement('div');
      title.className = 'entry-title';
      title.textContent = item.title;
      main.append(title);

      const metaParts = [item.time, itemPlace(item)].filter(Boolean);
      if (metaParts.length) {
        const meta = document.createElement('div');
        meta.className = 'entry-meta';
        meta.textContent = metaParts.join(' · ');
        main.append(meta);
      }

      const quickBtn = document.createElement('button');
      quickBtn.type = 'button';
      quickBtn.className = 'entry-done-btn';
      quickBtn.textContent = type === '待办' ? '✓ 完成' : '删除';
      quickBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        deleteItem(item);
      });

      card.addEventListener('click', () => openEntryModal(item));
      card.append(main, quickBtn);
      list.append(card);
    }
  }
}

function renderWeekView() {
  const container = $('weekView');
  container.replaceChildren();
  const weekdays = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];
  const base = new Date();

  for (let offset = 1; offset <= 7; offset++) {
    const d = addDays(base, offset);
    const iso = localDate(d);
    const block = document.createElement('div');
    block.className = 'week-day-block';

    const head = document.createElement('div');
    head.className = 'week-day-head';
    head.innerHTML = `<span>${d.getMonth() + 1}月${d.getDate()}日 · ${weekdays[d.getDay()]}</span>`;
    const addBtn = document.createElement('button');
    addBtn.type = 'button';
    addBtn.className = 'add-mini';
    addBtn.textContent = '＋';
    addBtn.title = '给这天添加日程';
    addBtn.addEventListener('click', () => {
      state.selected = iso;
      openEntryModal(null, '日程');
    });
    head.append(addBtn);
    block.append(head);

    const items = state.items
      .filter((it) => it.date === iso && it.type === '日程')
      .sort((a, b) => (a.time || '').localeCompare(b.time || ''));

    if (!items.length) {
      const empty = document.createElement('div');
      empty.className = 'empty-hint';
      empty.textContent = '暂无日程';
      block.append(empty);
    } else {
      const list = document.createElement('div');
      list.className = 'cat-list';
      for (const item of items) {
        const card = document.createElement('div');
        card.className = 'entry-card';
        const main = document.createElement('div');
        main.className = 'entry-main';
        const title = document.createElement('div');
        title.className = 'entry-title';
        title.textContent = item.title;
        main.append(title);
        const metaParts = [item.time, itemPlace(item)].filter(Boolean);
        if (metaParts.length) {
          const meta = document.createElement('div');
          meta.className = 'entry-meta';
          meta.textContent = metaParts.join(' · ');
          main.append(meta);
        }
        card.addEventListener('click', () => openEntryModal(item));
        card.append(main);
        list.append(card);
      }
      block.append(list);
    }
    container.append(block);
  }
}

function renderTabs() {
  const isDay = state.sidebarTab === 'day';
  $('tabDay').classList.toggle('active', isDay);
  $('tabWeek').classList.toggle('active', !isDay);
  $('dayView').classList.toggle('hidden', !isDay);
  $('weekView').classList.toggle('hidden', isDay);
}

function renderNativeControls() {
  const isPeeking = state.panelState === 'peek';
  const isExpanded = state.panelState === 'expanded';

  $('peekStrip').classList.toggle('hidden', !isPeeking);
  $('shell').classList.toggle('hidden', isPeeking);
  $('shell').classList.toggle('collapsed', !isExpanded);

  $('expandIcon').textContent = isExpanded ? '›' : '‹';
  $('expandLabel').textContent = isExpanded ? '收起' : '月历';

  const modeLabels = {
    autoHide: '自动隐藏',
    desktop: '贴近桌面',
    normal: '普通窗口',
    floating: '置顶悬浮',
  };
  $('modeQuickToggle').textContent = modeLabels[state.dockMode] || '自动隐藏';
  $('pinBtn').classList.toggle('active', state.pinned);
  $('hoverExpandToggle').checked = state.hoverExpand;

  for (const btn of $('dockModeSegmented').querySelectorAll('button')) {
    btn.classList.toggle('active', btn.dataset.mode === state.dockMode);
  }
  for (const btn of $('glassStyleSegmented').querySelectorAll('button')) {
    btn.classList.toggle('active', btn.dataset.glass === state.glassStyle);
  }
}

function renderAll() {
  renderNativeControls();
  renderModeBadge();
  renderSidebarHeader();
  renderTabs();
  renderCalendar();
  renderDayList();
  renderWeekView();
}

// Entry CRUD
function openEntryModal(item = null, defaultType = '日程') {
  if (state.mode === 'cloud' && state.offline) {
    setStatus('离线时只能查看，联网后再编辑。', true);
    return;
  }
  state.editing = item;
  $('entryModalTitle').textContent = item ? '编辑事项' : '新增事项';
  $('entryType').value = item?.type || defaultType;
  $('entryDate').value = item?.date || state.selected;
  $('entryTitle').value = item?.title || '';
  $('entryStart').value = item?.startTime || (item?.time || '').split('-')[0] || '';
  $('entryEnd').value = item?.endTime || (item?.time || '').split('-')[1] || '';
  $('entryPlace').value = itemPlace(item || {});
  $('deleteEntryBtn').classList.toggle('hidden', !item);

  $('entryBackdrop').classList.remove('hidden');
  postNative({ action: 'setModalOpen', open: true });
  setTimeout(() => $('entryTitle').focus(), 40);
}

function closeEntryModal() {
  $('entryBackdrop').classList.add('hidden');
  state.editing = null;
  postNative({ action: 'setModalOpen', open: false });
}

async function saveEntry(event) {
  event.preventDefault();
  const start = $('entryStart').value;
  const end = $('entryEnd').value;
  if (end && (!start || end <= start)) {
    setStatus('结束时间需要晚于开始时间。', true);
    return;
  }
  const title = $('entryTitle').value.trim();
  if (!title) return;

  const id = state.editing?.id || makeUuid32();
  const item = {
    ...(state.editing || {}),
    id,
    date: $('entryDate').value,
    type: $('entryType').value,
    title,
    startTime: start,
    endTime: end,
    time: end ? `${start}-${end}` : start,
    place: $('entryPlace').value.trim(),
    done: state.editing?.done || false,
  };
  if ('room' in item) item.room = item.place;
  if ('display' in item) item.display = item.title;

  if (isLocalMode()) {
    if (state.editing) {
      state.items = state.items.map((it) => (it.id === id ? item : it));
    } else {
      state.items.push(item);
    }
    storeSet('demoItems', state.items);
    closeEntryModal();
    state.selected = item.date;
    state.view = parseDate(item.date);
    renderAll();
    setStatus(state.mode === 'local' ? '已保存到本地日历' : '演示模式 · 已更新本地演示数据');
    return;
  }

  try {
    if (state.editing) {
      await api(`/rest/v1/calendar_items?id=eq.${encodeURIComponent(id)}`, {
        method: 'PATCH',
        body: { payload: item },
      });
    } else {
      await api('/rest/v1/calendar_items', {
        method: 'POST',
        body: { id, payload: item },
      });
    }
    closeEntryModal();
    state.selected = item.date;
    state.view = parseDate(item.date);
    await syncItems();
  } catch (err) {
    setStatus(err.message, true);
  }
}

async function deleteItem(item) {
  if (state.mode === 'cloud' && state.offline) {
    setStatus('离线时不能删除。', true);
    return;
  }
  if (isLocalMode()) {
    state.items = state.items.filter((it) => it.id !== item.id);
    storeSet('demoItems', state.items);
    renderAll();
    setStatus(item.type === '待办' ? `已完成「${item.title}」` : `已删除「${item.title}」`);
    return;
  }
  try {
    await api(`/rest/v1/calendar_items?id=eq.${encodeURIComponent(item.id)}`, { method: 'DELETE' });
    await syncItems();
  } catch (err) {
    setStatus(err.message, true);
  }
}

// Settings & Auth Modal
function setSettingsMsg(text, kind = '') {
  const el = $('settingsMsg');
  el.textContent = text;
  el.className = `settings-msg${kind ? ` ${kind}` : ''}`;
}

function openSettingsModal() {
  $('cfgUrl').value = isPlaceholderConfig(state.config) ? '' : state.config.url;
  $('cfgKey').value = isPlaceholderConfig(state.config) ? '' : state.config.key;
  $('authEmail').value = state.session?.email || '';
  $('authPassword').value = '';
  setSettingsMsg('');

  const loggedIn = state.mode === 'cloud' && Boolean(state.session);
  $('loggedInRow').classList.toggle('hidden', !loggedIn);
  if (loggedIn) {
    $('loggedInUser').textContent = `已连接云端：${state.session.email || '当前账号'}`;
  }

  $('settingsBackdrop').classList.remove('hidden');
  postNative({ action: 'setModalOpen', open: true });
}

function closeSettingsModal() {
  $('settingsBackdrop').classList.add('hidden');
  postNative({ action: 'setModalOpen', open: false });
}

function readAndValidateCloudInputs() {
  const url = $('cfgUrl').value.trim().replace(/\/rest\/v1\/?$/, '').replace(/\/+$/, '');
  const key = $('cfgKey').value.trim();
  const email = $('authEmail').value.trim();
  const password = $('authPassword').value;
  if (!/^https:\/\/[^/]+\.supabase\.co$/.test(url)) {
    setSettingsMsg('项目 URL 格式应为 https://xxxxx.supabase.co', 'error');
    return null;
  }
  if (!key.startsWith('sb_publishable_')) {
    setSettingsMsg('密钥必须以 sb_publishable_ 开头（不要填 secret key）', 'error');
    return null;
  }
  if (!email || password.length < 6) {
    setSettingsMsg('请填写有效邮箱和至少 6 位密码', 'error');
    return null;
  }
  return { url, key, email, password };
}

async function uploadLocalItemsToCloud() {
  const localItems = parseJsonSafe(storeGet('demoItems'), []);
  if (!Array.isArray(localItems) || !localItems.length) return 0;
  // Filter valid 32-hex IDs and skip synthetic demo items
  const valid = localItems.filter((it) => {
    const id = String(it?.id || '');
    if (!/^[0-9a-fA-F]{32}$/.test(id)) return false;
    if (id.startsWith('111111111111111111111111111111') ||
        id.startsWith('222222222222222222222222222222') ||
        id.startsWith('333333333333333333333333333333')) return false;
    return Boolean(it.date && it.title);
  });
  if (!valid.length) return 0;

  const existing = await fetchRemoteItems();
  const existingIds = new Set(existing.map((it) => it.id));
  const toUpload = valid.filter((it) => !existingIds.has(it.id));
  if (!toUpload.length) return 0;

  for (let i = 0; i < toUpload.length; i += 50) {
    const batch = toUpload.slice(i, i + 50).map((it) => ({ id: it.id, payload: it }));
    await api('/rest/v1/calendar_items', {
      method: 'POST',
      body: batch,
      extraHeaders: { Prefer: 'resolution=merge-duplicates,return=minimal' },
    });
  }
  return toUpload.length;
}

async function handleCloudLogin(event) {
  event.preventDefault();
  const vals = readAndValidateCloudInputs();
  if (!vals) return;
  setSettingsMsg('正在连接并登录…');
  try {
    const cfg = { url: vals.url, key: vals.key };
    const data = await rawRequest('/auth/v1/token?grant_type=password', {
      method: 'POST',
      body: { email: vals.email, password: vals.password },
      config: cfg,
    });
    state.config = cfg;
    state.mode = 'cloud';
    storeSet('config', cfg);
    storeSet('mode', 'cloud');
    saveSession(data, vals.email);
    setSettingsMsg('登录成功，正在同步本地 163 节全学期课表到云端…', 'ok');
    const uploaded = await uploadLocalItemsToCloud();
    closeSettingsModal();
    await syncItems();
    if (uploaded > 0) {
      setStatus(`云端同步完成 · 已自动上传 ${uploaded} 条本地课表/事项`);
    }
  } catch (err) {
    setSettingsMsg(err.message, 'error');
  }
}

async function handleCloudSignup() {
  const vals = readAndValidateCloudInputs();
  if (!vals) return;
  setSettingsMsg('正在提交注册…');
  try {
    const cfg = { url: vals.url, key: vals.key };
    await rawRequest('/auth/v1/signup', {
      method: 'POST',
      body: { email: vals.email, password: vals.password },
      config: cfg,
    });
    state.config = cfg;
    storeSet('config', cfg);
    setSettingsMsg('注册成功！请去邮箱点击验证链接，再回来点「保存并登录」。', 'ok');
  } catch (err) {
    setSettingsMsg(err.message, 'error');
  }
}

function switchToDemoMode() {
  state.mode = 'demo';
  storeSet('mode', 'demo');
  const savedDemo = parseJsonSafe(storeGet('demoItems'), null);
  state.items = Array.isArray(savedDemo) && savedDemo.length ? savedDemo : buildInitialDemoItems();
  storeSet('demoItems', state.items);
  closeSettingsModal();
  renderAll();
  setStatus('演示模式 · 移开鼠标自动收缩到右边缘');
}

function handleLogout() {
  state.session = null;
  storeSet('session', null);
  storeSet('cache', null);
  switchToDemoMode();
}

// Native Callbacks
window.__onNativeState = function (s) {
  if (!s) return;
  state.panelState = s.panelState || (s.peeking ? 'peek' : s.expanded ? 'expanded' : 'sidebar');
  state.pinned = Boolean(s.pinned);
  state.hoverExpand = Boolean(s.hoverExpand);
  state.dockMode = s.dockMode || 'autoHide';
  state.glassStyle = s.glassStyle || 'regular';
  renderNativeControls();
};

window.__triggerSync = function () { syncItems(); };
window.__openSettings = function () { openSettingsModal(); };

function hydrateAndStart(initPayload) {
  if (initPayload && initPayload.store) state.nativeStore = initPayload.store;
  if (initPayload && initPayload.state) {
    const s = initPayload.state;
    state.panelState = s.panelState || 'sidebar';
    state.pinned = Boolean(s.pinned);
    state.hoverExpand = Boolean(s.hoverExpand);
    state.dockMode = s.dockMode || 'autoHide';
    state.glassStyle = s.glassStyle || 'regular';
  }

  const savedConfig = parseJsonSafe(storeGet('config'), null);
  state.config = savedConfig || { ...DEFAULT_CONFIG };
  state.session = parseJsonSafe(storeGet('session'), null);
  const savedMode = storeGet('mode');

  if (savedMode === 'cloud' && !isPlaceholderConfig(state.config) && state.session) {
    state.mode = 'cloud';
    state.items = parseJsonSafe(storeGet('cache'), []);
    renderAll();
    syncItems();
  } else if (savedMode === 'local') {
    state.mode = 'local';
    const savedLocal = parseJsonSafe(storeGet('demoItems'), []);
    state.items = Array.isArray(savedLocal) ? savedLocal : [];
    syncItems();
  } else {
    state.mode = 'demo';
    const savedDemo = parseJsonSafe(storeGet('demoItems'), null);
    state.items = Array.isArray(savedDemo) && savedDemo.length ? savedDemo : buildInitialDemoItems();
    storeSet('demoItems', state.items);
    renderAll();
    setStatus('演示模式 · 移开鼠标自动收缩到右边缘');
  }
}

window.__initNative = function (payload) { hydrateAndStart(payload); };

// Wire up UI events
$('peekStrip').addEventListener('click', () => {
  postNative({ action: 'setPanelState', state: state.hoverExpand ? 'expanded' : 'sidebar' });
});

$('expandToggle').addEventListener('click', () => {
  const next = state.panelState === 'expanded' ? 'sidebar' : 'expanded';
  state.panelState = next;
  renderNativeControls();
  postNative({ action: 'setPanelState', state: next });
});

$('modeQuickToggle').addEventListener('click', () => {
  const next = state.dockMode === 'autoHide' ? 'desktop' : 'autoHide';
  state.dockMode = next;
  renderNativeControls();
  postNative({ action: 'setDockMode', mode: next });
});

$('pinBtn').addEventListener('click', () => {
  state.pinned = !state.pinned;
  renderNativeControls();
  postNative({ action: 'setPinned', pinned: state.pinned });
});

$('peekNowBtn').addEventListener('click', () => {
  state.pinned = false;
  state.panelState = 'peek';
  renderNativeControls();
  postNative({ action: 'setPinned', pinned: false });
  postNative({ action: 'setPanelState', state: 'peek' });
});

$('syncBtn').addEventListener('click', () => syncItems());
$('settingsBtn').addEventListener('click', () => openSettingsModal());
$('connectBannerBtn').addEventListener('click', () => openSettingsModal());

$('prevMonth').addEventListener('click', () => {
  state.view = new Date(state.view.getFullYear(), state.view.getMonth() - 1, 1);
  renderCalendar();
});
$('nextMonth').addEventListener('click', () => {
  state.view = new Date(state.view.getFullYear(), state.view.getMonth() + 1, 1);
  renderCalendar();
});
$('todayBtn').addEventListener('click', () => {
  const now = new Date();
  state.view = new Date(now.getFullYear(), now.getMonth(), 1);
  state.selected = localDate(now);
  state.sidebarTab = 'day';
  renderAll();
});
$('quickAddTop').addEventListener('click', () => openEntryModal(null, '日程'));

$('tabDay').addEventListener('click', () => { state.sidebarTab = 'day'; renderTabs(); });
$('tabWeek').addEventListener('click', () => { state.sidebarTab = 'week'; renderTabs(); });

for (const btn of document.querySelectorAll('[data-add-type]')) {
  btn.addEventListener('click', () => openEntryModal(null, btn.dataset.addType));
}

$('closeEntryModal').addEventListener('click', closeEntryModal);
$('cancelEntryBtn').addEventListener('click', closeEntryModal);
$('entryForm').addEventListener('submit', saveEntry);
$('deleteEntryBtn').addEventListener('click', () => {
  if (state.editing) {
    const target = state.editing;
    closeEntryModal();
    deleteItem(target);
  }
});

$('closeSettingsModal').addEventListener('click', closeSettingsModal);
$('cloudConfigForm').addEventListener('submit', handleCloudLogin);
$('signupBtn').addEventListener('click', handleCloudSignup);
$('useDemoBtn').addEventListener('click', switchToDemoMode);
$('logoutBtn').addEventListener('click', handleLogout);

$('hoverExpandToggle').addEventListener('change', (e) => {
  state.hoverExpand = e.target.checked;
  postNative({ action: 'setHoverExpand', enabled: state.hoverExpand });
});

for (const btn of $('dockModeSegmented').querySelectorAll('button')) {
  btn.addEventListener('click', () => {
    state.dockMode = btn.dataset.mode;
    renderNativeControls();
    postNative({ action: 'setDockMode', mode: state.dockMode });
  });
}

for (const btn of $('glassStyleSegmented').querySelectorAll('button')) {
  btn.addEventListener('click', () => {
    state.glassStyle = btn.dataset.glass;
    renderNativeControls();
    postNative({ action: 'setGlassStyle', style: state.glassStyle });
  });
}

document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') {
    if (!$('entryBackdrop').classList.contains('hidden')) closeEntryModal();
    if (!$('settingsBackdrop').classList.contains('hidden')) closeSettingsModal();
  }
});

updateClock();
setInterval(updateClock, 15 * 1000);
setInterval(() => {
  if (state.mode === 'cloud' && state.session) syncItems();
}, 5 * 60 * 1000);

if (!isNative) {
  hydrateAndStart({ state: { panelState: 'expanded', hoverExpand: false, dockMode: 'floating', glassStyle: 'regular' } });
}
