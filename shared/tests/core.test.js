import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import '../../web/core.js';

const CBCore = globalThis.CBCore;
const testDirectory = path.dirname(fileURLToPath(import.meta.url));
const testcases = JSON.parse(
  fs.readFileSync(path.resolve(testDirectory, '..', 'rules-testcases.json'), 'utf8'),
);
const { today, dataset } = testcases;
const itemById = new Map(dataset.map((item) => [item.id, item]));
const ids = (items) => items.map((item) => item.id);

function harness(handler, extra = {}) {
  const state = {
    session: {
      access_token: 'old-token',
      refresh_token: 'refresh-1',
      expires_at: Math.floor(Date.now() / 1000) + 3600,
      user_id: testcases.api.userId,
    },
    savedSessions: [],
  };
  const calls = [];
  const api = CBCore.createApi({
    getConfig: () => ({ url: testcases.api.url, key: testcases.api.key }),
    getSession: () => state.session,
    saveSession: (data) => {
      state.savedSessions.push(data);
      state.session = {
        access_token: data.access_token,
        refresh_token: data.refresh_token || state.session.refresh_token,
        expires_at: data.expires_at || Math.floor(Date.now() / 1000) + (data.expires_in || 3600),
        user_id: data.user?.id || CBCore.tokenUserId(data.access_token),
      };
    },
    transport: async (url, options) => {
      const parsed = new URL(url);
      const call = {
        url,
        path: parsed.pathname,
        query: parsed.searchParams,
        method: options.method,
        headers: options.headers,
        body: options.body === undefined ? undefined : JSON.parse(options.body),
      };
      calls.push(call);
      return handler(call, state);
    },
    ...extra,
  });
  return { api, calls, state };
}

test('CBCore 通过 IIFE 暴露且基础常量正确', () => {
  assert.ok(CBCore);
  assert.equal(CBCore.SUPPORTED_SCHEMA, 2);
  assert.equal(typeof CBCore.createApi, 'function');
  assert.equal(typeof CBCore.ApiError, 'function');
});

test('本地日期、解析、加减天与 UUID', () => {
  const fixed = new Date(2026, 10, 24, 23, 59, 30);
  assert.equal(CBCore.localDate(fixed), '2026-11-24');
  const parsed = CBCore.parseDate('2026-11-24');
  assert.equal(parsed.getFullYear(), 2026);
  assert.equal(parsed.getMonth(), 10);
  assert.equal(parsed.getDate(), 24);
  assert.equal(CBCore.addDays('2026-12-31', 1), '2027-01-01');
  assert.equal(CBCore.addDays('2026-03-01', -1), '2026-02-28');
  const dateResult = CBCore.addDays(new Date(2026, 10, 24, 18, 3), 2);
  assert.equal(dateResult.getFullYear(), 2026);
  assert.equal(dateResult.getMonth(), 10);
  assert.equal(dateResult.getDate(), 26);
  assert.equal(dateResult.getHours(), 0);
  for (let i = 0; i < 20; i += 1) {
    assert.match(CBCore.makeUuid32(), /^[0-9a-f]{32}$/);
  }
});

test('JWT sub 解析与本地时区 ISO 时间', () => {
  const payload = Buffer.from(JSON.stringify({ sub: 'user-中文', role: 'authenticated' })).toString('base64url');
  const token = `header.${payload}.signature`;
  assert.equal(CBCore.tokenUserId(token), 'user-中文');
  assert.equal(CBCore.tokenUserId('not-a-jwt'), null);
  assert.equal(CBCore.tokenUserId(''), null);

  const fixed = new Date(2026, 10, 24, 18, 3, 0, 0);
  const rendered = CBCore.nowIso(fixed);
  assert.equal(rendered.slice(0, 19), '2026-11-24T18:03:00');
  assert.match(rendered, /[+-]\d{2}:\d{2}$/);
  assert.equal(new Date(rendered).getTime(), fixed.getTime());

  const timestamp = '2026-11-24T18:03:00+08:00';
  assert.equal(CBCore.isoToLocalDate(timestamp), CBCore.localDate(new Date(timestamp)));
});

test('时间解析校验小时、分钟和全角冒号', () => {
  for (const testcase of testcases.timeParsing) {
    assert.equal(
      CBCore.parseMinutes(testcase.input),
      testcase.expected,
      `parseMinutes(${JSON.stringify(testcase.input)})`,
    );
  }
});

