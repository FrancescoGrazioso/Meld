#!/usr/bin/env python3
"""
Spotify GQL Hash Checker

Fetches the current Spotify web player JavaScript bundle, extracts all
GraphQL operation→hash mappings, and updates docs/spotify-gql-hashes.json
with any changes detected.

Designed to run as a GitHub Actions daily cron job.
Exit codes:
  0 — no changes detected
  1 — error
  2 — hashes updated (signals the workflow to commit)
"""

import json
import re
import ssl
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

SPOTIFY_HOME = "https://open.spotify.com"
CDN_BASE = "https://open.spotifycdn.com/cdn/build/web-player/"
USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)
HASHES_JSON = Path(__file__).resolve().parent.parent.parent / "docs" / "spotify-gql-hashes.json"


def fetch(url: str, timeout: int = 30) -> str:
    ctx = ssl.create_default_context()
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=timeout, context=ctx) as resp:
        return resp.read().decode("utf-8", errors="replace")


def find_bundle_url(html: str) -> str | None:
    """Extract the main web-player JS bundle URL from the Spotify home page."""
    for match in re.finditer(
        r'(https://open\.spotifycdn\.com/cdn/build/web-player/web-player\.[a-f0-9]+\.js)',
        html,
    ):
        return match.group(1)
    return None


def extract_bundle_id(url: str) -> str:
    """Get the filename portion for tracking which bundle we parsed."""
    return url.rsplit("/", 1)[-1]


def extract_hash_map(js: str) -> dict[str, tuple[str, str]]:
    """
    Walk through all 64-char hex strings in the bundle and map them to
    nearby quoted identifiers that look like GQL operation names.

    Returns {operation_name: (hash, type)} where type is 'query' or 'mutation'.
    """
    result: dict[str, tuple[str, str]] = {}
    for m in re.finditer(r'"([a-f0-9]{64})"', js):
        hash_val = m.group(1)
        pos = m.start()

        before = js[max(0, pos - 200) : pos]
        after = js[pos : pos + 100]
        context = before[-100:] + ',"' + hash_val + '",' + after[:50]

        nearby = re.findall(r'"(\w{3,50})"', context)
        op_type = "mutation" if '"mutation"' in context else "query"

        for name in nearby:
            if (
                name != hash_val
                and len(name) > 3
                and name[0].islower()
                and not all(c in "0123456789abcdef" for c in name)
                and name not in ("query", "mutation", "null", "true", "false")
            ):
                result[name] = (hash_val, op_type)

    return result


def extract_chunk_map(js: str) -> list[tuple[str, str]]:
    """Extract webpack chunk ID→hash map for lazy-loaded chunks."""
    candidates = re.findall(
        r'\{(\d+:"[a-f0-9]{8}"(?:,\d+:"[a-f0-9]{8}")*)\}', js
    )
    if not candidates:
        return []
    longest = max(candidates, key=len)
    return re.findall(r'(\d+):"([a-f0-9]{8})"', longest)


def scan_chunks(
    chunk_map: list[tuple[str, str]],
    tracked_ops: set[str],
    found_ops: dict[str, tuple[str, str]],
) -> dict[str, tuple[str, str]]:
    """Scan lazy-loaded webpack chunks for operations not found in the main bundle."""
    missing = tracked_ops - set(found_ops.keys())
    if not missing:
        return found_ops

    print(f"  Scanning {len(chunk_map)} chunks for {len(missing)} missing operations...")
    for chunk_id, chunk_hash in chunk_map:
        if not missing:
            break
        url = f"{CDN_BASE}{chunk_id}.{chunk_hash}.js"
        try:
            chunk_js = fetch(url, timeout=10)
            for op in list(missing):
                if f'"{op}"' in chunk_js:
                    idx = chunk_js.index(f'"{op}"')
                    ctx = chunk_js[max(0, idx - 80) : idx + 400]
                    hashes = re.findall(r'"([a-f0-9]{64})"', ctx)
                    if hashes:
                        op_type = "mutation" if '"mutation"' in ctx else "query"
                        found_ops[op] = (hashes[0], op_type)
                        missing.discard(op)
                        print(f"    Found {op} in chunk {chunk_id}")
        except Exception:
            continue

    return found_ops


