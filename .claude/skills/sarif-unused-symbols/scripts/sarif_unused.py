#!/usr/bin/env python3

# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
"""Helpers for the sarif-unused-symbols skill.

  candidates  Read a SARIF file produced by IntelliJ's inspection run, map every
              ScalaUnusedSymbol result from the commit the SARIF was run on to the
              current tree, and print one JSON object per line.
  verify      Check the position format against the SARIF commit itself (sanity test).
  attribute   Read sbt output on stdin and map `[error] file:line:col` lines back
              to symbols in a batch file (see SKILL.md, "Recovering from failures").

Positions in the SARIF are 1-based startLine/startColumn, endColumn exclusive;
charOffset is a 0-based character offset into the file; snippet is the identifier
itself (not the whole declaration).
"""
import argparse, json, re, subprocess, sys

RULE = "ScalaUnusedSymbol"


def git(*args, check=True):
    r = subprocess.run(["git", *args], capture_output=True)
    if check and r.returncode != 0:
        sys.exit(f"git {' '.join(args)} failed: {r.stderr.decode()}")
    return r.stdout.decode("utf-8")


def show(commit, path):
    r = subprocess.run(["git", "show", f"{commit}:{path}"], capture_output=True)
    return r.stdout.decode("utf-8").split("\n") if r.returncode == 0 else None


def results(sarif):
    with open(sarif) as f:
        for r in json.load(f)["runs"][0]["results"]:
            if r["ruleId"] != RULE:
                continue
            p = r["locations"][0]["physicalLocation"]
            yield r["message"]["text"], p["artifactLocation"]["uri"], p["region"]


def classify(msg):
    private = msg.startswith("Private ")
    head = msg.split(" ")[1 if private else 0].capitalize()
    kind = {
        "Parameter": "param", "Function": "def", "Class": "type", "Object": "type",
        "Value": "val", "Declaration": "decl",
    }.get(head, "other")
    if msg.startswith("Function definition") or msg.startswith("Private function"):
        kind = "def"
    return kind, private


def renames(base, head):
    out = {}
    for line in git("diff", "--name-status", "-M", base, head).splitlines():
        parts = line.split("\t")
        if parts[0].startswith("R"):
            out[parts[1]] = parts[2]
        elif parts[0] == "D":
            out[parts[1]] = None
    return out


def hunks(base, head, old, new):
    hs = []
    for line in git("diff", "-U0", base, head, "--", old, *([new] if new != old else [])).splitlines():
        m = re.match(r"@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@", line)
        if m:
            oc = int(m.group(2)) if m.group(2) is not None else 1
            nc = int(m.group(4)) if m.group(4) is not None else 1
            hs.append((int(m.group(1)), oc, int(m.group(3)), nc))
    return hs


def map_line(hs, n):
    """Return (new_line, exact). exact=False if n falls inside a changed hunk."""
    delta = 0
    for os_, oc, ns, nc in hs:
        if oc == 0:  # pure insertion after old line os_
            if os_ < n:
                delta += nc
            continue
        if n < os_:
            break
        if n < os_ + oc:
            return None, False
        delta += nc - oc
    return n + delta, True


def find_ident(line, ident, col_hint=None):
    hits = [m.start() for m in re.finditer(r"(?<![\w$])" + re.escape(ident) + r"(?![\w$])", line)]
    if col_hint is not None and col_hint in hits:
        return col_hint
    return hits[0] if len(hits) == 1 else None