test('分钟格式化与 buildTime', () => {
  for (const testcase of testcases.formatting) {
    assert.equal(CBCore.formatMinutes(testcase.input), testcase.expected);
  }
  assert.equal(CBCore.buildTime('9：05', '10:30'), '09:05-10:30');
  assert.equal(CBCore.buildTime('10:00', ''), '10:00');
  assert.equal(CBCore.buildTime('10:00', '9:00'), '10:00');
  assert.equal(CBCore.buildTime('25:00', '26:00'), '');
});

test('旧 time 字段与 itemStart/itemEnd 边界', () => {
  for (const testcase of testcases.legacyTime) {
    assert.equal(CBCore.itemStart(testcase.item), testcase.start, testcase.name);
    assert.equal(CBCore.itemEnd(testcase.item), testcase.end, testcase.name);
    const start = testcase.start === null ? '' : CBCore.formatMinutes(testcase.start);
    const end = testcase.end === null ? '' : CBCore.formatMinutes(testcase.end);
    assert.equal(CBCore.buildTime(start, end), testcase.build, testcase.name);
  }
  assert.equal(CBCore.itemStart(null), null);
  assert.equal(CBCore.itemEnd(null), null);
  assert.equal(CBCore.itemStart({ startTime: '9:00', time: '8:00-10:00' }), 540);
});

test('byTime 使用分钟、UTF-16 title 与 id 码元顺序', () => {
  for (const testcase of testcases.sorting) {
    const sorted = [...testcase.items].sort(CBCore.byTime);
    assert.deepEqual(ids(sorted), testcase.expectedIds, testcase.name);
  }
});

test('完成、放弃、归档与待处理状态判定只认精确字段', () => {
  for (const id of testcases.statusChecks.done) assert.equal(CBCore.isDone(itemById.get(id)), true, id);
  for (const id of testcases.statusChecks.dropped) assert.equal(CBCore.isDropped(itemById.get(id)), true, id);
  for (const id of testcases.statusChecks.pending) assert.equal(CBCore.isPending(itemById.get(id), today), true, id);
  for (const id of testcases.statusChecks.archived) assert.equal(CBCore.isArchived(itemById.get(id), today), true, id);
  for (const id of testcases.statusChecks.noArchive) assert.equal(CBCore.isArchived(itemById.get(id), today), false, id);
  assert.equal(CBCore.isMeta(itemById.get('meta-row')), true);
  assert.equal(CBCore.isArchived(itemById.get('meta-row'), today), false);
  assert.equal(CBCore.isDone({ type: '待办', done: 1 }), false);
  assert.equal(CBCore.isDone({ type: '日程', done: true }), false);
  assert.equal(CBCore.isDropped({ type: '待办', status: 'done' }), false);
  assert.equal(CBCore.isPending({ type: '待办', date: '2026-11-20', done: false }, today), true);
});

test('dayItems 主视图、归档统计、待处理与撤销窗口', () => {
  for (const testcase of testcases.dayItems) {
    const actual = CBCore.dayItems(
      dataset,
      testcase.iso,
      today,
      testcase.recentDoneIds,
    );
    const expected = testcase.expected;
    assert.equal(actual.past, expected.past, testcase.name);
    assert.deepEqual(ids(actual.courses), expected.courseIds, testcase.name);
    assert.deepEqual(ids(actual.schedules), expected.scheduleIds, testcase.name);
    assert.deepEqual(ids(actual.todos), expected.todoIds, testcase.name);
    assert.deepEqual(ids(actual.pending), expected.pendingIds, testcase.name);
    assert.equal(actual.archivedCount, expected.archivedCount, testcase.name);
  }

  const recentScenario = testcases.dayItems.find((testcase) => testcase.recentDoneIds.includes('done-today'));
  const asSet = CBCore.dayItems(dataset, today, today, new Set(recentScenario.recentDoneIds));
  assert.deepEqual(ids(asSet.todos), recentScenario.expected.todoIds);
  assert.equal(asSet.archivedCount, 0);

  const withOlderRecent = CBCore.dayItems(dataset, today, today, ['done-today', 'done-with-at']);
  assert.deepEqual(ids(withOlderRecent.pending), ['pending', 'done-with-at']);

  const includesMetaAndUndated = CBCore.dayItems(
    [...dataset, { id: 'undated', type: '待办', title: '无日期' }],
    today,
    today,
    [],
  );
  assert.equal(ids(includesMetaAndUndated.todos).includes('undated'), false);
  assert.equal(ids(includesMetaAndUndated.todos).includes('meta-row'), false);
});

test('dayDots 课程、待办与日程圆点规则', () => {
  for (const testcase of testcases.dayDots) {
    assert.deepEqual(
      CBCore.dayDots(dataset, testcase.iso, today),
      testcase.expected,
      testcase.iso,
    );
  }
});

