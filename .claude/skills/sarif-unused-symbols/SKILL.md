---
name: sarif-unused-symbols
description: Remove unused Scala symbols reported in an IntelliJ SARIF inspection report (ruleId ScalaUnusedSymbol) in batches, checking after each batch that the whole project still compiles (main + test, plus a check against the .sc console scripts), and commit each passing batch. Use for "clean up the unused symbols from this SARIF report", "apply report_*.sarif.json", dead-code removal driven by IntelliJ inspections.
---

# Remove unused symbols from a SARIF report

Input: a SARIF file (`jq '.runs[0].results'` is a list) and, optionally, the commit it was run on.
Reference example: `log/report_2026-10-19_07-14-18.sarif.json`, run on `ef62712d6d58bc9063b8bd32137181f12176cc8b`.

## SARIF format (verified exactly against ef62712d6d, 957/957 results)

Each result with `ruleId == "ScalaUnusedSymbol"` has:
- `message.text`: `Value 'x' is never used`, `Private function definition 'f' is never used`,
  `Class|Object|Private class|... 'X' is never used`, `Declaration 'x' is never used`,
  `Parameter 'p' is not used in either this method or any of its derived methods`.
- `locations[0].physicalLocation.artifactLocation.uri`: repo-relative path.
- `region`: `startLine`, `startColumn` (both **1-based**), `endColumn` (**exclusive**), `charOffset`
  (**0-based**, in characters), `charLength`, `snippet.text` = the identifier only.
  The position is that of the **identifier**, not of the whole declaration, so you must find the
  declaration extent yourself.

## Step 0: set up

1. Determine the SARIF commit (argument, or ask). `git cat-file -t <sha>` must work; if not, `git fetch origin`.
2. Branch: create `<user>/unused-symbols-<date>` from the commit the user names (default: current HEAD). Never commit on the user's current feature branch or on main.
3. Sanity-check the position format and map positions to the current tree:
   ```
   S=.claude/skills/sarif-unused-symbols/scripts/sarif_unused.py
   python3 -I $S verify <sarif> --base <sha>
   python3 -I $S candidates <sarif> --base <sha> > $SCRATCH/cands.jsonl
   ```
   `candidates` follows renames and line shifts via `git diff` between `<sha>` and HEAD. Each line has
   `id, kind (param|def|type|val|decl), private, name, file, line, col, status (ok|moved|stale|deleted), sc_refs, text`.
   Drop `stale`/`deleted` (report them), never guess a position.

## Step 1: triage (decide what is removable)

Scope per the user's decisions:

| kind | action |
|---|---|
| `def`, `type` (class/object), `val` that are members | remove the whole declaration (incl. scaladoc/comments directly attached, and its now-empty companion if the object is only that) |
| `val` that is a local / pattern binder (`implicit val WalletUserRequest(user, ...) = x`, `for (i <- ..)`, `case a :: _toIgnore :: Nil`) | replace the identifier by `_` (never delete the right-hand side if it has effects) |
| `decl` | context bound (`[A: ClassTag]`) -> drop the bound; for/pattern binder -> `_`; type alias -> remove |
| `param` of a **private or local** method (private/private[x], nested def, anonymous function) | remove the parameter, fix all call sites. For lambda params like `implicit env =>` / `implicit webDriver =>` replace by `_ =>` |
| `param` of a non-private, non-local method (most `ec`, `scheduler`, `tag`, etc.) | **skip**, list in the report (signature change, overrides, external callers) |
| `override`, abstract members, anything referenced by reflection/codegen/`@unused`/serialization (`Unused_*TxLogEntry` constants documenting wire values) | skip, list in the report; read the surrounding comment first |

Also skip when `sc_refs` is non-empty **and** the symbol is a member (def/type/member val): the `.sc`
scripts are not compiled by sbt and may use it. `sc_refs` is a whole-word grep, so it is noise for locals,
binders and generic names like `user`; for those ignore it. When in doubt, grep the `.sc` file yourself
and keep the symbol if it really refers to this one.

