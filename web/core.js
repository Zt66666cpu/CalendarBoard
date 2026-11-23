(function (global) {
  'use strict';

  const SUPPORTED_SCHEMA = 2;
  const LEGACY_TIME_RE = /^\s*(\d{1,2})[:：](\d{2})\s*(?:[-–—~～至到]\s*(\d{1,2})[:：](\d{2}))?\s*$/;

  function localDate(date) {
    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
  }

  function parseDate(iso) {
    const [y, m, d] = String(iso).split('-').map(Number);
    return new Date(y, m - 1, d);
  }

  function addDays(dateOrIso, n) {
    if (typeof dateOrIso === 'string') {
      const d = parseDate(dateOrIso);
      d.setDate(d.getDate() + n);
      return localDate(d);
    }
    const d = dateOrIso;
    return new Date(d.getFullYear(), d.getMonth(), d.getDate() + n);
  }

  function makeUuid32() {
    const cryptoObj = global.crypto;
    if (cryptoObj && typeof cryptoObj.randomUUID === 'function') {
      return cryptoObj.randomUUID().replace(/-/g, '').toLowerCase();
    }
    if (cryptoObj && typeof cryptoObj.getRandomValues === 'function') {
      const bytes = cryptoObj.getRandomValues(new Uint8Array(16));
      return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
    }
    let out = '';
    while (out.length < 32) out += Math.floor(Math.random() * 16).toString(16);
    return out;
  }

  function tokenUserId(token) {
    try {
      const part = String(token || '').split('.')[1];
      if (!part || typeof atob !== 'function') return null;
      let encoded = part.replace(/-/g, '+').replace(/_/g, '/');
      encoded = encoded.padEnd(Math.ceil(encoded.length / 4) * 4, '=');
      const binary = atob(encoded);
      let jsonText = binary;
      if (typeof TextDecoder !== 'undefined') {
        const bytes = new Uint8Array(binary.length);
        for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
        jsonText = new TextDecoder().decode(bytes);
      }
      const payload = JSON.parse(jsonText);
      return payload.sub ?? null;
    } catch {
      return null;
    }
  }

  function nowIso(date = new Date()) {
    const offset = -date.getTimezoneOffset();
    const sign = offset >= 0 ? '+' : '-';
    const abs = Math.abs(offset);
    const offsetHours = String(Math.floor(abs / 60)).padStart(2, '0');
    const offsetMinutes = String(abs % 60).padStart(2, '0');
    const time = `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}:${String(date.getSeconds()).padStart(2, '0')}`;
    return `${localDate(date)}T${time}${sign}${offsetHours}:${offsetMinutes}`;
  }

  function isoToLocalDate(isoTimestamp) {
    return localDate(new Date(isoTimestamp));
  }

  function parseMinutes(str) {
    const match = /^\s*(\d{1,2})[:：](\d{2})\s*$/.exec(String(str ?? ''));
    if (!match) return null;
    const hour = Number(match[1]);
    const minute = Number(match[2]);
    if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return null;
    return hour * 60 + minute;
  }

  function formatMinutes(mins) {
    if (!Number.isInteger(mins) || mins < 0 || mins > 1439) return '';
    return `${String(Math.floor(mins / 60)).padStart(2, '0')}:${String(mins % 60).padStart(2, '0')}`;
  }

  function itemStart(p) {
    if (!p) return null;
    const parsed = parseMinutes(p.startTime);
    if (parsed !== null) return parsed;
    if (!p.time) return null;
    const match = LEGACY_TIME_RE.exec(String(p.time));
    if (!match) return null;
    return parseMinutes(`${match[1]}:${match[2]}`);
  }

  function itemEnd(p) {
    const start = itemStart(p);
    if (start === null) return null;
    let end = parseMinutes(p.endTime);
    if (end === null && p.time) {
      const match = LEGACY_TIME_RE.exec(String(p.time));
      if (match && match[3]) end = parseMinutes(`${match[3]}:${match[4]}`);
    }
    if (end === null || end <= start) return null;
    return end;
  }

  function buildTime(startTime, endTime) {
    const start = parseMinutes(startTime);
    if (start === null) return '';
    const end = parseMinutes(endTime);
    return end !== null && end > start
      ? `${formatMinutes(start)}-${formatMinutes(end)}`
      : formatMinutes(start);
  }

  function byTime(a, b) {
    const startA = itemStart(a);
    const startB = itemStart(b);
    if (startA !== null && startB === null) return -1;
    if (startA === null && startB !== null) return 1;
    if (startA !== null && startB !== null && startA !== startB) return startA - startB;

    const titleA = String(a?.title ?? '');
    const titleB = String(b?.title ?? '');
    if (titleA !== titleB) return titleA < titleB ? -1 : 1;

    const idA = String(a?.id ?? a?._row_id ?? '');
    const idB = String(b?.id ?? b?._row_id ?? '');
    if (idA !== idB) return idA < idB ? -1 : 1;
    return 0;
  }

  function isMeta(p) {
    return Boolean(p && p.type === 'meta');
  }

  function isDone(p) {
    return Boolean(p && p.type === '待办' && p.done === true);
  }

  function isDropped(p) {
    return Boolean(p && p.type === '待办' && p.status === 'dropped');
  }

  function isArchived(p, today) {
    return Boolean(
      p &&
      !isMeta(p) &&
      (isDone(p) || isDropped(p) || (p.type === '日程' && p.date && p.date < today)),
    );
  }

  function isPending(p, today) {
    return Boolean(
      p &&
      p.type === '待办' &&
      !isDone(p) &&
      !isDropped(p) &&
      p.date &&
      p.date < today,
    );
  }

  function dayItems(items, iso, today, recentDoneIds) {
    const isRecent = (p) => Boolean(
      p &&
      recentDoneIds &&
      (recentDoneIds instanceof Set
        ? recentDoneIds.has(p.id || p._row_id)
        : Array.isArray(recentDoneIds) && recentDoneIds.includes(p.id || p._row_id)),
    );
    const past = iso < today;
    const rows = (Array.isArray(items) ? items : []).filter((p) => p && !isMeta(p) && p.date);

    const courses = rows
      .filter((p) => p.date === iso && p.type === '课程')
      .sort(byTime);
    const archivedCount = rows.filter(
      (p) => p.date === iso && isArchived(p, today) && !isRecent(p),
    ).length;

    let schedules = [];
    let todos = [];
    let pending = [];
    if (!past) {
      schedules = rows
        .filter((p) => p.date === iso && p.type === '日程' && !isArchived(p, today))
        .sort(byTime);
      todos = rows
        .filter((p) => p.date === iso && p.type === '待办' && (!isArchived(p, today) || (isDone(p) && isRecent(p))))
        .sort(byTime);
      if (iso === today) {
        pending = rows
          .filter((p) => p.type === '待办' && p.date < today && !isDropped(p) && (!isDone(p) || isRecent(p)))
          .sort((a, b) => {
            const dateA = String(a.date ?? '');
            const dateB = String(b.date ?? '');
            if (dateA !== dateB) return dateA < dateB ? -1 : 1;
            return byTime(a, b);
          });
      }
    }

    return { past, courses, schedules, todos, pending, archivedCount };
  }

  function dayDots(items, iso, today) {
    const rows = (Array.isArray(items) ? items : []).filter((p) => p && !isMeta(p) && p.date);
    const course = rows.some((p) => p.date === iso && p.type === '课程');
    const todo = (
      iso >= today &&
      rows.some((p) => p.date === iso && p.type === '待办' && !isArchived(p, today))
    ) || (
      iso === today &&
      rows.some((p) => isPending(p, today))
    );
    const schedule = iso >= today && rows.some((p) => p.date === iso && p.type === '日程');
    return { course, todo, schedule };
  }

  function weekAhead(items, today) {
    const rows = Array.isArray(items) ? items : [];
    const result = [];
    for (let offset = 1; offset <= 7; offset += 1) {
      const iso = addDays(today, offset);
      const courses = rows
        .filter((p) => p && !isMeta(p) && p.date === iso && p.type === '课程')
        .sort(byTime);
      const firstCourse = courses.find((course) => itemStart(course) !== null);
      const dayRows = rows
        .filter(
          (p) => p &&
            !isMeta(p) &&
            p.date === iso &&
            (p.type === '日程' || (p.type === '待办' && !isArchived(p, today))),
        )
        .sort(byTime);
      result.push({
        iso,
        courseCount: courses.length,
        firstCourseStart: firstCourse ? itemStart(firstCourse) : null,
        items: dayRows,
      });
    }
    return result;
  }

  function archiveList(items, today, filter = 'all') {
    const groups = new Map();
    const rows = (Array.isArray(items) ? items : []).filter((p) => p && isArchived(p, today));
    for (const p of rows) {
      if ((filter === '待办' || filter === '日程') && p.type !== filter) continue;
      let state;
      let at;
      let iso;
      if (isDone(p)) {
        state = 'done';
        at = p.completedAt || null;
        iso = p.completedAt ? isoToLocalDate(p.completedAt) : p.date;
      } else if (isDropped(p)) {
        state = 'dropped';
        at = p.droppedAt || null;
        iso = p.droppedAt ? isoToLocalDate(p.droppedAt) : p.date;
      } else {
        state = 'ended';
        at = null;
        iso = p.date;
      }
      let group = groups.get(iso);
      if (!group) {
        group = { iso, entries: [] };
        groups.set(iso, group);
      }
      group.entries.push({ item: p, state, at });
    }

    const result = Array.from(groups.values()).sort((a, b) => {
      const isoA = String(a.iso ?? '');
      const isoB = String(b.iso ?? '');
      if (isoA === isoB) return 0;
      return isoA > isoB ? -1 : 1;
    });

    for (const group of result) {
      group.entries.sort((a, b) => {
        if (a.at !== null && b.at === null) return -1;
        if (a.at === null && b.at !== null) return 1;
        if (a.at !== null && b.at !== null) {
          const timestampOrder = Date.parse(b.at) - Date.parse(a.at);
          if (timestampOrder) return timestampOrder;
        }
        return byTime(a.item, b.item);
      });
    }
    return result;
  }

  function reviewTriggerMinutes(items, today) {
    let latest = null;
    for (const p of Array.isArray(items) ? items : []) {
      if (!p || p.type !== '待办' || p.date !== today || isDone(p) || isDropped(p)) continue;
      const start = itemStart(p);
      if (start === null) continue;
      const end = itemEnd(p) ?? start;
      latest = latest === null ? end : Math.max(latest, end);
    }
    return latest !== null ? Math.max(latest, 1080) : 1080;
  }

  function reviewList(items, today, nowMinutes) {
    const trigger = reviewTriggerMinutes(items, today);
    const reached = typeof nowMinutes === 'number' && nowMinutes >= trigger;
    return (Array.isArray(items) ? items : [])
      .filter(
        (p) => p &&
          p.type === '待办' &&
          !isDone(p) &&
          !isDropped(p) &&
          p.date &&
          (reached ? p.date <= today : p.date < today),
      )
      .sort((a, b) => {
        if (a.date !== b.date) return a.date < b.date ? -1 : 1;
        return byTime(a, b);
      });
  }

  function applyDone(p, done, nowStr = nowIso()) {
    const copy = { ...p };
    delete copy.id;
    delete copy._row_id;
    if (done) {
      copy.done = true;
      copy.completedAt = nowStr;
    } else {
      copy.done = false;
      delete copy.completedAt;
    }
    return copy;
  }

  function applyPostpone(p, targetIso) {
    const copy = { ...p };
    delete copy.id;
    delete copy._row_id;
    if (!copy.originalDate) copy.originalDate = p.date;
    copy.date = targetIso;
    copy.postponeCount = (Number(copy.postponeCount) || 0) + 1;
    return copy;
  }

  function applyDrop(p, nowStr = nowIso()) {
    const copy = { ...p };
    delete copy.id;
    delete copy._row_id;
    copy.status = 'dropped';
    copy.droppedAt = nowStr;
    return copy;
  }

  function applyRestore(p) {
    const copy = { ...p };
    delete copy.id;
    delete copy._row_id;
    delete copy.status;
    delete copy.droppedAt;
    if (copy.done) {
      copy.done = false;
      delete copy.completedAt;
    }
    return copy;
  }

  function nowNext(items, today, nowMinutes) {
    const courses = (Array.isArray(items) ? items : [])
      .filter((p) => p && p.date === today && p.type === '课程' && itemStart(p) !== null)
      .sort(byTime)
      .map((p) => ({
        id: p.id || p._row_id || '',
        title: p.title || '',
        place: p.place || p.room || '',
        start: itemStart(p),
        end: itemEnd(p),
      }));
    const current = courses.find(
      (course) => course.end !== null && course.start <= nowMinutes && nowMinutes < course.end,
    ) ?? null;
    const next = courses.find((course) => course.start > nowMinutes) ?? null;
    const freeUntil = current === null && next !== null ? next.start : null;
    return { current, next, freeUntil };
  }

  function metaId(userId) {
    return String(userId || '').replace(/-/g, '').toLowerCase();
  }

  function defaultMeta() {
    return {
      type: 'meta',
      schemaVersion: 2,
      termName: '2026秋',
      termStart: '2026-08-31',
      termWeeks: 20,
    };
  }

  function dateParts(value) {
    if (value instanceof Date) {
      if (Number.isNaN(value.getTime())) return null;
      return [value.getFullYear(), value.getMonth() + 1, value.getDate()];
    }
    const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(value ?? ''));
    if (!match) return null;
    return [Number(match[1]), Number(match[2]), Number(match[3])];
  }

  function termWeekLabel(meta, dateOrIso) {
    const targetParts = dateParts(dateOrIso);
    const startParts = dateParts(meta?.termStart || '2026-08-31');
    if (!targetParts || !startParts) return '';
    let totalWeeks = Number(meta?.termWeeks);
    if (!Number.isInteger(totalWeeks) || totalWeeks <= 0) totalWeeks = 20;
    const targetUtc = Date.UTC(targetParts[0], targetParts[1] - 1, targetParts[2]);
    const startUtc = Date.UTC(startParts[0], startParts[1] - 1, startParts[2]);
    const dayOffset = Math.floor((targetUtc - startUtc) / 86400000);
    if (dayOffset < 0) return '';
    const week = Math.floor(dayOffset / 7) + 1;
    if (week < 1 || week > totalWeeks) return '';
    return `第${week}周`;
  }

  class ApiError extends Error {
    constructor(code, status, message) {
      super(message);
      this.name = 'ApiError';
      this.code = code;
      this.status = status;
    }
  }

  function createApi({ getConfig, getSession, saveSession, transport, getLatestPayload } = {}) {
    const writeQueues = new Map();
    const send = typeof transport === 'function'
      ? transport
      : (typeof global.fetch === 'function' ? global.fetch.bind(global) : null);

    function configValue() {
      const config = typeof getConfig === 'function' ? getConfig() : null;
      return config || {};
    }

    function sessionValue() {
      return typeof getSession === 'function' ? getSession() : null;
    }

    function saveSessionValue(data) {
      if (typeof saveSession === 'function') return saveSession(data);
      return undefined;
    }

    function requestMessage(data, status) {
      if (typeof data === 'string' && data) return data;
      return data?.msg || data?.message || data?.error_description || `请求失败（${status}）`;
    }

    async function readTransportResult(result) {
      if (result === null || result === undefined) return { status: 200, data: null };
      if (typeof result === 'number') return { status: result, data: null };
      const status = typeof result.status === 'number' ? result.status : (result.ok === false ? 500 : 200);
      let data;
      if (Object.prototype.hasOwnProperty.call(result, 'data')) {
        data = result.data;
      } else if (typeof result.text === 'function') {
        const raw = await result.text();
        try {
          data = raw ? JSON.parse(raw) : null;
        } catch {
          data = raw;
        }
      } else if (typeof result.text === 'string') {
        const raw = result.text;
        try {
          data = raw ? JSON.parse(raw) : null;
        } catch {
          data = raw;
        }
      } else if (typeof result.json === 'function') {
        data = await result.json();
      } else if (Object.prototype.hasOwnProperty.call(result, 'body')) {
        data = result.body;
      } else {
        data = result;
      }
      return { status, data };
    }

    function errorFor(status, data) {
      const message = requestMessage(data, status);
      if (status === 0) return new ApiError('network', 0, message);
      if (status === 401) return new ApiError('auth', 401, message);
      if (status === 409) return new ApiError('conflict', 409, message);
      if (status === 410) return new ApiError('gone', 410, message);
      return new ApiError('http', status, message);
    }

    async function rawRequest(path, { method = 'GET', body, token, headers = {} } = {}) {
      if (!send) throw new ApiError('network', 0, '网络连接失败，请检查网络后重试。');
      const config = configValue();
      const requestHeaders = { apikey: config.key, Accept: 'application/json', ...headers };
      if (body !== undefined) requestHeaders['Content-Type'] = 'application/json';
      if (token) requestHeaders.Authorization = `Bearer ${token}`;
      const fullUrl = /^https?:\/\//.test(path)
        ? path
        : `${String(config.url || '').replace(/\/+$/, '')}${path}`;
      let response;
      try {
        response = await send(fullUrl, {
          method,
          headers: requestHeaders,
          body: body === undefined ? undefined : JSON.stringify(body),
        });
      } catch (error) {
        if (error instanceof ApiError) throw error;
        throw new ApiError('network', 0, error?.message || '网络连接失败，请检查网络后重试。');
      }
      const { status, data } = await readTransportResult(response);
      if (status < 200 || status >= 300) throw errorFor(status, data);
      return data;
    }

    async function refreshSession() {
      const session = sessionValue();
      if (!session?.refresh_token) {
        throw new ApiError('auth', 401, '登录已过期，请重新登录。');
      }
      const data = await rawRequest('/auth/v1/token?grant_type=refresh_token', {
        method: 'POST',
        body: { refresh_token: session.refresh_token },
      });
      await saveSessionValue(data);
      return data;
    }

    async function request(path, options = {}) {
      let session = sessionValue();
      if (!session) throw new ApiError('auth', 401, '请先登录。');
      const expiresAt = Number(session.expires_at);
      if (Number.isFinite(expiresAt) && Date.now() / 1000 > expiresAt - 60) {
        const refreshed = await refreshSession();
        session = sessionValue() || refreshed;
      }
      try {
        return await rawRequest(path, { ...options, token: session?.access_token });
      } catch (error) {
        if (error instanceof ApiError && error.code === 'auth') {
          const refreshed = await refreshSession();
          const latestSession = sessionValue() || refreshed;
          return rawRequest(path, { ...options, token: latestSession?.access_token });
        }
        throw error;
      }
    }

    async function fetchAll() {
      const rows = [];
      let offset = 0;
      while (true) {
        const page = await request(`/rest/v1/calendar_items?select=id,payload&order=id.asc&limit=1000&offset=${offset}`);
        if (!Array.isArray(page)) throw new ApiError('http', 200, '云端返回了无效的数据格式');
        rows.push(...page);
        if (page.length < 1000) break;
        offset += page.length;
      }
      let meta = null;
      const items = [];
      for (const row of rows) {
        if (!row || typeof row !== 'object') continue;
        if (row.payload?.type === 'meta') {
          if (meta === null) meta = row.payload;
        } else if (row.payload?.date) {
          items.push({ ...row.payload, id: row.id });
        }
      }
      return { meta, items };
    }

    function isConflict(error) {
      return error instanceof ApiError && error.code === 'conflict';
    }

    async function create(id, payload) {
      let finalId = id;
      try {
        await request('/rest/v1/calendar_items', {
          method: 'POST',
          body: { id: finalId, payload },
        });
      } catch (error) {
        if (!isConflict(error) || payload?.type === 'meta') throw error;
        finalId = makeUuid32();
        await request('/rest/v1/calendar_items', {
          method: 'POST',
          body: { id: finalId, payload },
        });
      }
      return { id: finalId, payload };
    }

    async function update(id, payload) {
      const latest = typeof getLatestPayload === 'function' ? getLatestPayload(id) : undefined;
      const effectivePayload = latest ?? payload;
      const data = await request(`/rest/v1/calendar_items?id=eq.${encodeURIComponent(id)}`, {
        method: 'PATCH',
        body: { payload: effectivePayload },
        headers: { Prefer: 'return=representation' },
      });
      if (Array.isArray(data) && data.length === 0) {
        throw new ApiError('gone', 200, '该事项已在其他设备删除');
      }
      return data;
    }

    async function remove(id) {
      return request(`/rest/v1/calendar_items?id=eq.${encodeURIComponent(id)}`, {
        method: 'DELETE',
      });
    }

    async function ensureMeta(fetchedMeta) {
      if (fetchedMeta) return fetchedMeta;
      const session = sessionValue() || {};
      const userId = session.user_id || tokenUserId(session.access_token || '');
      const id = metaId(userId);
      try {
        const created = await create(id, defaultMeta());
        return created.payload;
      } catch (error) {
        if (!isConflict(error)) throw error;
        const rows = await request(`/rest/v1/calendar_items?select=id,payload&id=eq.${encodeURIComponent(id)}`);
        const existing = Array.isArray(rows) ? rows.find((row) => row?.payload?.type === 'meta') : null;
        if (!existing) throw new ApiError('gone', 200, '设置行不存在');
        return existing.payload;
      }
    }

    function enqueueWrite(id, fn) {
      const key = String(id);
      const previous = writeQueues.get(key) || Promise.resolve();
      const run = previous.catch(() => undefined).then(() => fn());
      writeQueues.set(key, run);
      return run.finally(() => {
        if (writeQueues.get(key) === run) writeQueues.delete(key);
      });
    }

    return {
      rawRequest,
      refreshSession,
      request,
      fetchAll,
      create,
      update,
      remove,
      ensureMeta,
      enqueueWrite,
    };
  }

  const CBCore = {
    SUPPORTED_SCHEMA,
    localDate,
    parseDate,
    addDays,
    makeUuid32,
    tokenUserId,
    nowIso,
    isoToLocalDate,
    parseMinutes,
    formatMinutes,
    itemStart,
    itemEnd,
    buildTime,
    byTime,
    isMeta,
    isDone,
    isDropped,
    isArchived,
    isPending,
    dayItems,
    dayDots,
    weekAhead,
    archiveList,
    reviewTriggerMinutes,
    reviewList,
    applyDone,
    applyPostpone,
    applyDrop,
    applyRestore,
    nowNext,
    metaId,
    defaultMeta,
    termWeekLabel,
    ApiError,
    createApi,
  };

  global.CBCore = CBCore;
  if (typeof module !== 'undefined' && module.exports) module.exports = CBCore;
})(typeof globalThis !== 'undefined' ? globalThis : this);