test('weekAhead 固定返回未来七天', () => {
  const week = CBCore.weekAhead(dataset, today);
  assert.equal(week.length, 7);
  for (const expected of testcases.weekAhead) {
    const actual = week[expected.offset - 1];
    assert.equal(actual.iso, expected.iso);
    assert.equal(actual.iso, CBCore.addDays(today, expected.offset));
    assert.equal(actual.courseCount, expected.courseCount);
    assert.equal(actual.firstCourseStart, expected.firstCourseStart);
    assert.deepEqual(ids(actual.items), expected.itemIds);
  }
});

test('archiveList 状态、完成时间、分组和组内排序', () => {
  for (const testcase of testcases.archiveList) {
    const actual = CBCore.archiveList(dataset, today, testcase.filter).map((group) => ({
      iso: group.iso,
      entries: group.entries.map(({ item, state, at }) => ({ id: item.id, state, at })),
    }));
    assert.deepEqual(actual, testcase.groups, testcase.filter);
  }

  const sameTimestamp = [
    {
      id: 'late-title',
      date: '2026-11-20',
      type: '待办',
      title: 'B',
      done: true,
      completedAt: '2026-11-20T10:00:00+08:00',
    },
    {
      id: 'early-title',
      date: '2026-11-20',
      type: '待办',
      title: 'A',
      done: true,
      completedAt: '2026-11-20T10:00:00+08:00',
    },
  ];
  assert.deepEqual(
    CBCore.archiveList(sameTimestamp, today)[0].entries.map((entry) => entry.item.id),
    ['early-title', 'late-title'],
  );
});

test('reviewTriggerMinutes 与 reviewList 的 18:00 边界', () => {
  for (const testcase of testcases.reviewTriggerMinutes) {
    const items = testcase.items === 'dataset' ? dataset : testcase.items;
    assert.equal(CBCore.reviewTriggerMinutes(items, today), testcase.expected, testcase.name);
  }
  for (const testcase of testcases.reviewList) {
    assert.deepEqual(
      ids(CBCore.reviewList(dataset, today, testcase.nowMinutes)),
      testcase.expectedIds,
    );
  }
  assert.deepEqual(ids(CBCore.reviewList(dataset, today)), ['pending']);
});

test('完成、顺延、放弃、恢复均为不改原对象的浅拷贝', () => {
  const original = itemById.get('todo-today');
  const done = CBCore.applyDone(original, true, '2026-11-24T20:00:00+08:00');
  assert.equal(original.done, undefined);
  assert.equal(done.id, undefined);
  assert.equal(done._row_id, undefined);
  assert.equal(done.done, true);
  assert.equal(done.completedAt, '2026-11-24T20:00:00+08:00');

  const reopened = CBCore.applyDone({ ...original, done: true, completedAt: 'x' }, false);
  assert.equal(reopened.done, false);
  assert.equal('completedAt' in reopened, false);

  const postponeCase = testcases.postpone;
  let postponed = postponeCase.initial;
  for (const target of postponeCase.targets) {
    postponed = CBCore.applyPostpone(postponed, target);
  }
  assert.equal(postponed.date, postponeCase.expected.date);
  assert.equal(postponed.originalDate, postponeCase.expected.originalDate);
  assert.equal(postponed.postponeCount, postponeCase.expected.postponeCount);
  assert.equal(postponeCase.initial.date, '2026-11-24');
  assert.equal('originalDate' in postponeCase.initial, false);

  const dropped = CBCore.applyDrop(original, '2026-11-24T21:00:00+08:00');
  assert.equal(dropped.status, 'dropped');
  assert.equal(dropped.droppedAt, '2026-11-24T21:00:00+08:00');
  assert.equal(dropped.id, undefined);
  assert.equal(original.status, undefined);

  const restored = CBCore.applyRestore({ ...original, done: true, completedAt: 'x', status: 'dropped', droppedAt: 'y' });
  assert.equal('status' in restored, false);
  assert.equal('droppedAt' in restored, false);
  assert.equal(restored.done, false);
  assert.equal('completedAt' in restored, false);
});

test('nowNext 当前课、下一节课与空闲截止', () => {
  for (const testcase of testcases.nowNext) {
    const actual = CBCore.nowNext(dataset, today, testcase.nowMinutes);
    assert.deepEqual(actual.current, testcase.expectedCurrent, String(testcase.nowMinutes));
    assert.deepEqual(actual.next, testcase.expectedNext, String(testcase.nowMinutes));
    assert.equal(actual.freeUntil, testcase.freeUntil);
  }
  assert.deepEqual(CBCore.nowNext([], today, 600), { current: null, next: null, freeUntil: null });
});

