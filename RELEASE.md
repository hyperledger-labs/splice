
### Release process

The file VERSION in the repo root defines the current base version. By default, all commits are
versioned `<VERSION>-<suffix>` where `<suffix>` is a function of the current commit and/or the
local username (see `build-tools/get-snapshot-version` for details).

Commits with a message starting with `[release]` exclude the `-<suffix>` part and are hence
considered stable releases.

The process for cutting a new release is described in the [cut-release-and-deploy.md](.github/ISSUE_TEMPLATE/cut-release-and-deploy.md) issue template.
