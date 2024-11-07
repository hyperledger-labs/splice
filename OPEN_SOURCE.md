# Working with open sourced content

## Target State

Details of the target state and plan are in https://docs.google.com/document/d/1qT4SZbaOqYoTBgYHTJ86cQVSw6DHMMm0-Mm8itVnEuI/edit, but the gist of it is:
the vast majority of this repo will eventually move over to Splice, and we execute
our development directly against that, while engaging also non-DA contributors.

## Current State

Currently:
- "Everything" has been approved for open sourcing
- Our Daml models, apps, dockerfiles and Helm charts have been open sourced at https://github.com/digital-asset/decentralized-canton-sync, copyrighted to Digital Asset
  - This repo is updated from CircleCI on every deployment of CIDaily (in the `deploy-cidaily` workflow)
    and every new release (in the `publish_public_artifacts` workflow)
- Our Daml models have also been handed over to Splice at https://github.com/hyperledger-labs/splice
  - We currently do not yet push the rest of the code to Splice since it still contains copyrighted terms (such as Canton Coin)
- We still develop in our private repo first, and then copy code to the open source repos.
  This implies that for the time being, we need to maintain both DA's open source repo as well as Splice,
  until active development moves over to Splice. Moreover, our contributions need to be on
  DA's public repo *before* being pushed to Splice.
- All source files include a copyright header. The git pre-commit hook should
  add them automatically if missing. To trigger that manually, run `sbt headerCreate`.

## Process

As part of creating a new CN release:
1. A PR will be auto-created against the [decentralized-canton-sync](https://github.com/digital-asset/decentralized-canton-sync) repo,
   please review and merge it, so that the released snapshot will be dumped to that repo. Note that it will create a release-line
   branch in `decentralized-canton-sync`, mirroring that of the internal repo.
2. Similarly, a PR will be auto-created against the [splice](https://github.com/hyperledger-labs/splice) repo,
   please review and merge it as well. If you don't have permissions to merge PRs to the splice repo, ping someone how does.
   Currently, this is Itai, Ray, or Moritz.