test('meta 标识、默认设置与学期周次', () => {
  assert.equal(CBCore.metaId(testcases.api.userId), testcases.api.metaId);
  assert.equal(CBCore.metaId(''), '');
  assert.deepEqual(CBCore.defaultMeta(), testcases.meta);
  for (const testcase of testcases.termWeek) {
    assert.equal(
      CBCore.termWeekLabel(testcases.meta, testcase.date),
      testcase.expected,
      testcase.date,
    );
  }
  assert.equal(CBCore.termWeekLabel({ ...testcases.meta, termWeeks: 0 }, '2026-08-31'), '第1周');
  assert.equal(CBCore.termWeekLabel(testcases.meta, new Date(2026, 8, 7)), '第2周');
});

test('fetchAll 分页拉取并分离 meta 与普通事项', async () => {
  const firstPage = [
    { id: 'meta-row', payload: testcases.meta },
    ...Array.from({ length: 999 }, (_, index) => ({
      id: `row-${String(index).padStart(4, '0')}`,
      payload: {
        id: `row-${String(index).padStart(4, '0')}`,
        date: '2026-11-24',
        type: '日程',
        title: `事项${index}`,
      },
    })),
  ];
  const secondPage = [
    {
      id: 'row-1000',
      payload: { id: 'row-1000', date: '2026-11-25', type: '待办', title: '第二页一' },
    },
    {
      id: 'row-1001',
      payload: { id: 'row-1001', date: '2026-11-26', type: '日程', title: '第二页二' },
    },
  ];
  const { api, calls } = harness((call) => {
    const offset = call.query.get('offset');
    assert.equal(call.query.get('select'), 'id,payload');
    assert.equal(call.query.get('order'), 'id.asc');
    assert.equal(call.query.get('limit'), '1000');
    return { status: 200, data: offset === '0' ? firstPage : secondPage };
  });

  const result = await api.fetchAll();
  assert.equal(calls.length, 2);
  assert.equal(calls[0].query.get('offset'), '0');
  assert.equal(calls[1].query.get('offset'), '1000');
  assert.deepEqual(result.meta, testcases.meta);
  assert.equal(result.items.length, 1001);
  assert.deepEqual(result.items[0].id, 'row-0000');
  assert.deepEqual(result.items.at(-1).id, 'row-1001');
  assert.equal(result.items.some((item) => item.type === 'meta'), false);
});

test('create 遇普通事项 409 换随机 id 且只重试一次', async () => {
  const attempts = [];
  const { api, calls } = harness((call) => {
    assert.equal(call.method, 'POST');
    assert.equal(call.path, '/rest/v1/calendar_items');
    attempts.push(call.body.id);
    if (attempts.length === 1) return { status: 409, data: { message: 'duplicate key' } };
    return { status: 201, data: null };
  });
  const payload = { type: '待办', date: '2026-11-24', title: '新建' };
  const created = await api.create('fixed-id', payload);
  assert.equal(calls.length, 2);
  assert.equal(attempts[0], 'fixed-id');
  assert.match(attempts[1], /^[0-9a-f]{32}$/);
  assert.notEqual(attempts[1], 'fixed-id');
  assert.equal(created.id, attempts[1]);
  assert.equal(created.payload, payload);
  assert.deepEqual(calls[0].body.payload, payload);
});

test('create 连续 409 会抛出 conflict 而不无限重试', async () => {
  const { api, calls } = harness(() => ({ status: 409, data: { message: 'duplicate' } }));
  await assert.rejects(
    api.create('fixed-id', { type: '日程', date: '2026-11-24' }),
    (error) => error instanceof CBCore.ApiError && error.code === 'conflict' && error.status === 409,
  );
  assert.equal(calls.length, 2);
});

test('ensureMeta 的 meta 409 不换 id，改为 GET 读取云端设置', async () => {
  const { api, calls } = harness((call) => {
    if (call.method === 'POST') {
      assert.equal(call.path, '/rest/v1/calendar_items');
      assert.equal(call.body.id, testcases.api.metaId);
      assert.deepEqual(call.body.payload, CBCore.defaultMeta());
      return { status: 409, data: { message: 'duplicate key' } };
    }
    assert.equal(call.method, 'GET');
    assert.equal(call.path, '/rest/v1/calendar_items');
    assert.equal(call.query.get('select'), 'id,payload');
    assert.equal(call.query.get('id'), `eq.${testcases.api.metaId}`);
    return {
      status: 200,
      data: [{ id: testcases.api.metaId, payload: testcases.api.existingMeta }],
    };
  });

  const meta = await api.ensureMeta(null);
  assert.deepEqual(meta, testcases.api.existingMeta);
  assert.equal(calls.length, 2);
  assert.deepEqual(calls.map((call) => call.method), ['POST', 'GET']);
  assert.equal(calls[0].body.id, testcases.api.metaId);
});

