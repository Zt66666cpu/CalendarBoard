const $ = (id) => document.getElementById(id);
const { localDate, parseDate, addDays, makeUuid32, tokenUserId } = CBCore;

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
  meta: CBCore.defaultMeta(),
  offline: false,
  readOnly: false,
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
  recentDoneIds: new Set(),
  undoTimers: new Map(),
  subview: null, // null | 'review' | 'archive'
  archiveFilter: 'all',
  archiveLimit: 200,
  archiveFocusIso: null,
  deleteConfirmArmed: false,
  deleteConfirmTimer: null,
};

let activeUndoStatusId = null;

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

function nativeTransport(url, options = {}) {
  if (!isNative) return fetch(url, options);
  const id = `req_${++httpCounter}_${Date.now()}`;
  return new Promise((resolve, reject) => {
    pendingHttp.set(id, (res) => {
      if (res.networkError) {
        reject(new Error('网络连接失败，请检查网络后重试。'));
        return;
      }
      resolve({ status: res.status || 0, text: res.text || '' });
    });
    postNative({
      action: 'httpRequest',
      id,
      url,
      method: options.method || 'GET',
      headers: options.headers || {},
      body: options.body,
    });
  });
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

const apiClient = CBCore.createApi({
  getConfig: () => state.config,
  getSession: () => state.session,
  saveSession: (data) => saveSession(data),
  transport: nativeTransport,
  getLatestPayload: (id) => {
    const found = state.items.find((it) => it.id === id);
    if (!found) return undefined;
    const copy = { ...found };
    delete copy.id;
    return copy;
  },
});

function setStatus(message, isError = false) {
  activeUndoStatusId = null;
  const el = $('statusText');
  el.textContent = message;
  el.classList.toggle('error', isError);
}

function isLocalMode() {
  return state.mode === 'demo' || state.mode === 'local';
}

function formatMonthDay(iso) {
  const date = parseDate(iso);
  if (Number.isNaN(date.getTime())) return '';
  return `${date.getMonth() + 1}月${date.getDate()}日`;
}

function nowMinutes() {
  const now = new Date();
  return now.getHours() * 60 + now.getMinutes();
}

function canMutate() {
  if (state.readOnly) {
    setStatus('请升级应用', true);
    return false;
  }
  if (state.mode === 'cloud' && state.offline) {
    setStatus('没有保存成功，请重试', true);
    return false;
  }
  return true;
}

function mutateItem(id, newPayload, onSuccessStatus) {
  if (state.readOnly) {
    setStatus('请升级应用', true);
    return;
  }
  if (state.mode === 'cloud' && state.offline) {
    setStatus('没有保存成功，请重试', true);
    return;
  }
  const prevItem = state.items.find((it) => it.id === id);
  state.items = state.items.map((it) => (it.id === id ? { ...newPayload, id } : it));
  if (isLocalMode()) {
    storeSet('demoItems', state.items);
    renderAll();
    if (onSuccessStatus) onSuccessStatus();
    return;
  }
  storeSet('cache', state.items);
  renderAll();
  return apiClient.enqueueWrite(id, () => apiClient.update(id, newPayload))
    .then(() => {
      if (onSuccessStatus) onSuccessStatus();
    })
    .catch(async (err) => {
      const timer = state.undoTimers.get(id);
      if (timer) {
        clearTimeout(timer);
        state.undoTimers.delete(id);
      }
      if (err && err.code === 'gone') {
        state.items = state.items.filter((it) => it.id !== id);
        state.recentDoneIds.delete(id);
        storeSet('cache', state.items);
        setStatus('该事项已在其他设备删除', true);
        await syncItems();
        return;
      }
      if (prevItem) {
        state.items = state.items.map((it) => (it.id === id ? prevItem : it));
      } else {
        state.items = state.items.filter((it) => it.id !== id);
      }
      state.recentDoneIds.delete(id);
      storeSet('cache', state.items);
      renderAll();
      setStatus('没有保存成功，请重试', true);
    });
}

function academicWeekLabel(date) {
  return CBCore.termWeekLabel(state.meta, date);
}

async function syncItems() {
  if (isLocalMode()) {
    state.meta = parseJsonSafe(storeGet('meta'), null) || state.meta || CBCore.defaultMeta();
    state.readOnly = Number(state.meta?.schemaVersion || 2) > CBCore.SUPPORTED_SCHEMA;
    renderAll();
    if (state.readOnly) {
      setStatus('请升级应用', true);
    } else {
      const courseCount = state.items.filter((it) => it.type === '课程').length;
      setStatus(state.mode === 'local'
        ? `已同步本地课表（${courseCount} 节）· 移开鼠标自动收缩`
        : '演示模式 · 移开鼠标自动收缩到右边缘');
    }
    return;
  }
  if (!state.session) {
    setStatus('未登录云端 · 请点击「连接云端」', true);
    renderAll();
    return;
  }
  setStatus('正在同步云端…');
  try {
    const fetched = await apiClient.fetchAll();
    const meta = await apiClient.ensureMeta(fetched.meta);
    state.items = fetched.items;
    state.meta = meta || CBCore.defaultMeta();
    state.offline = false;
    storeSet('cache', state.items);
    storeSet('meta', state.meta);
    const t = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
    setStatus(`已同步 · ${t}`);
  } catch (err) {
    state.items = parseJsonSafe(storeGet('cache'), []);
    state.meta = parseJsonSafe(storeGet('meta'), null) || CBCore.defaultMeta();
    state.offline = true;
    setStatus(`离线缓存 · ${err.message}`, true);
  }
  state.readOnly = Number(state.meta?.schemaVersion || 2) > CBCore.SUPPORTED_SCHEMA;
  if (state.readOnly) setStatus('请升级应用', true);
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
  if (checkReviewPrompt()) renderAll();
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
  const today = state.todayIso;

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
    const dayData = CBCore.dayItems(state.items, iso, state.todayIso);
    const dayItems = [
      ...dayData.courses,
      ...dayData.todos,
      ...(iso === state.todayIso ? dayData.pending : []),
      ...dayData.schedules,
    ].sort(CBCore.byTime);

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
      state.subview = null;
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
  if (weekLbl) subParts.push(`${state.meta?.termName || '2026秋'} ${weekLbl}`);
  if (lunar.label) subParts.push(`农历 ${lunar.label}`);
  subParts.push('点击事项编辑');
  $('selectedSubtitle').textContent = subParts.join(' · ');
}

function showUndoStatus(item) {
  activeUndoStatusId = item.id;
  const el = $('statusText');
  el.classList.remove('error');
  el.replaceChildren();
  el.append(document.createTextNode(`已完成「${item.title}」 · `));
  const undoBtn = document.createElement('button');
  undoBtn.type = 'button';
  undoBtn.className = 'undo-inline-btn';
  undoBtn.textContent = '撤销';
  undoBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    undoCompleteTodo(item.id);
  });
  el.append(undoBtn);
}

