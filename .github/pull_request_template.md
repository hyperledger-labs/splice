

### Pull Request Checklist

#### Cluster Testing
- [ ] If a cluster test is required, comment `/cluster_test` on this PR to request it, and ping someone with access to the DA-internal system to approve it.
- [ ] If a hard-migration test is required (from the latest release), comment `/hdm_test` on this PR to request it, and ping someone with access to the DA-internal system to approve it.

#### PR Guidelines
- [ ] Include any change that might be observable by our partners or affect their deployment in the [release notes](https://github.com/hyperledger-labs/splice/blob/main/docs/src/release_notes.rst).
- [ ] Specify fixed issues with `Fixes #n`, and mention issues worked on using `#n`
- [ ] Include a screenshot for frontend-related PRs - see [README](https://github.com/hyperledger-labs/splice/blob/main/TESTING.md#running-and-debugging-integration-tests) or use your favorite screenshot tool


#### Merge Guidelines
- [ ] Make the git commit message look sensible when squash-merging on GitHub (most likely: just copy your PR description).
