(function (root) {
  'use strict';

  const TYPE_TODO = '待办';
  const TYPE_SCHEDULE = '日程';
  const PREFIX_RE = /^(?:待办|todo)(?:[\s:：]+|$)/i;

  function pad2(value) {
    return String(value).padStart(2, '0');
  }

  function localIso(date) {
    return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`;
  }

  function addDays(date, days) {
    const next = new Date(date.getFullYear(), date.getMonth(), date.getDate());
    next.setDate(next.getDate() + days);
    return next;
  }

  function parse(rawInput, refDate = new Date(), chronoLib = root.chrono, typeOverride = null) {
    const raw = String(rawInput ?? '');
    let workingText = raw.trim();
    if (!workingText) return null;

    let type = TYPE_SCHEDULE;
    if (PREFIX_RE.test(workingText)) {
      type = TYPE_TODO;
      workingText = workingText.replace(PREFIX_RE, '').trim();
    }
    if (typeOverride === TYPE_SCHEDULE || typeOverride === TYPE_TODO) {
      type = typeOverride;
    }

    let date = localIso(refDate);
    let startTime = '';
    let endTime = '';
    let title = workingText;
    let results = [];

    const zh = chronoLib && chronoLib.zh;
    if (zh) {
      const parser = zh.hans || zh;
      if (parser && typeof parser.parse === 'function') {
        results = parser.parse(workingText, refDate, { forwardDate: true }) || [];
      }
    }

    if (results.length && results[0]) {
      const match = results[0];
      const start = match.start;
      if (start) {
        const year = start.get('year');
        const month = start.get('month');
        const day = start.get('day');
        if (year !== null && month !== null && day !== null) {
          date = `${year}-${pad2(month)}-${pad2(day)}`;
        }
        if (typeof start.isCertain === 'function' && start.isCertain('hour')) {
          const hour = start.get('hour');
          const minute = start.get('minute');
          if (hour !== null) {
            startTime = `${pad2(hour)}:${pad2(minute === null ? 0 : minute)}`;
          }
        }
      }
      const end = match.end;
      if (end && typeof end.isCertain === 'function' && end.isCertain('hour')) {
        const hour = end.get('hour');
        const minute = end.get('minute');
        if (hour !== null) {
          endTime = `${pad2(hour)}:${pad2(minute === null ? 0 : minute)}`;
        }
      }
      const index = Number(match.index);
      const textLength = Number(match.text ? match.text.length : 0);
      if (Number.isInteger(index) && index >= 0) {
        title = `${workingText.slice(0, index)}${workingText.slice(index + textLength)}`;
        title = title.replace(/\s+/g, ' ').trim();
        if (!title) title = workingText.trim();
      }
    }

    return { type, date, startTime, endTime, title, raw: rawInput };
  }

  function formatPreview(parsed, todayIso) {
    if (!parsed) return '';
    const today = todayIso || localIso(new Date());
    const parsedDate = new Date(`${parsed.date}T00:00:00`);
    const baseDate = new Date(`${today}T00:00:00`);
    let label = '';
    if (parsed.date === today) {
      label = '今天';
    } else if (!Number.isNaN(parsedDate.getTime()) && !Number.isNaN(baseDate.getTime())) {
      const dayDiff = Math.round((parsedDate.getTime() - baseDate.getTime()) / 86400000);
      if (dayDiff === 1) label = '明天';
      else if (dayDiff === 2) label = '后天';
    }
    if (!label) {
      const month = parsedDate.getMonth() + 1;
      const day = parsedDate.getDate();
      label = Number.isNaN(parsedDate.getTime()) ? String(parsed.date || '') : `${month}月${day}日`;
    }

    let timeLabel = '';
    if (parsed.startTime && parsed.endTime) {
      timeLabel = `${parsed.startTime}–${parsed.endTime}`;
    } else if (parsed.startTime) {
      timeLabel = parsed.startTime;
    }
    const when = timeLabel ? `${label} ${timeLabel}` : label;
    return `${when} · ${parsed.type} · ${parsed.title}`;
  }

  const api = { parse, formatPreview };
  root.CBQuickAdd = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