function completeTodo(item) {
  if (!item || !item.id) return;
  if (!canMutate()) return;
  const id = item.id;
  state.recentDoneIds.add(id);
  const existing = state.undoTimers.get(id);
  if (existing) clearTimeout(existing);
  state.undoTimers.set(id, setTimeout(() => {
    state.undoTimers.delete(id);
    state.recentDoneIds.delete(id);
    if (activeUndoStatusId === id) {
      setStatus(`已完成「${item.title}」`);
    }
    renderAll();
  }, 5000));
  showUndoStatus(item);
  mutateItem(id, CBCore.applyDone(item, true));
}

function undoCompleteTodo(id) {
  const timer = state.undoTimers.get(id);
  if (timer) clearTimeout(timer);
  state.undoTimers.delete(id);
  state.recentDoneIds.delete(id);
  const current = state.items.find((it) => it.id === id);
  if (!current) {
    renderAll();
    return;
  }
  mutateItem(id, CBCore.applyDone(current, false), () => setStatus('已撤销完成'));
}

function actionButton(label, handler) {
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'entry-done-btn';
  btn.textContent = label;
  btn.disabled = state.offline || state.readOnly;
  btn.addEventListener('click', (e) => {
    e.stopPropagation();
    handler();
  });
  return btn;
}

function createEntryMain(item, metaText) {
  const main = document.createElement('div');
  main.className = 'entry-main';
  const title = document.createElement('div');
  title.className = 'entry-title';
  title.textContent = item.title;
  main.append(title);
  if (metaText) {
    const meta = document.createElement('div');
    meta.className = 'entry-meta';
    meta.textContent = metaText;
    main.append(meta);
  }
  return main;
}