Dependency order: removing a symbol can make others unused (helpers only it called, imports). That is
fine; do not chase it in the same run. Note newly-unused things for a re-run with a fresh SARIF.

## Step 2: batches

- Group by file; batch size about 25 symbols or one module's worth, whichever is smaller (this
  keeps error attribution easy). Batch order: private defs/types/vals, then members, then locals/binders,
  then local params (lambda `env`/`webDriver`, many per file, so batch by file count rather than symbol count).
- Apply edits **from the bottom of each file upward** (so earlier line numbers stay valid), or re-run
  `candidates` after each batch. Positions are only valid against an unedited file.
- Write the batch to `$SCRATCH/batch-N.jsonl` (the `candidates` lines you are acting on).
- Run `sbt "scalafmtOnly <files>"` on the touched files (see project-conventions).

## Step 3: compile check

Test code compiles with `-Xfatal-warnings`, so this also catches newly unused privates/params.
Run only one sbt build or Canton start at a time in a checkout (they share CPU, ports and `target/`
directories): wrap every build in `flock log/build.lock`. Run sbt through `bash -l -c 'cd <repo> && direnv exec . <cmd>'`
so the nix/direnv dev shell and `PATH` are loaded even from a non-login shell. Background long sbt runs:

```
flock log/build.lock bash -l -c 'cd <repo> && direnv exec . sbt -batch Test/compile' > $SCRATCH/compile-N.log 2>&1
```
Run it with `nohup`/`run_in_background` (output to the log file) and wait for it to finish with an until-loop on the
process or log rather than a bare `sleep`, which some environments block; do not poll. Then
`grep '^\[error\]' $SCRATCH/compile-N.log`.
(Whole-project `Test/compile` is deliberate: removals in `apps-common` affect every app and test module.
If a root aggregate is too slow, fall back to the aggregated modules of the touched files **and** every
dependent: `sbt "<mod>/Test/compile"` for each plus `apps-app/Test/compile`.)

If the run adds or edits any non-Scala file that needs a license header (e.g. a helper script), copy the header
from an existing file verbatim: `project/Headers.scala` hard-codes the year 2024 and `sbt -batch headerCheck` (run it
from the root project; per-module `headerCheck` flags unrelated files) compares the text exactly, including the blank
line before the first code or docstring.

Success: `[success]` and no `[error]`. Frontend/Daml/`.sc` are not part of this; the `.sc` safety net is the
Step 1 grep.

## Step 4: recovering from failures (use the compiler errors)

1. `python3 -I $S attribute $SCRATCH/batch-N.jsonl < $SCRATCH/compile-N.log` prints batch symbols implicated by
   an error: error within a few lines of the edit in the same file, or an error text (`not found: value X`,
   `too many arguments`, `missing argument list`, `ambiguous implicit`) naming the removed symbol. It is a
   heuristic: read the errors yourself and confirm.
2. Restore **only those symbols** (`git checkout -p`, or re-edit; for param removals also restore the call
   sites that were changed for them). Record them as `kept: <reason from compiler>`.
3. Recompile. Repeat at most 3 rounds. If errors cannot be attributed to a symbol (cascades, errors in
   untouched files), revert the whole batch (`git checkout -- <files>`) and rerun it at half the size; a
   single-symbol batch that fails is simply `kept`.
4. A failure that is only a new `-Wunused` warning-as-error (e.g. an import that became unused) is not a
   reason to keep the symbol: remove the newly unused import/private in the same batch and recompile.

## Step 5: commit

One commit per passing batch, on the branch from Step 0, following the repository's and your user's
commit conventions (see AGENTS.md "Contributing"):
- Subject e.g. `Remove unused private defs in apps/scan (batch 3)`; body lists the symbols
  (name + file) and that the project compiles.
- Never push, unless the user asks.

## Step 6: report

Write `$SCRATCH/unused-symbols-report.md` (and summarize to the user): counts removed per kind;
the `kept` list with the compiler reason; skipped params/overrides/`.sc`-referenced symbols;
`stale`/`deleted` results; newly-unused leftovers worth a second SARIF run; the commits created.
