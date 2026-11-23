const $ = (id) => document.getElementById(id);
const { localDate, parseDate, tokenUserId } = CBCore;
const configKey = 'calendar-board-config-v1';
const sessionKey = 'calendar-board-session-v1';
const cacheKey = 'calendar-board-cache-v1';
const userCachePrefix = 'calendar-board-cache-v2-';
const userMetaCachePrefix = 'calendar-board-meta-v2-';
const defaultConfig = {
  url: 'https://samnpjgnvtearyeejjtg.supabase.co',
  key: 'sb_publishable_x061oGvVUpjXv0PG1J-Kag_Voayt0Ux',
};
const state = {
  config: JSON.parse(localStorage.getItem(configKey) || 'null') || defaultConfig,
  session: JSON.parse(localStorage.getItem(sessionKey) || 'null'),
  items: [],
  meta: CBCore.defaultMeta(),
  offline: false,
  readOnly: false,
  view: new Date(),
  selected: localDate(new Date()),
  todayIso: CBCore.localDate(new Date()),
  editing: null,
};
localStorage.removeItem(cacheKey); // Discard the old cache that was shared across accounts.

function showStatus(message, error = false) {
  $('status').textContent = message;
  $('status').classList.toggle('error', error);
}
function setPanel(name) {
  $('setupPanel').hidden = name !== 'setup';
  $('authPanel').hidden = name !== 'auth';
  $('calendarApp').hidden = name !== 'app';
}
function userCacheKey(session = state.session) {
  const userId = session?.user_id || tokenUserId(session?.access_token || '');
  return userId ? `${userCachePrefix}${userId}` : null;
}
function userMetaCacheKey(session = state.session) {
  const userId = session?.user_id || tokenUserId(session?.access_token || '');
  return userId ? `${userMetaCachePrefix}${userId}` : null;
}
function clearLocalAccount() {
  const key = userCacheKey();
  if (key) localStorage.removeItem(key);
  const metaKey = userMetaCacheKey();
  if (metaKey) localStorage.removeItem(metaKey);
  localStorage.removeItem(cacheKey); // Remove the old cache shared by every account.
  localStorage.removeItem(sessionKey);
  state.session = null;
  state.items = [];
  state.meta = CBCore.defaultMeta();
  state.offline = false;
  state.readOnly = false;
}
function saveSession(data) {
  state.session = {
    access_token: data.access_token,
    refresh_token: data.refresh_token,
    expires_at: data.expires_at || Math.floor(Date.now() / 1000) + (data.expires_in || 3600),
    user_id: data.user?.id || tokenUserId(data.access_token),
  };
  localStorage.setItem(sessionKey, JSON.stringify(state.session));
}
const apiClient = CBCore.createApi({
  getConfig: () => state.config,
  getSession: () => state.session,
  saveSession,
  getLatestPayload: (id) => {
    const found = state.items.find((it) => it.id === id);
    if (!found) return undefined;
    const copy = { ...found };
    delete copy.id;
    return copy;
  },
});
async function loadItems() {
  const session = state.session;
  try {
    const fetched = await apiClient.fetchAll();
    const meta = await apiClient.ensureMeta(fetched.meta);
    if (!state.session || userCacheKey() !== userCacheKey(session)) return;
    state.items = fetched.items;
    state.meta = meta || CBCore.defaultMeta();
    state.offline = false;
    const key = userCacheKey();
    if (key) localStorage.setItem(key, JSON.stringify(state.items));
    const metaKey = userMetaCacheKey();
    if (metaKey) localStorage.setItem(metaKey, JSON.stringify(state.meta));
    showStatus(`已同步 · ${new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}`);
  } catch (error) {
    if (!state.session || userCacheKey() !== userCacheKey(session)) return;
    const key = userCacheKey();
    const metaKey = userMetaCacheKey();
    state.items = key ? JSON.parse(localStorage.getItem(key) || '[]') : [];
    state.meta = (metaKey ? JSON.parse(localStorage.getItem(metaKey) || 'null') : null) || CBCore.defaultMeta();
    state.offline = true;
    showStatus(`${error.message} 正显示上次保存的内容；离线时不能编辑。`, true);
  }
  state.readOnly = Number(state.meta?.schemaVersion || 2) > CBCore.SUPPORTED_SCHEMA;
  if (state.readOnly) showStatus('请升级应用', true);
  render();
}
function itemPlace(item) { return item.place || item.room || ''; }
function make(tag, className, text) {
  const el = document.createElement(tag);
  if (className) el.className = className;
  if (text !== undefined) el.textContent = text;
  return el;
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
    const button = make('button', '', '');
    button.type = 'button';
    button.setAttribute('aria-label', `${iso}，查看当天事项`);
    if (date.getMonth() !== m) button.classList.add('outside');
    if (iso === today) button.classList.add('today');
    if (iso === state.selected) button.classList.add('selected');
    button.append(make('span', 'number', String(date.getDate())));
    const dots = make('span', 'dots');
    const dotsState = CBCore.dayDots(state.items, iso, state.todayIso);
    for (const type of ['course', 'todo', 'schedule']) {
      if (dotsState[type]) dots.append(make('span', `dot ${type}`));
    }
    button.append(dots);
    button.addEventListener('click', () => {
      state.selected = iso;
      state.view = new Date(date.getFullYear(), date.getMonth(), 1);
      render();
    });
    grid.append(button);
  }
}
function renderDay() {
  const date = parseDate(state.selected);
  const weekdays = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];
  const weekLbl = CBCore.termWeekLabel(state.meta, date);
  const weekSuffix = weekLbl ? ` · ${state.meta?.termName || '2026秋'} ${weekLbl}` : '';
  $('dayTitle').textContent = `${date.getMonth() + 1}月${date.getDate()}日 · ${weekdays[date.getDay()]}${weekSuffix}`;
  const container = $('dayItems');
  container.replaceChildren();
  const dayData = CBCore.dayItems(state.items, state.selected, state.todayIso);
  const actionsDisabled = state.offline || state.readOnly;
  const groups = [];
  if (dayData.past) {
    groups.push({ title: '课程', items: dayData.courses, empty: '这天没有课程', muted: true });
  } else {
    groups.push({ title: '课程', items: dayData.courses, empty: '这天没有课程' });
    groups.push({ title: '待办', items: dayData.todos, empty: '这天没有待办' });
    if (state.selected === state.todayIso && dayData.pending.length) {
      groups.push({ title: '待处理', items: dayData.pending, empty: '' });
    }
    groups.push({ title: '日程', items: dayData.schedules, empty: '这天没有日程' });
  }
  for (const groupDef of groups) {
    const group = make('section', 'group');
    group.append(make('h3', '', groupDef.title));
    if (!groupDef.items.length && groupDef.empty) group.append(make('p', 'empty', groupDef.empty));
    for (const item of groupDef.items) {
      const row = make('div', 'item');
      if (groupDef.muted) row.style.opacity = '0.55';
      const main = make('div', 'item-main');
      main.append(make('div', 'item-title', item.title));
      const metaParts = [];
      if (groupDef.title === '待处理') metaParts.push(item.date);
      if (item.time) metaParts.push(item.time);
      if (itemPlace(item)) metaParts.push(`场地：${itemPlace(item)}`);
      if (metaParts.length) main.append(make('div', 'item-meta', metaParts.join(' · ')));
      row.append(main);
      const actions = make('div', 'item-actions');
      const edit = make('button', '', '编辑');
      edit.type = 'button';
      edit.disabled = actionsDisabled;
      edit.addEventListener('click', () => openEntry(item));
      const remove = make('button', '', item.type === '待办' ? '完成' : '删除');
      remove.type = 'button';
      remove.disabled = actionsDisabled;
      remove.addEventListener('click', () => deleteEntry(item));
      actions.append(edit, remove);
      row.append(actions);
      group.append(row);
    }
    container.append(group);
  }
  if (dayData.past && dayData.archivedCount > 0) {
    container.append(make('p', 'muted', `这一天有 ${dayData.archivedCount} 条已归档 ›`));
  }
}
function render() {
  renderCalendar();
  renderDay();
  $('addButton').disabled = state.offline || state.readOnly;
}
function syncEndTimeState() {
  const start = $('entryStart').value;
  const end = $('entryEnd');
  if (!start) {
    end.value = '';
    end.disabled = true;
  } else {
    end.disabled = false;
  }
}
function openEntry(item = null) {
  if (state.readOnly) { showStatus('请升级应用', true); return; }
  if (state.offline) { showStatus('离线时只能查看，联网后再编辑。', true); return; }
  state.editing = item;
  $('entryDialogTitle').textContent = item ? '编辑事项' : '新增事项';
  $('entryType').value = item?.type || '日程';
  $('entryDate').value = item?.date || state.selected;
  $('entryTitle').value = item?.title || '';
  $('entryStart').value = CBCore.formatMinutes(CBCore.itemStart(item));
  $('entryEnd').value = CBCore.formatMinutes(CBCore.itemEnd(item));
  $('entryPlace').value = itemPlace(item || {});
  syncEndTimeState();
  $('entryDialog').showModal();
}
async function saveEntry(event) {
  event.preventDefault();
  if (state.readOnly) { showStatus('请升级应用', true); return; }
  const start = $('entryStart').value;
  const end = $('entryEnd').value;
  if ((!start && end) || (start && end && CBCore.parseMinutes(end) <= CBCore.parseMinutes(start))) {
    showStatus('结束时间需要晚于开始时间。', true);
    return;
  }
  const id = state.editing?.id || CBCore.makeUuid32();
  const payload = {
    ...(state.editing ? { ...state.editing } : {}),
    date: $('entryDate').value,
    type: $('entryType').value,
    title: $('entryTitle').value.trim(),
    startTime: start,
    endTime: start ? end : '',
    time: CBCore.buildTime(start, start ? end : ''),
    place: $('entryPlace').value.trim(),
    done: state.editing?.done || false,
  };
  delete payload.id;
  if (state.editing && 'room' in state.editing) payload.room = payload.place;
  if (state.editing && 'display' in state.editing) payload.display = payload.title;
  if (!payload.title) { showStatus('请填写内容。', true); return; }
  let previousItems = null;
  try {
    if (state.editing) {
      previousItems = state.items;
      state.items = state.items.map((it) => (it.id === id ? { ...it, ...payload } : it));
      await apiClient.enqueueWrite(id, () => apiClient.update(id, payload));
    } else {
      await apiClient.create(id, payload);
    }
    $('entryDialog').close();
    state.selected = payload.date;
    state.view = parseDate(payload.date);
    await loadItems();
  } catch (error) {
    if (previousItems) state.items = previousItems;
    if (state.editing && error.code === 'gone') {
      showStatus('该事项已在其他设备删除', true);
      await loadItems();
      return;
    }
    showStatus(error.message, true);
  }
}
async function deleteEntry(item) {
  if (state.readOnly) { showStatus('请升级应用', true); return; }
  if (state.offline) { showStatus('离线时不能删除。', true); return; }
  if (!confirm(item.type === '待办' ? `完成并删除「${item.title}」？` : `删除「${item.title}」？`)) return;
  try {
    await apiClient.remove(item.id);
    await loadItems();
  } catch (error) { showStatus(error.message, true); }
}
function checkDateRollover() {
  const nowIso = CBCore.localDate(new Date());
  if (nowIso !== state.todayIso) {
    const wasToday = state.selected === state.todayIso;
    state.todayIso = nowIso;
    if (wasToday) {
      state.selected = nowIso;
      state.view = CBCore.parseDate(nowIso);
    }
    render();
  }
}

