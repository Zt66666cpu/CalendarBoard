#!/usr/bin/env python3
"""
Convert UESTC 2026-2027 Fall weekly timetable CSV into CalendarBoard JSON + ICS,
and optionally inject directly into the macOS CalendarBoard widget local store.
"""
import csv
import datetime
import hashlib
import json
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CSV_PATH = os.path.join(ROOT, "data", "uestc-2026-fall-raw.csv")
JSON_OUT = os.path.join(ROOT, "data", "timetable-2026-fall.json")
ICS_OUT = os.path.join(ROOT, "data", "timetable-2026-fall.ics")

# UESTC 2026-2027 Fall: Week 1 Monday = 2026-08-31 (2026-11-20 Fri = Week 12 Fri)
WEEK1_MONDAY = datetime.date(2026, 8, 31)

WEEKDAY_OFFSET = {
    "周一": 0,
    "周二": 1,
    "周三": 2,
    "周四": 3,
    "周五": 4,
    "周六": 5,
    "周日": 6,
}

# UESTC official class times
PERIOD_TIMES = {
    "第1–2节": ("08:30", "10:05"),
    "第3–4节": ("10:20", "11:55"),
    "第5–6节": ("14:30", "16:05"),
    "第7–8节": ("16:20", "17:55"),
    "第9节": ("19:30", "20:15"),
    "第9–10节": ("19:30", "21:05"),
    "第9–11节": ("19:30", "21:55"),
    "第12节": ("22:00", "22:45"),
}


def deterministic_id(key: str) -> str:
    return hashlib.md5(key.encode("utf-8")).hexdigest()


