// 可输入的时间下拉框（CalendarBoard macOS 课程岛）
//
// 把 `<input type="text">` 增强成“输入框 + 15 分钟步进的下拉列表”：
//   · 聚焦时在下方弹出列表（弹层挂在 #entryBackdrop 里，避免被 .modal-card 的 overflow 裁掉）
//   · 支持直接输入 1530 / 15:30 / 15.30 / 3pm / 下午三点半，失焦或回车时解析
//   · ↑↓ 在列表里移动、回车选中、Esc 关闭；列表收起时 ↑↓ 直接 ±15 分钟
//   · 值始终是 HH:MM 字符串，提交后派发 input/change 事件，交给 widget.js 处理联动
(function (global) {
  'use strict';

  const STEP = 15;
  const LIST_START = 6 * 60; // 06:00
  const LIST_END = 23 * 60 + 45; // 23:45
  const LAST_MINUTE = 23 * 60 + 59;

  const instances = [];

  const pad = (value) => String(value).padStart(2, '0');

  function format(minutes) {
    if (!Number.isInteger(minutes) || minutes < 0 || minutes > LAST_MINUTE) return '';
    return `${pad(Math.floor(minutes / 60))}:${pad(minutes % 60)}`;
  }

  function inRange(hour, minute) {
    if (!Number.isInteger(hour) || !Number.isInteger(minute)) return null;
    if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return null;
    return hour * 60 + minute;
  }

  const COMPACT_RE = /^(\d{3,4})$/;
  const WRITTEN_RE = /^(\d{1,2})\s*[:：.]\s*(\d{1,2})$/;
  const AMPM_RE = /^(\d{1,2})(?:\s*[:：.]\s*(\d{2}))?\s*(am|pm)$/i;

  // 解析时间输入。empty: 输入为空；minutes: null 表示无法识别。
  function parseTimeText(raw, refDate) {
    const text = String(raw == null ? '' : raw).trim();
    if (!text) return { empty: true, minutes: null };

    let match = COMPACT_RE.exec(text);
    if (match) {
      const digits = match[1];
      return {
        empty: false,
        minutes: inRange(Number(digits.slice(0, -2)), Number(digits.slice(-2))),
      };
    }

    match = WRITTEN_RE.exec(text);
    if (match) return { empty: false, minutes: inRange(Number(match[1]), Number(match[2])) };

    match = AMPM_RE.exec(text);
    if (match) {
      let hour = Number(match[1]);
      const minute = match[2] ? Number(match[2]) : 0;
      const isPM = match[3].toLowerCase() === 'pm';
      if (hour === 12) hour = isPM ? 12 : 0;
      else if (isPM) hour += 12;
      return { empty: false, minutes: inRange(hour, minute) };
    }

    const chrono = global.chrono;
    if (chrono && chrono.zh && chrono.zh.hans && typeof chrono.zh.hans.parse === 'function') {
      try {
        const results = chrono.zh.hans.parse(text, refDate || new Date(), { forwardDate: true });
        const result = results && results[0];
        if (result && result.start && result.start.isCertain('hour')) {
          const date = result.start.date();
          return { empty: false, minutes: inRange(date.getHours(), date.getMinutes()) };
        }
      } catch (error) {
        void error;
      }
    }
    return { empty: false, minutes: null };
  }

  function durationLabel(minutes) {
    if (!Number.isInteger(minutes) || minutes <= 0) return '';
    const hours = Math.floor(minutes / 60);
    const rest = minutes % 60;
    if (hours && rest) return `${hours} 小时 ${rest} 分`;
    if (hours) return `${hours} 小时`;
    return `${rest} 分钟`;
  }

  function createField({ input, backdrop, kind, getStartMinutes }) {
    const popup = document.createElement('div');
    popup.className = 'time-popup hidden';
    backdrop.appendChild(popup);

    let values = [];
    let activeIndex = -1;
    let opened = false;
    let dirty = false; // 用户手动改过输入框里的文字
    let lastCommitted = input.value;
    let invalidTimer = null;

    const isEndField = kind === 'end';

    function rawStartMinutes() {
      if (typeof getStartMinutes !== 'function') return null;
      const value = getStartMinutes();
      return Number.isInteger(value) ? value : null;
    }

    function buildValues() {
      const list = [];
      if (isEndField) {
        const start = rawStartMinutes();
        const base = start === null ? LIST_START : Math.max(start, LIST_START);
        for (let minutes = Math.ceil(base / STEP) * STEP; minutes <= LIST_END; minutes += STEP) {
          list.push(minutes);
        }
        return list;
      }
      for (let minutes = LIST_START; minutes <= LIST_END; minutes += STEP) list.push(minutes);
      return list;
    }

    function render() {
      values = buildValues();
      popup.textContent = '';
      const base = isEndField ? rawStartMinutes() : null;
      values.forEach((minutes, index) => {
        const row = document.createElement('button');
        row.type = 'button';
        row.className = 'time-option';
        row.dataset.minutes = String(minutes);

        const label = document.createElement('span');
        label.className = 'time-option-label';
        label.textContent = format(minutes);
        row.appendChild(label);

        if (base !== null) {
          const hint = document.createElement('span');
          hint.className = 'time-option-hint';
          hint.textContent = durationLabel(minutes - base);
          row.appendChild(hint);
        }

        row.addEventListener('mousedown', (event) => event.preventDefault());
        row.addEventListener('click', () => choose(index));
        popup.appendChild(row);
      });
    }

    function setActive(index, scroll) {
      const rows = Array.from(popup.children);
      if (!rows.length) return;
      activeIndex = Math.max(0, Math.min(index, rows.length - 1));
      rows.forEach((row, i) => row.classList.toggle('active', i === activeIndex));
      if (scroll === false) return;
      const row = rows[activeIndex];
      if (!row) return;
      const target = row.offsetTop - popup.clientHeight / 2 + row.offsetHeight / 2;
      popup.scrollTop = Math.max(0, target);
    }

    function position() {
      if (!opened) return;
      const rect = input.getBoundingClientRect();
      const popupHeight = Math.min(222, popup.scrollHeight || 222);
      const below = window.innerHeight - rect.bottom;
      const openUp = below < popupHeight + 16 && rect.top > popupHeight + 16;
      popup.style.width = `${Math.max(rect.width, 158)}px`;
      popup.style.left = `${Math.max(8, rect.left)}px`;
      popup.style.top = openUp
        ? `${Math.max(8, rect.top - popupHeight - 4)}px`
        : `${rect.bottom + 4}px`;
    }

    function emitChange() {
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
    }

    function flashInvalid() {
      input.classList.add('time-invalid');
      if (invalidTimer) clearTimeout(invalidTimer);
      invalidTimer = setTimeout(() => {
        invalidTimer = null;
        input.classList.remove('time-invalid');
      }, 900);
    }

    function commit(minutes) {
      input.value = format(minutes);
      lastCommitted = input.value;
      input.classList.remove('time-invalid');
      emitChange();
      dirty = false;
    }

    function commitFromText() {
      const parsed = parseTimeText(input.value);
      if (parsed.empty) {
        input.value = '';
        lastCommitted = '';
        input.classList.remove('time-invalid');
        emitChange();
        dirty = false;
        return;
      }
      if (parsed.minutes === null) {
        input.value = lastCommitted;
        flashInvalid();
        return;
      }
      commit(parsed.minutes);
    }

    function step(delta) {
      const parsed = parseTimeText(input.value);
      let base = parsed.minutes;
      if (!Number.isInteger(base)) base = isEndField ? rawStartMinutes() : null;
      if (!Number.isInteger(base)) base = LIST_START;
      commit(Math.min(LAST_MINUTE, Math.max(0, base + delta)));
    }

    function open() {
      render();
      if (!values.length) return;
      opened = true;
      dirty = false;
      popup.classList.remove('hidden');
      position();
      const current = parseTimeText(input.value);
      let index = 0;
      if (Number.isInteger(current.minutes)) {
        values.forEach((minutes, i) => {
          if (minutes <= current.minutes) index = i;
        });
      }
      setActive(index);
    }

    function close() {
      opened = false;
      activeIndex = -1;
      popup.classList.add('hidden');
    }

    function choose(index) {
      const minutes = values[index];
      if (!Number.isInteger(minutes)) return;
      commit(minutes);
      close();
    }

    input.addEventListener('focus', () => {
      lastCommitted = input.value;
      open();
    });
    input.addEventListener('click', () => {
      if (!opened) open();
    });
    input.addEventListener('input', () => {
      dirty = true;
      if (!opened) return;
      const parsed = parseTimeText(input.value);
      if (!Number.isInteger(parsed.minutes)) return;
      const index = values.indexOf(parsed.minutes);
      if (index >= 0) setActive(index, false);
    });
    input.addEventListener('keydown', (event) => {
      if (event.isComposing || event.keyCode === 229) return;
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        event.preventDefault();
        const down = event.key === 'ArrowDown';
        if (opened) setActive(activeIndex + (down ? 1 : -1));
        else step(down ? STEP : -STEP);
        return;
      }
      if (event.key === 'Enter') {
        event.preventDefault();
        // 用户自己打了字就以输入框内容为准，否则回车选中列表当前项
        if (dirty) {
          close();
          commitFromText();
        } else if (opened && activeIndex >= 0) {
          choose(activeIndex);
        } else {
          close();
          commitFromText();
        }
        return;
      }
      if (event.key === 'Escape') {
        if (!opened) return;
        event.preventDefault();
        event.stopPropagation();
        close();
        return;
      }
      if (event.key === 'Tab') close();
    });
    input.addEventListener('blur', () => {
      setTimeout(() => {
        if (document.activeElement === input) return;
        close();
        if (input.value !== lastCommitted) commitFromText();
      }, 120);
    });

    window.addEventListener('resize', position);
    const scrollHost = backdrop.querySelector('.modal-card');
    if (scrollHost) scrollHost.addEventListener('scroll', position, { passive: true });

    return { open, close };
  }

  function attach({ start, end, backdrop }) {
    if (!start || !end || !backdrop) return null;
    const startField = createField({
      input: start,
      backdrop,
      kind: 'start',
      getStartMinutes: null,
    });
    const endField = createField({
      input: end,
      backdrop,
      kind: 'end',
      getStartMinutes: () => {
        const parsed = parseTimeText(start.value);
        return Number.isInteger(parsed.minutes) ? parsed.minutes : null;
      },
    });
    instances.push(startField, endField);
    return { start: startField, end: endField };
  }

  function closeAll() {
    instances.forEach((instance) => instance.close());
  }

  global.CBTimeCombo = {
    STEP,
    format,
    durationLabel,
    parseTimeText,
    attach,
    closeAll,
  };
})(typeof globalThis !== 'undefined' ? globalThis : this);
