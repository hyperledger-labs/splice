# Packet template

File: `ci-triage/<ref>-<short-slug>.md`. Title line first, then one paragraph of verdict, then numbered
sections. Every section is: one sentence of what the command shows, the command in a fence, the verbatim
output in a fence (long hashes trimmed by the sed/cut in the command itself, e.g. `s/1220[0-9a-f]{60}/../g`).

```
# <ref> - <one-line failure description> (run <run id>)

<DUPLICATE of ... | new> ... one paragraph: branch/sha, job, canton pin, tests passed/failed, what checkErrors flagged.

- Run: <url>, <branch> <sha> ("<commit title>"), job <id> `<job name>`.
- Runtime canton: <version from nix/canton-sources.json at the sha>.
- Component: <test | splice app | Canton module | infra>.

## 1. Flagged lines / failing assertion
## 2. Which test was running (suite timeline around the timestamps)
## 3..n. Mechanism, one section per hop, each with command + output
## Verdict
Duplicate of / family; flake or real; fix location; fix branch if any; what was NOT verified.
```

README rows:
- Mapping table: `| ref | run | branch sha (PR) | job id and name | canton pin |`
- Overview table: `| ref | failure (one line, with the key timestamps) | duplicate of | packet + resolution/status |`
- Fix-branch table: `| branch | commit | fixes | verified here |`