function renderDayList() {
  const dayData = CBCore.dayItems(state.items, state.selected, state.todayIso, state.recentDoneIds);
  const isToday = state.selected === state.todayIso;

  // 课程：过去日期保留并置灰，卡片上不再提供删除入口。
  const courseList = $('listCourse');
  const courseGroup = courseList.closest('.cat-group');
  courseList.replaceChildren();
  if (courseGroup) courseGroup.classList.remove('hidden');
  $('countCourse').textContent = String(dayData.courses.length);
  if (!dayData.courses.length) {
    const hint = document.createElement('div');
    hint.className = 'empty-hint';
    hint.textContent = dayData.past ? '这天没有课程' : '今天没有课程';
    courseList.append(hint);
  }
  for (const item of dayData.courses) {
    const card = document.createElement('div');
    card.className = `entry-card${dayData.past ? ' muted' : ''}`;
    const metaParts = [item.time, itemPlace(item)].filter(Boolean);
    card.append(createEntryMain(item, metaParts.join(' · ')));
    card.addEventListener('click', () => openEntryModal(item));
    courseList.append(card);
  }
  if (dayData.past && dayData.archivedCount > 0) {
    const archived = document.createElement('button');
    archived.type = 'button';
    archived.className = 'archive-link';
    archived.textContent = `这一天有 ${dayData.archivedCount} 条已归档 ›`;
    archived.addEventListener('click', () => openArchiveView(state.selected));
    courseList.append(archived);
  }

  // 待办：只展示当天待办，待处理单独成组。
  const todoList = $('listTodo');
  const todoGroup = todoList.closest('.cat-group');
  todoList.replaceChildren();
  if (todoGroup) todoGroup.classList.toggle('hidden', dayData.past);
  $('countTodo').textContent = String(dayData.todos.length);
  if (!dayData.todos.length) {
    const hint = document.createElement('div');
    hint.className = 'empty-hint';
    hint.textContent = '暂无待办事项';
    todoList.append(hint);
  }
  for (const item of dayData.todos) {
    const done = CBCore.isDone(item);
    const card = document.createElement('div');
    card.className = `entry-card${done ? ' recent-done' : ''}`;
    const metaParts = [item.time, itemPlace(item)].filter(Boolean);
    card.append(createEntryMain(item, metaParts.join(' · ')));
    const btn = done
      ? actionButton('撤销', () => undoCompleteTodo(item.id))
      : actionButton('已完成', () => completeTodo(item));
    card.append(btn);
    card.addEventListener('click', () => openEntryModal(item));
    todoList.append(card);
  }

  // 待处理：仅今天显示，全部为今天之前顺延下来的待办。
  const pending = dayData.pending;
  const pendingGroup = $('groupPending');
  const pendingList = $('listPending');
  const showPending = !dayData.past && isToday && pending.length > 0;
  pendingGroup.classList.toggle('hidden', !showPending);
  pendingList.replaceChildren();
  $('countPending').textContent = String(pending.length);
  for (const item of pending) {
    const done = CBCore.isDone(item);
    const card = document.createElement('div');
    card.className = `entry-card${done ? ' recent-done' : ''}`;
    card.append(createEntryMain(item, `原定 ${formatMonthDay(item.date)}`));
    const actions = document.createElement('div');
    actions.className = 'entry-actions';
    if (done) {
      actions.append(actionButton('撤销', () => undoCompleteTodo(item.id)));
    } else {
      actions.append(
        actionButton('已完成', () => completeTodo(item)),
        actionButton('顺延', () => {
          mutateItem(item.id, CBCore.applyPostpone(item, CBCore.addDays(state.todayIso, 1)),
            () => setStatus(`已顺延「${item.title}」到明天`));
        }),
      );
      const dateInput = document.createElement('input');
      dateInput.type = 'date';
      dateInput.className = 'mini-date-input';
      dateInput.title = '选择顺延日期';
      dateInput.value = CBCore.addDays(state.todayIso, 1);
      dateInput.disabled = state.offline || state.readOnly;
      dateInput.addEventListener('click', (e) => e.stopPropagation());
      dateInput.addEventListener('change', (e) => {
        e.stopPropagation();
        const targetIso = dateInput.value;
        if (!targetIso) return;
        mutateItem(item.id, CBCore.applyPostpone(item, targetIso),
          () => setStatus(`已顺延「${item.title}」到 ${formatMonthDay(targetIso)}`));
      });
      actions.append(dateInput, actionButton('放弃', () => {
        mutateItem(item.id, CBCore.applyDrop(item), () => setStatus(`已放弃「${item.title}」`));
      }));
    }
    card.append(actions);
    card.addEventListener('click', () => openEntryModal(item));
    pendingList.append(card);
  }

  // 日程：卡片上不再提供删除入口。
  const scheduleList = $('listSchedule');
  const scheduleGroup = scheduleList.closest('.cat-group');
  scheduleList.replaceChildren();
  if (scheduleGroup) scheduleGroup.classList.toggle('hidden', dayData.past);
  $('countSchedule').textContent = String(dayData.schedules.length);
  if (!dayData.schedules.length) {
    const hint = document.createElement('div');
    hint.className = 'empty-hint';
    hint.textContent = '暂无日程安排';
    scheduleList.append(hint);
  }
  for (const item of dayData.schedules) {
    const card = document.createElement('div');
    card.className = 'entry-card';
    const metaParts = [item.time, itemPlace(item)].filter(Boolean);
    card.append(createEntryMain(item, metaParts.join(' · ')));
    card.addEventListener('click', () => openEntryModal(item));
    scheduleList.append(card);
  }
}

function renderWeekView() {
  const container = $('weekView');
  container.replaceChildren();
  const weekdays = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];
  const days = CBCore.weekAhead(state.items, state.todayIso);

  for (const day of days) {
    const d = parseDate(day.iso);
    const block = document.createElement('div');
    block.className = 'week-day-block';

    const head = document.createElement('div');
    head.className = 'week-day-head';
    const headLabel = document.createElement('span');
    headLabel.textContent = `${d.getMonth() + 1}月${d.getDate()}日 · ${weekdays[d.getDay()]}`;
    head.append(headLabel);
    const addBtn = document.createElement('button');
    addBtn.type = 'button';
    addBtn.className = 'add-mini';
    addBtn.textContent = '＋';
    addBtn.title = '给这天添加日程';
    addBtn.addEventListener('click', () => {
      state.selected = day.iso;
      openEntryModal(null, '日程');
    });
    head.append(addBtn);
    block.append(head);

    if (day.courseCount > 0) {
      const summary = document.createElement('div');
      summary.className = 'course-summary-row';
      summary.textContent = day.firstCourseStart !== null
        ? `${day.courseCount} 节课 · 首节 ${CBCore.formatMinutes(day.firstCourseStart)}`
        : `${day.courseCount} 节课`;
      block.append(summary);
    }

    if (!day.items.length) {
      if (day.courseCount === 0) {
        const empty = document.createElement('div');
        empty.className = 'empty-hint';
        empty.textContent = '暂无安排';
        block.append(empty);
      }
    } else {
      const list = document.createElement('div');
      list.className = 'cat-list';
      for (const item of day.items) {
        const card = document.createElement('div');
        card.className = 'entry-card';
        const metaParts = [item.time, itemPlace(item)].filter(Boolean);
        card.append(createEntryMain(item, metaParts.join(' · ')));
        if (item.type === '待办') {
          card.append(actionButton('已完成', () => completeTodo(item)));
        }
        card.addEventListener('click', () => openEntryModal(item));
        list.append(card);
      }
      block.append(list);
    }
    container.append(block);
  }
}

