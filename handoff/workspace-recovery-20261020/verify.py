"""Verify inert recovered source payloads; never import or apply them."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parent
index = json.loads((root / "index.json").read_text(encoding="utf-8"))
seen = set()
for entry in index["versions"]:
    path = (root / entry["payload"]).resolve()
    assert path.is_relative_to(root) and path not in seen
    seen.add(path)
    assert hashlib.sha256(path.read_bytes()).hexdigest() == entry["sha256"], entry["original_path"]
actual = {p.resolve() for p in (root / "sources").rglob("*.source")}
assert actual == seen, "Unindexed or missing source payload"
print(f"Verified {len(seen)} inert recovered source versions")