def cmd_candidates(a):
    base, head = a.base, a.head
    rn = renames(base, head)
    sc_files = git("ls-files", "*.sc").split()
    sc_text = {p: open(p, encoding="utf-8").read() for p in sc_files}
    cache, hcache = {}, {}
    for i, (msg, uri, reg) in enumerate(results(a.sarif)):
        kind, private = classify(msg)
        name = reg["snippet"]["text"]
        new_uri = rn.get(uri, uri)
        status, line_no, col = "ok", None, None
        if new_uri is None:
            status = "deleted"
        else:
            if new_uri not in cache:
                try:
                    cache[new_uri] = open(new_uri, encoding="utf-8").read().split("\n")
                except FileNotFoundError:
                    cache[new_uri] = None
            lines = cache[new_uri]
            if lines is None:
                status = "deleted"
            else:
                if (uri, new_uri) not in hcache:
                    hcache[(uri, new_uri)] = hunks(base, head, uri, new_uri)
                ln, exact = map_line(hcache[(uri, new_uri)], reg["startLine"])
                col0 = reg["startColumn"] - 1
                if ln is not None and 1 <= ln <= len(lines) and lines[ln - 1][col0:col0 + len(name)] == name:
                    line_no, col = ln, col0 + 1
                else:
                    # moved or edited: search +-30 lines for a unique occurrence on a similar line
                    old_line = (show(base, uri) or [""] * (reg["startLine"]))[reg["startLine"] - 1].strip()
                    center = ln or reg["startLine"]
                    cands = []
                    for k in range(max(1, center - 30), min(len(lines), center + 30) + 1):
                        if lines[k - 1].strip() == old_line and find_ident(lines[k - 1], name) is not None:
                            cands.append(k)
                    if len(cands) == 1:
                        status, line_no = "moved", cands[0]
                        col = find_ident(lines[line_no - 1], name) + 1
                    else:
                        status = "stale"
        sc_refs = [] if kind in ("param", "decl") else sorted(p for p, t in sc_text.items() if re.search(r"(?<![\w$])" + re.escape(name) + r"(?![\w$])", t))
        print(json.dumps({
            "id": i, "kind": kind, "private": private, "name": name, "message": msg,
            "file": new_uri, "line": line_no, "col": col, "status": status,
            "sc_refs": sc_refs,
            "text": (cache[new_uri][line_no - 1].strip() if status in ("ok", "moved") else None),
        }))


def cmd_verify(a):
    bad = n = 0
    files = {}
    for msg, uri, reg in results(a.sarif):
        n += 1
        if uri not in files:
            files[uri] = git("show", f"{a.base}:{uri}")
        t = files[uri]
        name = reg["snippet"]["text"]
        okoff = t[reg["charOffset"]:reg["charOffset"] + reg["charLength"]] == name
        L = t.split("\n")[reg["startLine"] - 1]
        oklc = L[reg["startColumn"] - 1:reg["endColumn"] - 1] == name
        if not (okoff and oklc):
            bad += 1
            print("MISMATCH", uri, reg["startLine"], reg["startColumn"], name)
    print(f"{n} results checked against {a.base[:10]}, {bad} mismatches")
    sys.exit(1 if bad else 0)


def cmd_attribute(a):
    """sbt errors -> batch symbols. a.batch is a JSONL file of candidates (as printed above,
    plus optional 'new_line' recorded by the skill after editing)."""
    batch = [json.loads(l) for l in open(a.batch)]
    errs = []
    for l in sys.stdin:
        m = re.search(r"\[(?:error|warn)\]\s+(/\S+?\.scala):(\d+):(\d+)?:?\s*(.*)", l)
        if m:
            errs.append((m.group(1), int(m.group(2)), m.group(4)))
    hit = {}
    for path, ln, text in errs:
        for s in batch:
            same_file = path.endswith("/" + s["file"])
            names_it = re.search(r"(?<![\w$])" + re.escape(s["name"]) + r"(?![\w$])", text)
            if (same_file and abs((s.get("new_line") or s["line"]) - ln) <= a.window) or names_it:
                hit.setdefault(s["id"], []).append(f"{path.split('/')[-1]}:{ln} {text[:100]}")
    for sid, why in hit.items():
        print(json.dumps({"id": sid, "reasons": why[:3]}))
    print(f"{len(errs)} error lines, {len(hit)} batch symbols implicated", file=sys.stderr)


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    for name, fn in (("candidates", cmd_candidates), ("verify", cmd_verify)):
        s = sub.add_parser(name)
        s.add_argument("sarif")
        s.add_argument("--base", required=True, help="commit the SARIF was run on")
        s.add_argument("--head", default="HEAD")
        s.set_defaults(fn=fn)
    s = sub.add_parser("attribute")
    s.add_argument("batch")
    s.add_argument("--window", type=int, default=3)
    s.set_defaults(fn=cmd_attribute)
    a = p.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()