let reviewAutoCloseTimer = null;

function currentReviewList() {
  return CBCore.reviewList(state.items, state.todayIso, nowMinutes());
}

function checkReviewPrompt() {
  if (state.subview) return false;
  const list = currentReviewList();
  if (!list.length) return false;
  if (nowMinutes() < CBCore.reviewTriggerMinutes(state.items, state.todayIso)) return false;
  if (storeGet('lastReviewPromptDate') === state.todayIso) return false;
  state.subview = 'review';
  storeSet('lastReviewPromptDate', state.todayIso);
  return true;
}

function renderReviewBanner() {
  const banner = $('reviewBanner');
  const list = currentReviewList();
  if (!list.length) {
    banner.classList.add('hidden');
    banner.replaceChildren();
    return;
  }
  banner.classList.remove('hidden');
  const text = document.createElement('span');
  text.textContent = `今日回顾 · 还有 ${list.length} 项待决定 ›`;
  banner.replaceChildren(text);
}

function closeReviewView() {
  if (reviewAutoCloseTimer) {
    clearTimeout(reviewAutoCloseTimer);
    reviewAutoCloseTimer = null;
  }
  state.subview = null;
  renderAll();
}

function renderReviewView() {
  const container = $('reviewView');
  if (reviewAutoCloseTimer) {
    clearTimeout(reviewAutoCloseTimer);
    reviewAutoCloseTimer = null;
  }
  container.replaceChildren();
  const list = currentReviewList();

  if (!list.length) {
    const done = document.createElement('div');
    done.className = 'empty-hint archive-empty';
    done.textContent = '都处理好了';
    container.append(done);
    if (state.subview === 'review') {
      reviewAutoCloseTimer = setTimeout(() => {
        reviewAutoCloseTimer = null;
        if (state.subview === 'review') {
          state.subview = null;
          renderAll();
        }
      }, 700);
    }
    return;
  }

  const head = document.createElement('div');
  head.className = 'subview-head';
  const title = document.createElement('div');
  title.className = 'subview-title';
  title.textContent = `今日回顾 · 还有 ${list.length} 项待决定`;
  head.append(title);
  container.append(head);

  const listEl = document.createElement('div');
  listEl.className = 'cat-list';
  for (const item of list) {
    const card = document.createElement('div');
    card.className = 'entry-card';
    const metaParts = [`原定 ${formatMonthDay(item.date)}`];
    if (item.time) metaParts.push(item.time);
    if (itemPlace(item)) metaParts.push(itemPlace(item));
    card.append(createEntryMain(item, metaParts.join(' · ')));
    const actions = document.createElement('div');
    actions.className = 'entry-actions';
    actions.append(
      actionButton('已完成', () => completeTodo(item)),
      actionButton('顺延', () => {
        mutateItem(item.id, CBCore.applyPostpone(item, CBCore.addDays(state.todayIso, 1)),
          () => setStatus(`已顺延「${item.title}」到明天`));
      }),
      actionButton('放弃', () => {
        mutateItem(item.id, CBCore.applyDrop(item), () => setStatus(`已放弃「${item.title}」`));
      }),
    );
    card.append(actions);
    listEl.append(card);
  }
  container.append(listEl);

  const foot = document.createElement('div');
  foot.className = 'subview-foot';
  const postponeAll = document.createElement('button');
  postponeAll.type = 'button';
  postponeAll.className = 'ghost-btn';
  postponeAll.textContent = '全部顺延到明天';
  postponeAll.disabled = state.offline || state.readOnly;
  postponeAll.addEventListener('click', () => {
    if (!canMutate()) return;
    const tomorrow = CBCore.addDays(state.todayIso, 1);
    const targets = currentReviewList();
    for (const item of targets) {
      mutateItem(item.id, CBCore.applyPostpone(item, tomorrow));
    }
    setStatus('已全部顺延到明天');
  });
  const closeBtn = document.createElement('button');
  closeBtn.type = 'button';
  closeBtn.className = 'ghost-btn';
  closeBtn.textContent = '关闭';
  closeBtn.addEventListener('click', closeReviewView);
  foot.append(postponeAll, closeBtn);
  container.append(foot);
}

