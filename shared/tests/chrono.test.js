import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const TEST_DIR = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(TEST_DIR, '../..');
const CHRONO_PATH = path.join(ROOT, 'macos-widget/ui/vendor/chrono.min.js');
const PARSER_PATH = path.join(ROOT, 'macos-widget/ui/quickadd-parser.js');

const context = vm.createContext({ console });
vm.runInContext(fs.readFileSync(CHRONO_PATH, 'utf8'), context, { filename: CHRONO_PATH });
vm.runInContext(fs.readFileSync(PARSER_PATH, 'utf8'), context, { filename: PARSER_PATH });

const CBQuickAdd = context.CBQuickAdd;
// Build the reference date inside the same VM realm; chrono checks Date identity
// and would otherwise silently fall back to the current system clock.
const REF_DATE = vm.runInContext('new Date(2026, 10, 24, 10, 0, 0)', context);

function parse7(raw, typeOverride = null) {
  return CBQuickAdd.parse(raw, REF_DATE, context.chrono, typeOverride);
}

function assertParsed(raw, expected, typeOverride = null) {
  const parsed = parse7(raw, typeOverride);
  assert.ok(parsed, `expected a parse result for: ${raw}`);
  assert.equal(parsed.date, expected.date, `${raw} date`);
  assert.equal(parsed.startTime, expected.startTime, `${raw} startTime`);
  assert.equal(parsed.endTime, expected.endTime, `${raw} endTime`);
  assert.equal(parsed.type, expected.type, `${raw} type`);
  assert.equal(parsed.title, expected.title, `${raw} title`);
  return parsed;
}

test('P4.7 必过例句 1：明天下午3点 交实验报告', () => {
  assertParsed('明天下午3点 交实验报告', {
    date: '2026-11-25',
    startTime: '15:00',
    endTime: '',
    type: '日程',
    title: '交实验报告',
  });
});

test('P4.7 必过例句 2：后天 9:30 组会', () => {
  assertParsed('后天 9:30 组会', {
    date: '2026-11-26',
    startTime: '09:30',
    endTime: '',
    type: '日程',
    title: '组会',
  });
});

test('P4.7 必过例句 3：周五晚上8点 羽毛球', () => {
  assertParsed('周五晚上8点 羽毛球', {
    date: '2026-11-27',
    startTime: '20:00',
    endTime: '',
    type: '日程',
    title: '羽毛球',
  });
});

test('P4.7 必过例句 4：下周一 交作业', () => {
  assertParsed('下周一 交作业', {
    date: '2026-11-30',
    startTime: '',
    endTime: '',
    type: '日程',
    title: '交作业',
  });
});

test('P4.7 必过例句 5：12月3日 14:00-16:00 期中考试', () => {
  assertParsed('12月3日 14:00-16:00 期中考试', {
    date: '2026-12-03',
    startTime: '14:00',
    endTime: '16:00',
    type: '日程',
    title: '期中考试',
  });
});

test('P4.7 必过例句 6：买牛奶', () => {
  assertParsed('买牛奶', {
    date: '2026-11-24',
    startTime: '',
    endTime: '',
    type: '日程',
    title: '买牛奶',
  });
});

test('P4.7 必过例句 7：待办 明天 取快递', () => {
  assertParsed('待办 明天 取快递', {
    date: '2026-11-25',
    startTime: '',
    endTime: '',
    type: '待办',
    title: '取快递',
  });
});

test('todo 前缀：todo 后天 交材料', () => {
  assertParsed('todo 后天 交材料', {
    date: '2026-11-26',
    startTime: '',
    endTime: '',
    type: '待办',
    title: '交材料',
  });
});

test('typeOverride 覆盖前缀识别的类别', () => {
  assertParsed('明天下午3点 交实验报告', {
    date: '2026-11-25',
    startTime: '15:00',
    endTime: '',
    type: '待办',
    title: '交实验报告',
  }, '待办');
  assertParsed('待办 明天 取快递', {
    date: '2026-11-25',
    startTime: '',
    endTime: '',
    type: '日程',
    title: '取快递',
  }, '日程');
});

test('空白输入返回 null', () => {
  assert.equal(parse7('   '), null);
});

test('formatPreview 生成实时预览文案', () => {
  assert.equal(
    CBQuickAdd.formatPreview(parse7('明天下午3点 交实验报告'), '2026-11-24'),
    '明天 15:00 · 日程 · 交实验报告',
  );
  assert.equal(
    CBQuickAdd.formatPreview(parse7('12月3日 14:00-16:00 期中考试'), '2026-11-24'),
    '12月3日 14:00–16:00 · 日程 · 期中考试',
  );
  assert.equal(
    CBQuickAdd.formatPreview(parse7('待办 买牛奶'), '2026-11-24'),
    '今天 · 待办 · 买牛奶',
  );
});
