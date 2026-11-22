#!/usr/bin/env python3
"""导出 / 恢复 CalendarBoard 的 calendar_items 云端数据（仅使用 Python 标准库）。

用法:
  python3 scripts/export_backup.py --email you@example.com
  python3 scripts/export_backup.py --email you@example.com --restore backups/calendar-backup-20260101-120000.json
"""
import argparse
import datetime
import getpass
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from upload_to_supabase import ROOT, request_json

PAGE_SIZE = 1000
BATCH_SIZE = 50
TABLE_PATH = "/rest/v1/calendar_items"


def login(email, password):
    """使用邮箱 + 密码换取 Supabase access_token。"""
    auth = request_json(
        "/auth/v1/token?grant_type=password",
        method="POST",
        body={"email": email, "password": password},
    )
    return auth["access_token"]


def export_backup(token):
    """全量分页导出 calendar_items，返回 (备份文件路径, 总条数)。"""
    all_rows = []
    offset = 0
    while True:
        rows = request_json(
            f"{TABLE_PATH}?select=id,payload,updated_at&order=id.asc&limit={PAGE_SIZE}&offset={offset}",
            token=token,
        )
        if not rows:
            break
        all_rows.extend(rows)
        if len(rows) < PAGE_SIZE:
            break
        offset += PAGE_SIZE

    backup_dir = os.path.join(ROOT, "backups")
    os.makedirs(backup_dir, exist_ok=True)
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    path = os.path.join(backup_dir, f"calendar-backup-{stamp}.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(all_rows, f, ensure_ascii=False, indent=2)
    return path, len(all_rows)


def restore_backup(token, path):
    """读取备份 JSON，按 50 条一批 upsert 恢复，返回恢复条数。"""
    with open(path, "r", encoding="utf-8") as f:
        items = json.load(f)
    if not isinstance(items, list):
        raise SystemExit(f"备份文件格式错误：顶层应为 JSON 数组（{path}）")

    total = len(items)
    for i in range(0, total, BATCH_SIZE):
        batch = [
            {"id": it["id"], "payload": it["payload"] if "payload" in it else it}
            for it in items[i : i + BATCH_SIZE]
        ]
        request_json(
            TABLE_PATH,
            method="POST",
            body=batch,
            token=token,
            extra_headers={"Prefer": "resolution=merge-duplicates,return=minimal"},
        )
        print(f"已恢复 {min(i + BATCH_SIZE, total)}/{total}...")
    return total


def main():
    parser = argparse.ArgumentParser(
        description="导出 / 恢复 CalendarBoard calendar_items 云端备份"
    )
    parser.add_argument("--email", required=True, help="日历账号邮箱")
    parser.add_argument("--password", help="日历账号密码（不传则交互式输入）")
    parser.add_argument("--restore", metavar="文件路径", help="从备份 JSON 恢复云端数据")
    args = parser.parse_args()

    password = args.password or getpass.getpass("请输入日历账号密码: ")
    token = login(args.email, password)

    if args.restore:
        restored = restore_backup(token, args.restore)
        print(f"恢复完成，共 {restored} 条数据。")
        return

    path, total = export_backup(token)
    print(f"导出完成：{path}（共 {total} 条）")


if __name__ == "__main__":
    main()