const ARCHIVE_STATE_LABELS = { done: '已完成', dropped: '已放弃', ended: '已结束' };

function attachTwoStepDelete(button, onConfirm) {
  button.addEventListener('click', (e) => {
    e.stopPropagation();
    if (button.dataset.confirmArmed === '1') {
      if (button.confirmTimer) clearTimeout(button.confirmTimer);
      button.dataset.confirmArmed = '0';
      button.textContent = '删除';
      onConfirm();
      return;
    }
    button.dataset.confirmArmed = '1';
    button.textContent = '确认删除';
    button.confirmTimer = setTimeout(() => {
      button.dataset.confirmArmed = '0';
      button.textContent = '删除';
    }, 3000);
  });
}

async function deleteArchivedItems(itemsToDelete) {
  if (!itemsToDelete.length) return;
  if (!canMutate()) return;
  if (isLocalMode()) {
    const ids = new Set(itemsToDelete.map((it) => it.id));
    state.items = state.items.filter((it) => !ids.has(it.id));
    storeSet('demoItems', state.items);
    renderAll();
    setStatus(`已清理 ${itemsToDelete.length} 条归档`);
    return;
  }
  setStatus('正在清理归档…');
  try {
    for (const item of itemsToDelete) {
      await apiClient.remove(item.id);
    }
    await syncItems();
    setStatus(`已清理 ${itemsToDelete.length} 条归档`);
  } catch (err) {
    setStatus(err.message, true);
  }
}

function attachCleanupButton(button) {
  let armedItems = null;
  let resetTimer = null;
  const reset = () => {
    if (resetTimer) clearTimeout(resetTimer);
    resetTimer = null;
    armedItems = null;
    button.textContent = '清理 90 天前的归档';
  };
  button.addEventListener('click', async () => {
    if (armedItems) {
      const targets = armedItems;
      reset();
      await deleteArchivedItems(targets);
      return;
    }
    if (!canMutate()) return;
    const cutoff = CBCore.addDays(state.todayIso, -90);
    const groups = CBCore.archiveList(state.items, state.todayIso, 'all');
    const oldItems = [];
    for (const group of groups) {
      if (String(group.iso) < cutoff) {
        for (const entry of group.entries) oldItems.push(entry.item);
      }
    }
    if (!oldItems.length) {
      setStatus('没有 90 天前的归档事项');
      return;
    }
    armedItems = oldItems;
    button.textContent = `确认清理 ${oldItems.length} 条`;
    resetTimer = setTimeout(reset, 3000);
  });
}

function openArchiveView(focusIso = null) {
  state.subview = 'archive';
  state.archiveFilter = 'all';
  state.archiveLimit = 200;
  state.archiveFocusIso = focusIso;
  renderAll();
}

function renderArchiveView() {
  const container = $('archiveView');
  container.replaceChildren();
  const filter = state.archiveFilter;

  const head = document.createElement('div');
  head.className = 'subview-head';
  const title = document.createElement('div');
  title.className = 'subview-title';
  title.textContent = '归档';
  const backBtn = document.createElement('button');
  backBtn.type = 'button';
  backBtn.className = 'pill-btn';
  backBtn.textContent = '返回';
  backBtn.addEventListener('click', () => {
    state.subview = null;
    state.archiveFocusIso = null;
    renderAll();
  });
  head.append(title, backBtn);

  const filters = document.createElement('div');
  filters.className = 'archive-filters';
  const filterOptions = [
    { key: 'all', label: '全部' },
    { key: '待办', label: '待办' },
    { key: '日程', label: '日程' },
  ];
  for (const option of filterOptions) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = `pill-btn archive-filter-btn${filter === option.key ? ' active' : ''}`;
    btn.textContent = option.label;
    btn.addEventListener('click', () => {
      state.archiveFilter = option.key;
      state.archiveLimit = 200;
      state.archiveFocusIso = null;
      renderAll();
    });
    filters.append(btn);
  }
  container.append(head, filters);

  const groups = CBCore.archiveList(state.items, state.todayIso, filter);
  let total = 0;
  for (const group of groups) total += group.entries.length;
  let shown = 0;
  const weekdays = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];
  for (const group of groups) {
    if (shown >= state.archiveLimit) break;
    const remain = state.archiveLimit - shown;
    const entries = group.entries.slice(0, remain);
    if (!entries.length) continue;
    shown += entries.length;

    const groupEl = document.createElement('div');
    groupEl.className = 'archive-group';
    groupEl.dataset.archiveIso = group.iso;
    const groupHead = document.createElement('div');
    groupHead.className = 'archive-date';
    const gd = parseDate(group.iso);
    groupHead.textContent = `${formatMonthDay(group.iso)} · ${weekdays[gd.getDay()]}`;
    groupEl.append(groupHead);

    const listEl = document.createElement('div');
    listEl.className = 'cat-list';
    for (const entry of entries) {
      const item = entry.item;
      const row = document.createElement('div');
      row.className = 'entry-card archive-row';
      const metaParts = [item.time, itemPlace(item)].filter(Boolean);
      row.append(createEntryMain(item, metaParts.join(' · ')));
      const stateTag = document.createElement('span');
      stateTag.className = 'state-tag';
      stateTag.textContent = ARCHIVE_STATE_LABELS[entry.state] || '';
      row.append(stateTag);

      if (item.type === '待办') {
        row.append(actionButton('恢复', () => {
          mutateItem(item.id, CBCore.applyRestore(item), () => setStatus(`已恢复「${item.title}」`));
        }));
      }
      const deleteBtn = document.createElement('button');
      deleteBtn.type = 'button';
      deleteBtn.className = 'entry-done-btn';
      deleteBtn.textContent = '删除';
      deleteBtn.disabled = state.offline || state.readOnly;
      attachTwoStepDelete(deleteBtn, () => deleteItem(item));
      row.append(deleteBtn);
      listEl.append(row);
    }
    groupEl.append(listEl);
    container.append(groupEl);
  }

  if (total === 0) {
    const empty = document.createElement('div');
    empty.className = 'empty-hint archive-empty';
    empty.textContent = '暂无归档事项';
    container.append(empty);
  } else if (total > shown) {
    const moreBtn = document.createElement('button');
    moreBtn.type = 'button';
    moreBtn.className = 'ghost-btn';
    moreBtn.textContent = '加载更多';
    moreBtn.addEventListener('click', () => {
      state.archiveLimit += 200;
      renderAll();
    });
    const moreWrap = document.createElement('div');
    moreWrap.className = 'archive-foot';
    moreWrap.append(moreBtn);
    container.append(moreWrap);
  }

  const foot = document.createElement('div');
  foot.className = 'subview-foot';
  const cleanupBtn = document.createElement('button');
  cleanupBtn.type = 'button';
  cleanupBtn.className = 'danger-btn';
  cleanupBtn.textContent = '清理 90 天前的归档';
  cleanupBtn.disabled = state.offline || state.readOnly;
  attachCleanupButton(cleanupBtn);
  foot.append(cleanupBtn);
  container.append(foot);

  if (state.archiveFocusIso) {
    const focusIso = state.archiveFocusIso;
    state.archiveFocusIso = null;
    requestAnimationFrame(() => {
      const target = container.querySelector(`[data-archive-iso="${focusIso}"]`);
      if (target) target.scrollIntoView({ block: 'start' });
    });
  }
}