def load_current_json() -> dict:
    if HASHES_JSON.exists():
        return json.loads(HASHES_JSON.read_text("utf-8"))
    return {"schema_version": 1, "operations": {}}


def update_json(
    current: dict,
    bundle_hashes: dict[str, tuple[str, str]],
    bundle_id: str,
) -> tuple[dict, bool]:
    """
    Compare bundle hashes with current JSON and produce an updated version.
    Returns (updated_json, has_changes).
    """
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    ops = current.get("operations", {})
    changed = False

    for op_name, op_data in ops.items():
        old_hash = op_data.get("hash")
        bundle_entry = bundle_hashes.get(op_name)

        if bundle_entry:
            new_hash, op_type = bundle_entry
            op_data["last_verified"] = now
            op_data["type"] = op_type

            if new_hash != old_hash:
                print(f"  ROTATED: {op_name}")
                print(f"    old: {old_hash}")
                print(f"    new: {new_hash}")
                op_data["previous_hash"] = old_hash
                op_data["hash"] = new_hash
                op_data["status"] = "verified"
                op_data["last_changed"] = now
                changed = True
            else:
                if op_data.get("status") != "verified":
                    op_data["status"] = "verified"
                    changed = True
        else:
            if op_data.get("status") != "not_in_bundle":
                print(f"  MISSING: {op_name} — not found in bundle")
                op_data["status"] = "not_in_bundle"
                op_data["last_verified"] = now
                changed = True

    current["last_updated"] = now
    current["bundle_id"] = bundle_id
    current["operations"] = ops

    return current, changed


def main() -> int:
    print("=== Spotify GQL Hash Checker ===\n")

    print("1. Fetching Spotify home page...")
    try:
        html = fetch(SPOTIFY_HOME)
    except Exception as e:
        print(f"   ERROR: Could not fetch Spotify home: {e}")
        return 1

    bundle_url = find_bundle_url(html)
    if not bundle_url:
        print("   ERROR: Could not find web-player bundle URL in HTML")
        return 1

    bundle_id = extract_bundle_id(bundle_url)
    print(f"   Bundle: {bundle_id}")

    print("2. Fetching main bundle...")
    try:
        js = fetch(bundle_url)
    except Exception as e:
        print(f"   ERROR: Could not fetch bundle: {e}")
        return 1
    print(f"   Size: {len(js):,} bytes")

    print("3. Extracting operation→hash mappings...")
    bundle_hashes = extract_hash_map(js)
    print(f"   Found {len(bundle_hashes)} operations in main bundle")

    print("4. Loading current hashes JSON...")
    current = load_current_json()
    tracked_ops = set(current.get("operations", {}).keys())
    print(f"   Tracking {len(tracked_ops)} operations")

    print("5. Scanning lazy-loaded chunks for missing ops...")
    chunk_map = extract_chunk_map(js)
    print(f"   Found {len(chunk_map)} webpack chunks")
    bundle_hashes = scan_chunks(chunk_map, tracked_ops, bundle_hashes)

    print("6. Comparing and updating...")
    updated, changed = update_json(current, bundle_hashes, bundle_id)

    if changed:
        HASHES_JSON.write_text(
            json.dumps(updated, indent=2, ensure_ascii=False) + "\n",
            encoding="utf-8",
        )
        print(f"\n   JSON updated: {HASHES_JSON}")
        return 2
    else:
        # Still update last_verified timestamps
        HASHES_JSON.write_text(
            json.dumps(updated, indent=2, ensure_ascii=False) + "\n",
            encoding="utf-8",
        )
        print("\n   No hash changes detected. Timestamps updated.")
        return 0


if __name__ == "__main__":
    sys.exit(main())
