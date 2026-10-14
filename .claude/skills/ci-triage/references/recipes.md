# Command recipes (all verified 2026-09)

## 1. Run, jobs, job log
```
gh run view $RUN --repo canton-network/splice --json headBranch,headSha,displayTitle,createdAt,jobs \
  --jq '{b:.headBranch,sha:.headSha[0:10],t:.displayTitle,c:.createdAt}, (.jobs[]|select(.conclusion=="failure")|{id:.databaseId,name:.name})'
gh api repos/canton-network/splice/actions/jobs/$JOB/logs > log/$REF/job.log      # by job id; names like "(1)" also match "(11)"
```
Job logs expire after ~90 days (HTTP 410); artifacts sooner. `gh run view --log` often returns nothing; use the API.

## 2. Classify the job log
```
sed -E 's/\x1b\[[0-9;]*m//g' log/$REF/job.log | grep -a -E 'FAILED \*\*\*|Tests: succeeded|All tests passed|contains problems|error\] +org|Run completed|##\[error\]' | sed -E 's/^[^Z]*Z //' | sort -u
```
Flagged lines only (the ones that actually fail checkErrors):
```
sed -E 's/\x1b\[[0-9;]*m//g; s/^[^Z]*Z //' log/$REF/job.log | awk '/^Found (problems|unmasked secrets|deprecated config paths) in /{p=1} p; /^Total: [0-9]+ lines with /{p=0}' | cut -c1-600
```
Failing assertion and stack head:
```
sed -E 's/\x1b\[[0-9;]*m//g' log/$REF/job.log | grep -a -A12 'FAILED \*\*\*' | sed -E 's/^[^Z]*Z //' | grep -v 'still running'
```
Suites in the shard (the split echo near the top of the log):
```
grep -a -A16 -E '^\S+Z org\.lfdecentralizedtrust\.splice\.integration\.tests\.[A-Za-z0-9]+$' log/$REF/job.log | grep -a -oE 'tests\.[A-Za-z0-9]+$' | sed 's/tests\.//' | sort -u
```

## 3. Artifact
```
export TMPDIR=$PWD/log/ghtmp; mkdir -p $TMPDIR
gh api "repos/canton-network/splice/actions/runs/$RUN/artifacts?per_page=100" --jq '.artifacts[] | "\(.id) \(.size_in_bytes) \(.name) expired=\(.expired)"'
gh run download $RUN --repo canton-network/splice -n logs-<job-slug> -D log/$REF/logs-<job-slug>
```
Contents: `canton_network_test.clog.gz` (apps + test framework), `canton.clog.gz` or `canton_before_shutdown.clog.gz`
(all canton nodes; simtime shards: `canton-simtime.clog.gz`), `canton-standalone-*.clog.gz` (per standalone
instance, each with its own ignore file), frontend shards: `browser.<suite>.<user>.N.1.log.gz` (geckodriver, epoch-ms
timestamps) and `npm-<app>-<user>.out.gz` (Vite dev servers), `webpage-*.html.gz` (DOM dump at failure).

## 4. Timelines
```
W='T13:1[5-8]'   # time window around the flagged timestamps, here 13:15-13:18
zcat $T | grep -a -E "Starting test suite|Test (succeeded|failed): |Starting '" | grep -a "$W" | sed -E 's/"logger_name":.*//; s/\{"@timestamp":"([^"]+)","message":"/\1 /' | cut -c1-170
```
Generic line formatter (timestamp, level, logger, message):
```
sed -E 's/"logger_name":"([^"]*)".*"level":"([A-Z]+)".*/[\1] \2/; s/\{"@timestamp":"([^"]+)","message":"/\1 /; s/1220[0-9a-f]{60}/../g' | cut -c1-230
```
Follow one request end to end by trace id: `zcat $C | grep -a <trace-id>`.
Browser logs: epoch ms; `awk -F'\t' '$1>=<from> && $1<=<to>'`; `log.entryAdded` with `"level":"error"` are the
JS console errors; `WebDriver:Navigate` and `browsingContext.domContentLoaded` mark page loads.

## 5. Canton pin and backport state
```
git show <sha>:nix/canton-sources.json | grep -m1 version
git fetch origin <release-line>          # e.g. release-line-0.8.4; the run's headBranch
git merge-base --is-ancestor <fix-sha> origin/<branch> && echo present || echo MISSING
git log --format='%h %ad %s' --date=short origin/<release>..origin/main -- <test file>     # main-only fixes to that file
```

## 6. Canton jar instead of vendored source
```
V=<version>; curl -sSLo canton-$V.tgz https://www.canton.io/releases/canton-open-source-$V.tar.gz
tar -xzf canton-$V.tgz --wildcards '*/lib/canton-open-source-*.jar'
python3 -c "import zipfile;z=zipfile.ZipFile('<jar>');print([n for n in z.namelist() if b'<log string>' in z.read(n)])"
javap -p -c -l -cp <jar> <fully.qualified.Class>   # -l prints the LineNumberTable for exact line numbers
```
Public mirror `digital-asset/canton` is squashed (`[main] Update <date>` commits, no shared shas); compare
sources or jars, not commit ids.

## 7. Ignore patterns
`project/ignore-patterns/canton_log.ignore.txt` (canton logs), `canton_network_test_log.ignore.txt` (app/test log),
`canton_log_shutdown_extra.ignore.txt` (after-shutdown half only), `canton-standalone-<instance>.ignore.txt`.
`.github/actions/scripts/check-logs.sh` prints ignored lines with the suffix and real problems without it.

## 8. Fix branch
A separate worktree keeps the triage branch checked out. `-b` refuses to overwrite an existing branch; for a
stacked follow-up, add a worktree for the existing branch instead.
```
git fetch origin main
git worktree add -b <user>/fix-<ref>-<slug> ../fix-<ref> origin/main       # e.g. <user>/fix-10172-multiarch-check-retry
git worktree add -b <user>/backport-<ref>-<pr>-<release-line> ../backport-<ref> origin/<release-line>   # e.g. <user>/backport-10169-7299-release-line-0.8.3
git worktree add ../fix-<ref> <user>/fix-<ref>-<slug>                        # stacked follow-up on an existing fix branch
git -C ../backport-<ref> cherry-pick -x -s <sha>                             # backports
git -C ../fix-<ref> commit -s -m "[ci] <one subject line>"
git worktree remove ../fix-<ref>                                             # when done (the branch stays)
```
The ref in the branch name is the cn-test-failures issue the branch fixes (the parent ref when it closes a family).
Cheap checks: `sbt --batch "apps-app/Test/scalafmtCheck"`, `npx --no-install prettier --check <files>`.