function renderTabs() {
  const isDay = state.sidebarTab === 'day';
  $('tabDay').classList.toggle('active', isDay);
  $('tabWeek').classList.toggle('active', !isDay);
  const inSubview = state.subview !== null;
  $('dayView').classList.toggle('hidden', !isDay || inSubview);
  $('weekView').classList.toggle('hidden', isDay || inSubview);
  $('reviewView').classList.toggle('hidden', state.subview !== 'review');
  $('archiveView').classList.toggle('hidden', state.subview !== 'archive');
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
  checkReviewPrompt();
  renderNativeControls();
  renderModeBadge();
  renderSidebarHeader();
  renderTabs();
  renderCalendar();
  renderDayList();
  renderWeekView();
  renderReviewBanner();
  renderReviewView();
  renderArchiveView();
}

// Entry CRUD
function syncEntryEndState() {
  const start = $('entryStart').value;
  const end = $('entryEnd');
  if (!start) {
    end.value = '';
    end.disabled = true;
  } else {
    end.disabled = false;
  }
}

function resetDeleteConfirm() {
  if (state.deleteConfirmTimer) {
    clearTimeout(state.deleteConfirmTimer);
    state.deleteConfirmTimer = null;
  }
  state.deleteConfirmArmed = false;
  const btn = $('deleteEntryBtn');
  if (btn) btn.textContent = '删除';
}

function openEntryModal(item = null, defaultType = '日程') {
  if (state.readOnly) {
    setStatus('请升级应用', true);
    return;
  }
  if (state.mode === 'cloud' && state.offline) {
    setStatus('离线时只能查看，联网后再编辑。', true);
    return;
  }
  state.editing = item;
  $('entryModalTitle').textContent = item ? '编辑事项' : '新增事项';
  $('entryType').value = item?.type || defaultType;
  $('entryDate').value = item?.date || state.selected;
  $('entryTitle').value = item?.title || '';
  $('entryStart').value = CBCore.formatMinutes(CBCore.itemStart(item));
  $('entryEnd').value = CBCore.formatMinutes(CBCore.itemEnd(item));
  $('entryPlace').value = itemPlace(item || {});
  $('deleteEntryBtn').classList.toggle('hidden', !item);
  resetDeleteConfirm();
  syncEntryEndState();

  $('entryBackdrop').classList.remove('hidden');
  postNative({ action: 'setModalOpen', open: true });
  setTimeout(() => $('entryTitle').focus(), 40);
}

function closeEntryModal() {
  $('entryBackdrop').classList.add('hidden');
  state.editing = null;
  resetDeleteConfirm();
  postNative({ action: 'setModalOpen', open: false });
}