test('ensureMeta 已有设置时不发请求', async () => {
  const { api, calls } = harness(() => {
    throw new Error('不应发请求');
  });
  const meta = await api.ensureMeta(testcases.meta);
  assert.equal(meta, testcases.meta);
  assert.equal(calls.length, 0);
});

test('update 带 Prefer、使用最新 payload，空数组抛 gone', async () => {
  const { api, calls } = harness(
    (call) => {
      assert.equal(call.method, 'PATCH');
      assert.equal(call.headers.Prefer, 'return=representation');
      assert.equal(call.query.get('id'), 'eq.row/1');
      return { status: 200, data: [] };
    },
    {
      getLatestPayload: (id) => (id === 'row/1' ? { type: '待办', title: '最新标题' } : undefined),
    },
  );

  await assert.rejects(
    api.update('row/1', { type: '待办', title: '旧标题' }),
    (error) => {
      assert.ok(error instanceof CBCore.ApiError);
      assert.equal(error.code, 'gone');
      assert.equal(error.status, 200);
      assert.match(error.message, /其他设备删除/);
      return true;
    },
  );
  assert.equal(calls.length, 1);
  assert.deepEqual(calls[0].body.payload, { type: '待办', title: '最新标题' });
});

test('remove 使用编码后的行 id 发起 DELETE', async () => {
  const { api, calls } = harness((call) => {
    assert.equal(call.method, 'DELETE');
    assert.equal(call.path, '/rest/v1/calendar_items');
    assert.equal(call.query.get('id'), 'eq.row a/b');
    return { status: 204, data: null };
  });
  await api.remove('row a/b');
  assert.equal(calls.length, 1);
});

test('401 触发 refreshSession 后使用新 token 重试一次', async () => {
  const { api, calls, state } = harness((call) => {
    if (call.path === '/auth/v1/token') {
      assert.equal(call.method, 'POST');
      assert.deepEqual(call.body, { refresh_token: 'refresh-1' });
      return {
        status: 200,
        data: { access_token: 'new-token', refresh_token: 'refresh-2', expires_in: 3600, user: { id: 'user-1' } },
      };
    }
    assert.equal(call.path, '/rest/v1/calendar_items');
    if (call.headers.Authorization === 'Bearer old-token') {
      return { status: 401, data: { message: 'JWT expired' } };
    }
    assert.equal(call.headers.Authorization, 'Bearer new-token');
    return { status: 200, data: [{ id: 'row-1' }] };
  });

  const result = await api.request('/rest/v1/calendar_items?id=eq.row-1', { method: 'GET' });
  assert.deepEqual(result, [{ id: 'row-1' }]);
  assert.equal(calls.length, 3);
  assert.equal(calls[0].headers.Authorization, 'Bearer old-token');
  assert.equal(calls[1].path, '/auth/v1/token');
  assert.equal(calls[2].headers.Authorization, 'Bearer new-token');
  assert.equal(state.savedSessions.length, 1);
  assert.equal(state.session.access_token, 'new-token');
  assert.equal(state.session.user_id, 'user-1');
});

test('同一行 id 的写队列串行，不同行互不阻塞且失败不阻断后续', async () => {
  const { api } = harness(() => ({ status: 200, data: null }));
  const events = [];
  let releaseFirst;
  const firstGate = new Promise((resolve) => { releaseFirst = resolve; });

  const first = api.enqueueWrite('row-a', async () => {
    events.push('a1-start');
    await firstGate;
    events.push('a1-end');
  });
  const second = api.enqueueWrite('row-a', async () => {
    events.push('a2');
  });
  const otherRow = api.enqueueWrite('row-b', async () => {
    events.push('b1');
  });

  await Promise.resolve();
  await Promise.resolve();
  assert.deepEqual(events, ['a1-start', 'b1']);
  releaseFirst();
  await Promise.all([first, second, otherRow]);
  assert.deepEqual(events, ['a1-start', 'b1', 'a1-end', 'a2']);

  await assert.rejects(
    api.enqueueWrite('row-c', async () => { throw new Error('write failed'); }),
    /write failed/,
  );
  await api.enqueueWrite('row-c', async () => {
    events.push('c2');
  });
  assert.equal(events.at(-1), 'c2');
});
