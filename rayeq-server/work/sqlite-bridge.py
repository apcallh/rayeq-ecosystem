#!/usr/bin/env python3
import sys
import json
import sqlite3

db_path = sys.argv[1] if len(sys.argv) > 1 else ":memory:"
conn = sqlite3.connect(db_path)
conn.row_factory = sqlite3.Row
conn.execute("PRAGMA foreign_keys = ON;")

for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    req = json.loads(line)
    req_id = req.get("id")
    sql = req.get("sql", "")
    params = req.get("params", [])
    mode = req.get("mode", "run")
    try:
        cur = conn.cursor()
        cur.execute(sql, params)
        if mode == "first":
            row = cur.fetchone()
            res = dict(row) if row is not None else None
            conn.commit()
            print(json.stringify({"id": req_id, "ok": True, "data": res}) if hasattr(json, "stringify") else json.dumps({"id": req_id, "ok": True, "data": res}), flush=True)
        elif mode == "all":
            rows = [dict(r) for r in cur.fetchall()]
            conn.commit()
            print(json.dumps({"id": req_id, "ok": True, "data": {"results": rows}}), flush=True)
        else:
            conn.commit()
            print(json.dumps({"id": req_id, "ok": True, "data": {"changes": cur.rowcount, "lastRowId": cur.lastrowid}}), flush=True)
    except Exception as e:
        print(json.dumps({"id": req_id, "ok": False, "error": str(e)}), flush=True)