async function saveEntry(event) {
  event.preventDefault();
  if (state.readOnly) {
    setStatus('请升级应用', true);
    return;
  }
  const start = $('entryStart').value;
  const end = $('entryEnd').value;
  if ((!start && end) || (start && end && CBCore.parseMinutes(end) <= CBCore.parseMinutes(start))) {
    setStatus('结束时间需要晚于开始时间。', true);
    return;
  }
  const title = $('entryTitle').value.trim();
  if (!title) return;

  const id = state.editing?.id || makeUuid32();
  const payload = {
    ...(state.editing ? { ...state.editing } : {}),
    date: $('entryDate').value,
    type: $('entryType').value,
    title,
    startTime: start,
    endTime: start ? end : '',
    time: CBCore.buildTime(start, start ? end : ''),
    place: $('entryPlace').value.trim(),
    done: state.editing?.done || false,
  };
  delete payload.id;
  if (state.editing && 'room' in state.editing) payload.room = payload.place;
  if (state.editing && 'display' in state.editing) payload.display = payload.title;

  if (isLocalMode()) {
    const item = { ...payload, id };
    if (state.editing) {
      state.items = state.items.map((it) => (it.id === id ? item : it));
    } else {
      state.items.push(item);
    }
    storeSet('demoItems', state.items);
    closeEntryModal();
    state.selected = payload.date;
    state.view = parseDate(payload.date);
    renderAll();
    setStatus(state.mode === 'local' ? '已保存到本地日历' : '演示模式 · 已更新本地演示数据');
    return;
  }

  let previousItems = null;
  try {
    if (state.editing) {
      previousItems = state.items;
      state.items = state.items.map((it) => (it.id === id ? { ...it, ...payload } : it));
      await apiClient.enqueueWrite(id, () => apiClient.update(id, payload));
    } else {
      await apiClient.create(id, payload);
    }
    closeEntryModal();
    state.selected = payload.date;
    state.view = parseDate(payload.date);
    await syncItems();
  } catch (err) {
    if (previousItems) state.items = previousItems;
    if (state.editing && err.code === 'gone') {
      setStatus('该事项已在其他设备删除', true);
      await syncItems();
      return;
    }
    setStatus(err.message, true);
  }
}