$('setupForm').addEventListener('submit', (event) => {
  event.preventDefault();
  const url = $('projectUrl').value.trim().replace(/\/+$/, '');
  const key = $('publishableKey').value.trim();
  if (!/^https:\/\/[^/]+\.supabase\.co$/.test(url) || !key.startsWith('sb_publishable_')) {
    showStatus('请填写 Supabase 项目 URL 和 sb_publishable_ 开头的密钥。', true);
    return;
  }
  state.config = { url, key };
  localStorage.setItem(configKey, JSON.stringify(state.config));
  clearLocalAccount();
  setPanel('auth');
  showStatus('连接信息已保存。请登录日历账号。');
});
$('authForm').addEventListener('submit', async (event) => {
  event.preventDefault();
  try {
    const data = await apiClient.rawRequest('/auth/v1/token?grant_type=password', {
      method: 'POST', body: { email: $('email').value.trim(), password: $('password').value },
    });
    localStorage.removeItem(cacheKey);
    saveSession(data);
    $('password').value = '';
    setPanel('app');
    await loadItems();
  } catch (error) { showStatus(error.message, true); }
});
$('signupButton').addEventListener('click', async () => {
  if (!$('authForm').reportValidity() || $('password').value.length < 6) {
    showStatus('请先填写有效邮箱和至少 6 位密码。', true);
    return;
  }
  try {
    await apiClient.rawRequest('/auth/v1/signup', {
      method: 'POST', body: { email: $('email').value.trim(), password: $('password').value },
    });
    showStatus('注册请求已提交。请查收验证邮件，验证后再登录。');
  } catch (error) { showStatus(error.message, true); }
});
$('settingsButton').addEventListener('click', () => {
  if (!confirm('退出当前登录并重新填写连接信息？')) return;
  clearLocalAccount();
  setPanel('setup');
  showStatus('已退出登录。');
});
$('prevMonth').addEventListener('click', () => { state.view = new Date(state.view.getFullYear(), state.view.getMonth() - 1, 1); render(); });
$('nextMonth').addEventListener('click', () => { state.view = new Date(state.view.getFullYear(), state.view.getMonth() + 1, 1); render(); });
$('todayButton').addEventListener('click', () => { state.view = new Date(); state.selected = localDate(new Date()); render(); });
$('addButton').addEventListener('click', () => openEntry());
$('cancelEntry').addEventListener('click', () => $('entryDialog').close());
$('entryForm').addEventListener('submit', saveEntry);
$('entryStart').addEventListener('input', syncEndTimeState);

if (new URLSearchParams(location.search).has('demo')) {
  const today = localDate(new Date());
  state.items = [
    { id: '11111111111111111111111111111111', date: today, type: '课程', title: '微积分Ⅰ', time: '10:20-11:55', place: '品学楼B308' },
    { id: '22222222222222222222222222222222', date: today, type: '待办', title: '完成数学作业', time: '' },
    { id: '33333333333333333333333333333333', date: today, type: '日程', title: '社团活动', time: '19:00-20:00', place: '学生活动中心' },
  ];
  state.offline = true;
  setPanel('app');
  render();
  showStatus('界面预览 · 演示数据');
} else if (state.config) {
  $('projectUrl').value = state.config.url;
  $('publishableKey').value = state.config.key;
  if (state.session) { setPanel('app'); loadItems(); }
  else setPanel('auth');
} else setPanel('setup');
setInterval(() => {
  checkDateRollover();
  if (state.config && state.session && !document.hidden) loadItems();
}, 5 * 60 * 1000);
document.addEventListener('visibilitychange', () => {
  if (!document.hidden) {
    checkDateRollover();
    if (state.config && state.session) loadItems();
  }
});
