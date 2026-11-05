#!/usr/bin/env python3
"""
scripts/build_rime_assets.py

General CLI tool to convert standard Rime tables (YAML / TSV / TXT)
into optimized TSV dictionary assets for TypeFree's built-in IME engine:
  1. dict.tsv: `code\\tword1\\tword2...` grouped by code, preserving Rime priority
  2. dz.tsv:   `code\\tchar` 4-code single-character reverse-lookup table

No personal paths or sensitive credentials are hardcoded. Pass paths via CLI arguments.
"""

import argparse
import os
import sys
from collections import defaultdict


def parse_tsv_entries(path: str):
    """Parses a Rime dict YAML/TSV/TXT file. Returns list of (word, code, weight_int)."""
    if not path or not os.path.exists(path):
        return []
    entries = []
    with open(path, encoding="utf-8", errors="ignore") as f:
        for raw in f:
            line = raw.rstrip("\r\n")
            if not line or line.startswith("#") or line in ("---", "..."):
                continue
            if "\t" not in line:
                continue
            parts = line.split("\t")
            word = parts[0].strip()
            code = parts[1].strip()
            if not word or not code:
                continue
            weight = 0
            if len(parts) >= 3:
                try:
                    weight = int(parts[2].strip())
                except ValueError:
                    weight = 0
            entries.append((word, code, weight))
    return entries


def sort_layer_by_weight(layer):
    by_c = defaultdict(list)
    for idx, (w, c, wt) in enumerate(layer):
        by_c[c].append((wt, idx, w))
    flattened = []
    for c, items in by_c.items():
        # Higher weight first; ties preserve original file line order
        items.sort(key=lambda t: (-t[0], t[1]))
        for wt, _, w in items:
            flattened.append((w, c, wt))
    return flattened


def main():
    parser = argparse.ArgumentParser(
        description="Build TypeFree Rime TSV dictionary assets from source tables."
    )
    parser.add_argument(
        "--base-dict",
        type=str,
        help="Path to base Rime dict (e.g. flypy.dict.yaml / base.dict.yaml)",
    )
    parser.add_argument(
        "--sys-dict",
        type=str,
        default=None,
        help="Path to system dict (e.g. flypy_sys.txt)",
    )
    parser.add_argument(
        "--user-rime-dir",
        type=str,
        default=None,
        help="Path to user Rime directory containing flypy_top.txt, flypy_user.txt, etc.",
    )
    parser.add_argument(
        "--dz-dict",
        type=str,
        default=None,
        help="Path to 4-code single-character dictionary for reverse lookup (e.g. flypydz.dict.yaml)",
    )
    parser.add_argument(
        "--out-dir",
        type=str,
        default="app/src/main/assets",
        help="Output directory for generated TSV files (default: app/src/main/assets)",
    )
    parser.add_argument(
        "--out-dict-name",
        type=str,
        default="flypy_dict.tsv",
        help="Main dictionary file name (default: flypy_dict.tsv)",
    )
    parser.add_argument(
        "--out-dz-name",
        type=str,
        default="flypy_dz.tsv",
        help="Reverse single-char table file name (default: flypy_dz.tsv)",
    )

    args = parser.parse_args()

    os.makedirs(args.out_dir, exist_ok=True)

    base_entries = []
    if args.base_dict and os.path.exists(args.base_dict):
        raw_base = parse_tsv_entries(args.base_dict)
        for w, c, wt in raw_base:
            if len(c) > 4 and c.startswith("ok"):
                continue
            base_entries.append((w, c, wt))
    else:
        print("[Notice] No --base-dict provided or file does not exist. Generating default baseline structure.")

    layers = []

    # Check user rime dir for layer files if supplied
    if args.user_rime_dir and os.path.isdir(args.user_rime_dir):
        top_entries = parse_tsv_entries(os.path.join(args.user_rime_dir, "flypy_top.txt"))
        user_sys_entries = parse_tsv_entries(os.path.join(args.user_rime_dir, "flypy_sys.txt"))
        user_custom_entries = parse_tsv_entries(os.path.join(args.user_rime_dir, "flypy_user.txt"))
        full_char_entries = parse_tsv_entries(os.path.join(args.user_rime_dir, "flypy_full.txt"))

        layers.append(sort_layer_by_weight(top_entries))
        layers.append(base_entries)
        layers.append(sort_layer_by_weight(user_sys_entries))
        layers.append(sort_layer_by_weight(user_custom_entries))
        layers.append(full_char_entries)
    else:
        layers.append(base_entries)

    merged_by_code = defaultdict(list)
    seen_per_code = defaultdict(set)

    for layer in layers:
        for w, c, _ in layer:
            if len(c) > 4 and c.startswith("ok"):
                continue
            if w not in seen_per_code[c]:
                seen_per_code[c].add(w)
                merged_by_code[c].append(w)

    sorted_codes = sorted(merged_by_code.keys())
    dict_lines = [f"{c}\t" + "\t".join(merged_by_code[c]) for c in sorted_codes]
    dict_payload = ("\n".join(dict_lines) + "\n").encode("utf-8")

    out_dict_path = os.path.join(args.out_dir, args.out_dict_name)
    with open(out_dict_path, "wb") as f:
        f.write(dict_payload)

    # Reverse table
    dz_lines = []
    if args.dz_dict and os.path.exists(args.dz_dict):
        dz_raw = parse_tsv_entries(args.dz_dict)
        dz_lines = [f"{c}\t{w}" for w, c, _ in dz_raw if len(c) == 4 and c.isalpha()]
        dz_payload = ("\n".join(dz_lines) + "\n").encode("utf-8")
        out_dz_path = os.path.join(args.out_dir, args.out_dz_name)
        with open(out_dz_path, "wb") as f:
            f.write(dz_payload)
        print(f"Built {out_dz_path}: {len(dz_lines)} single-character entries")
    else:
        print("[Notice] No --dz-dict provided; skipping dz table generation.")

    total_words = sum(len(v) for v in merged_by_code.values())
    print(f"Successfully generated {out_dict_path}: {len(sorted_codes)} unique codes, {total_words} words.")


if __name__ == "__main__":
    main()