async function deleteItem(item) {
  if (!item) return;
  if (state.readOnly) {
    setStatus('请升级应用', true);
    return;
  }
  if (state.mode === 'cloud' && state.offline) {
    setStatus('离线时不能删除。', true);
    return;
  }
  const timer = state.undoTimers.get(item.id);
  if (timer) {
    clearTimeout(timer);
    state.undoTimers.delete(item.id);
  }
  state.recentDoneIds.delete(item.id);
  if (isLocalMode()) {
    state.items = state.items.filter((it) => it.id !== item.id);
    storeSet('demoItems', state.items);
    renderAll();
    setStatus(`已删除「${item.title}」`);
    return;
  }
  try {
    await apiClient.remove(item.id);
    await syncItems();
    setStatus(`已删除「${item.title}」`);
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

function setTermSettingsMsg(text, kind = '') {
  const el = $('termSettingsMsg');
  el.textContent = text;
  el.className = `settings-msg${kind ? ` ${kind}` : ''}`;
}

async function saveTermSettings() {
  if (state.readOnly) {
    setTermSettingsMsg('请升级应用', 'error');
    return;
  }
  const termName = $('termNameInput').value.trim();
  const termStart = $('termStartInput').value;
  const termWeeks = Number($('termWeeksInput').value);
  if (!termName || termName.length > 12) {
    setTermSettingsMsg('学期名称需为 1–12 个字', 'error');
    return;
  }
  if (!/^\d{4}-\d{2}-\d{2}$/.test(termStart)) {
    setTermSettingsMsg('学期第一天格式应为 YYYY-MM-DD', 'error');
    return;
  }
  const [year, month, day] = termStart.split('-').map(Number);
  const parsedStart = new Date(year, month - 1, day);
  if (
    parsedStart.getFullYear() !== year ||
    parsedStart.getMonth() !== month - 1 ||
    parsedStart.getDate() !== day
  ) {
    setTermSettingsMsg('学期第一天不是有效日期', 'error');
    return;
  }
  if (!Number.isInteger(termWeeks) || termWeeks < 1 || termWeeks > 30) {
    setTermSettingsMsg('学期周数需为 1–30 的整数', 'error');
    return;
  }
  const updatedMeta = {
    ...(state.meta || CBCore.defaultMeta()),
    type: 'meta',
    termName,
    termStart,
    termWeeks,
  };
  if (isLocalMode()) {
    state.meta = updatedMeta;
    storeSet('meta', updatedMeta);
    renderAll();
    setTermSettingsMsg('学期设置已保存（本地）', 'ok');
    return;
  }
  try {
    const mId = CBCore.metaId(state.session?.user_id || tokenUserId(state.session?.access_token || ''));
    await apiClient.update(mId, updatedMeta);
    state.meta = updatedMeta;
    storeSet('meta', updatedMeta);
    renderAll();
    setTermSettingsMsg('学期设置已保存', 'ok');
  } catch (err) {
    setTermSettingsMsg(err.message, 'error');
  }
}

function openSettingsModal() {
  $('cfgUrl').value = isPlaceholderConfig(state.config) ? '' : state.config.url;
  $('cfgKey').value = isPlaceholderConfig(state.config) ? '' : state.config.key;
  $('authEmail').value = state.session?.email || '';
  $('authPassword').value = '';
  setSettingsMsg('');
  $('termNameInput').value = state.meta?.termName || '2026秋';
  $('termStartInput').value = state.meta?.termStart || '2026-08-31';
  $('termWeeksInput').value = String(state.meta?.termWeeks || 20);
  setTermSettingsMsg('');
  $('termNameInput').disabled = state.readOnly;
  $('termStartInput').disabled = state.readOnly;
  $('termWeeksInput').disabled = state.readOnly;
  $('saveTermBtn').disabled = state.readOnly;

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

  const existing = await apiClient.fetchAll();
  const existingIds = new Set(existing.items.map((it) => it.id));
  const toUpload = valid.filter((it) => !existingIds.has(it.id));
  if (!toUpload.length) return 0;

  for (let i = 0; i < toUpload.length; i += 50) {
    const batch = toUpload.slice(i, i + 50).map((it) => ({ id: it.id, payload: it }));
    await apiClient.request('/rest/v1/calendar_items', {
      method: 'POST',
      body: batch,
      headers: { Prefer: 'resolution=merge-duplicates,return=minimal' },
    });
  }
  return toUpload.length;
}

async function handleCloudLogin(event) {
  event.preventDefault();
  const vals = readAndValidateCloudInputs();
  if (!vals) return;
  setSettingsMsg('正在连接并登录…');
  const cfg = { url: vals.url, key: vals.key };
  const previousConfig = state.config;
  let authenticated = false;
  state.config = cfg;
  try {
    const data = await apiClient.rawRequest('/auth/v1/token?grant_type=password', {
      method: 'POST',
      body: { email: vals.email, password: vals.password },
    });
    authenticated = true;
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
    if (!authenticated) state.config = previousConfig;
    setSettingsMsg(err.message, 'error');
  }
}

async function handleCloudSignup() {
  const vals = readAndValidateCloudInputs();
  if (!vals) return;
  setSettingsMsg('正在提交注册…');
  const cfg = { url: vals.url, key: vals.key };
  const previousConfig = state.config;
  state.config = cfg;
  try {
    await apiClient.rawRequest('/auth/v1/signup', {
      method: 'POST',
      body: { email: vals.email, password: vals.password },
    });
    storeSet('config', cfg);
    setSettingsMsg('注册成功！请去邮箱点击验证链接，再回来点「保存并登录」。', 'ok');
  } catch (err) {
    state.config = previousConfig;
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
  storeSet('meta', null);
  state.meta = CBCore.defaultMeta();
  state.readOnly = false;
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
    state.meta = parseJsonSafe(storeGet('meta'), null) || CBCore.defaultMeta();
    state.readOnly = Number(state.meta?.schemaVersion || 2) > CBCore.SUPPORTED_SCHEMA;
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

$('tabDay').addEventListener('click', () => { state.sidebarTab = 'day'; state.subview = null; renderAll(); });
$('tabWeek').addEventListener('click', () => { state.sidebarTab = 'week'; state.subview = null; renderAll(); });

for (const btn of document.querySelectorAll('[data-add-type]')) {
  btn.addEventListener('click', () => openEntryModal(null, btn.dataset.addType));
}

$('closeEntryModal').addEventListener('click', closeEntryModal);
$('cancelEntryBtn').addEventListener('click', closeEntryModal);
$('entryForm').addEventListener('submit', saveEntry);
$('entryStart').addEventListener('input', syncEntryEndState);
$('deleteEntryBtn').addEventListener('click', () => {
  if (!state.editing) return;
  if (!state.deleteConfirmArmed) {
    state.deleteConfirmArmed = true;
    $('deleteEntryBtn').textContent = '确认删除';
    if (state.deleteConfirmTimer) clearTimeout(state.deleteConfirmTimer);
    state.deleteConfirmTimer = setTimeout(() => {
      state.deleteConfirmArmed = false;
      state.deleteConfirmTimer = null;
      $('deleteEntryBtn').textContent = '删除';
    }, 3000);
    return;
  }
  if (state.deleteConfirmTimer) clearTimeout(state.deleteConfirmTimer);
  state.deleteConfirmTimer = null;
  state.deleteConfirmArmed = false;
  const target = state.editing;
  closeEntryModal();
  deleteItem(target);
});

$('openReviewBtn').addEventListener('click', () => {
  state.subview = 'review';
  renderAll();
});

$('reviewBanner').addEventListener('click', () => {
  state.subview = 'review';
  renderAll();
});

$('archiveToggleBtn').addEventListener('click', () => {
  state.subview = state.subview === 'archive' ? null : 'archive';
  state.archiveFocusIso = null;
  renderAll();
});

$('closeSettingsModal').addEventListener('click', closeSettingsModal);
$('cloudConfigForm').addEventListener('submit', handleCloudLogin);
$('signupBtn').addEventListener('click', handleCloudSignup);
$('useDemoBtn').addEventListener('click', switchToDemoMode);
$('logoutBtn').addEventListener('click', handleLogout);
$('saveTermBtn').addEventListener('click', saveTermSettings);

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
