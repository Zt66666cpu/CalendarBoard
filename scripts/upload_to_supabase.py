#!/usr/bin/env python3
"""
Upload CalendarBoard JSON timetable to Supabase (uses only Python stdlib).
Usage:
  python3 scripts/upload_to_supabase.py --email you@example.com
"""
import argparse
import getpass
import json
import os
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_JSON = os.path.join(ROOT, "data", "timetable-2026-fall.json")
SUPABASE_URL = "https://samnpjgnvtearyeejjtg.supabase.co"
PUBLISHABLE_KEY = "sb_publishable_x061oGvVUpjXv0PG1J-Kag_Voayt0Ux"


def request_json(path, method="GET", body=None, token=None, extra_headers=None):
    headers = {
        "apikey": PUBLISHABLE_KEY,
        "Accept": "application/json",
    }
    if body is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if extra_headers:
        headers.update(extra_headers)
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(f"{SUPABASE_URL}{path}", data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            raw = resp.read().decode("utf-8")
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {e.code}: {detail}")


def main():
    parser = argparse.ArgumentParser(description="Upload timetable JSON to Supabase")
    parser.add_argument("--email", required=True, help="CalendarBoard account email")
    parser.add_argument("--file", default=DEFAULT_JSON, help="Path to timetable JSON")
    args = parser.parse_args()

    password = getpass.getpass("请输入日历账号密码: ")
    auth = request_json(
        "/auth/v1/token?grant_type=password",
        method="POST",
        body={"email": args.email, "password": password},
    )
    token = auth["access_token"]

    with open(args.file, "r", encoding="utf-8") as f:
        items = json.load(f)

    total = len(items)
    for i in range(0, total, 50):
        batch = [{"id": it["id"], "payload": it} for it in items[i : i + 50]]
        request_json(
            "/rest/v1/calendar_items",
            method="POST",
            body=batch,
            token=token,
            extra_headers={"Prefer": "resolution=merge-duplicates,return=minimal"},
        )
        print(f"Uploaded {min(i + 50, total)}/{total}...")

    print(f"Done! {total} items synced to Supabase.")


if __name__ == "__main__":
    main()