def load_entries():
    raw_rows = []
    with open(CSV_PATH, "r", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            week_str = (row.get("教学周") or "").strip()
            weekday = (row.get("星期") or "").strip()
            period = (row.get("节次") or "").strip()
            title = (row.get("课程名称") or "").strip()
            if not week_str or not weekday or not title:
                continue
            raw_rows.append({
                "week": int(week_str),
                "weekday": weekday,
                "period": period,
                "title": title,
                "teacher": (row.get("教师") or "").strip(),
                "code": (row.get("课程序号") or "").strip(),
                "place": (row.get("教学地点") or "").strip(),
                "note": (row.get("备注") or "").strip(),
            })

    # Merge Week 16 Wed 9-11 + 12 (starts 19:00, 4 consecutive sessions)
    merged_rows = []
    skip_next = False
    for i, r in enumerate(raw_rows):
        if skip_next:
            skip_next = False
            continue
        if (
            r["week"] == 16
            and r["weekday"] == "周三"
            and r["period"] == "第9–11节"
            and i + 1 < len(raw_rows)
            and raw_rows[i + 1]["week"] == 16
            and raw_rows[i + 1]["weekday"] == "周三"
            and raw_rows[i + 1]["period"] == "第12节"
            and raw_rows[i + 1]["code"] == r["code"]
        ):
            combined = dict(r)
            combined["period"] = "第9–12节"
            combined["title"] = f"{r['title']}（4节连上）"
            combined["custom_time"] = ("19:00", "22:15")
            combined["note"] = raw_rows[i + 1]["note"]
            merged_rows.append(combined)
            skip_next = True
        else:
            merged_rows.append(r)

    items = []
    for r in merged_rows:
        date = WEEK1_MONDAY + datetime.timedelta(
            days=(r["week"] - 1) * 7 + WEEKDAY_OFFSET[r["weekday"]]
        )
        start_time, end_time = r.get("custom_time") or PERIOD_TIMES[r["period"]]
        iso = date.isoformat()
        uid = deterministic_id(f"uestc-2026-fall|{iso}|{start_time}|{r['code']}|{r['title']}")
        item = {
            "id": uid,
            "date": iso,
            "type": "课程",
            "title": r["title"],
            "startTime": start_time,
            "endTime": end_time,
            "time": f"{start_time}-{end_time}",
            "place": r["place"],
            "room": r["place"],
            "teacher": r["teacher"],
            "courseCode": r["code"],
            "week": r["week"],
            "source": "school-timetable-2026-fall",
            "done": False,
        }
        items.append(item)

    items.sort(key=lambda x: (x["date"], x["startTime"], x["title"]))
    return items


def write_ics(items):
    lines = [
        "BEGIN:VCALENDAR",
        "VERSION:2.0",
        "PRODID:-//CalendarBoard//UESTC 2026-2027 Fall//ZH",
        "CALSCALE:GREGORIAN",
        "X-WR-CALNAME:电子科大 2026秋课表",
        "X-WR-TIMEZONE:Asia/Shanghai",
    ]
    for it in items:
        d = it["date"].replace("-", "")
        st = it["startTime"].replace(":", "") + "00"
        et = it["endTime"].replace(":", "") + "00"
        desc_parts = [f"第{it['week']}周"]
        if it.get("teacher"):
            desc_parts.append(f"教师：{it['teacher']}")
        if it.get("courseCode"):
            desc_parts.append(f"课程序号：{it['courseCode']}")
        lines.extend([
            "BEGIN:VEVENT",
            f"UID:{it['id']}@calendarboard",
            f"DTSTART;TZID=Asia/Shanghai:{d}T{st}",
            f"DTEND;TZID=Asia/Shanghai:{d}T{et}",
            f"SUMMARY:{it['title']}",
            f"LOCATION:{it['place']}",
            f"DESCRIPTION:{' | '.join(desc_parts)}",
            "END:VEVENT",
        ])
    lines.append("END:VCALENDAR")
    with open(ICS_OUT, "w", encoding="utf-8") as f:
        f.write("\r\n".join(lines) + "\r\n")


def inject_into_macos_defaults(items):
    # Preserve any user-created non-timetable items (todos/schedules/manual courses) already stored locally
    existing = []
    try:
        out = subprocess.check_output(
            ["defaults", "read", "com.calendarboard.macos", "cb_store_demoItems"],
            stderr=subprocess.DEVNULL,
            text=True,
        ).strip()
        parsed = json.loads(out)
        if isinstance(parsed, list):
            for it in parsed:
                # Drop initial synthetic demo ids & old timetable items; keep user's own added items
                it_id = str(it.get("id", ""))
                if it.get("source") == "school-timetable-2026-fall":
                    continue
                if it_id.startswith(("111111111111111111111111111111", "222222222222222222222222222222", "333333333333333333333333333333")):
                    continue
                existing.append(it)
    except Exception:
        pass

    combined = items + existing
    combined.sort(key=lambda x: (x.get("date", ""), x.get("startTime", ""), x.get("title", "")))

    payload_str = json.dumps(combined, ensure_ascii=False)
    subprocess.check_call([
        "defaults", "write", "com.calendarboard.macos", "cb_store_demoItems", "-string", payload_str
    ])
    subprocess.check_call([
        "defaults", "write", "com.calendarboard.macos", "cb_store_mode", "-string", "local"
    ])
    return len(combined)


def main():
    items = load_entries()
    with open(JSON_OUT, "w", encoding="utf-8") as f:
        json.dump(items, f, ensure_ascii=False, indent=2)
    write_ics(items)
    print(f"Generated {len(items)} course sessions -> {JSON_OUT}")
    print(f"Generated ICS file -> {ICS_OUT}")

    # Show Week 12 & Week 16 Wed verification
    w12 = [it for it in items if it["week"] == 12]
    print(f"\nWeek 12 ({w12[0]['date']} .. {w12[-1]['date']}) has {len(w12)} sessions:")
    for it in w12:
        print(f"  {it['date']} {it['time']}  {it['title']}  [{it['place']}]")
    w16_wed = [it for it in items if it["week"] == 16 and it["date"] == "2026-12-16"]
    print("\nWeek 16 Wed (2026-12-16) special check:")
    for it in w16_wed:
        print(f"  {it['date']} {it['time']}  {it['title']}  [{it['place']}]")

    if "--inject-mac" in sys.argv:
        total = inject_into_macos_defaults(items)
        print(f"\nInjected into macOS CalendarBoard.app local store (total {total} items).")


if __name__ == "__main__":
    main()
