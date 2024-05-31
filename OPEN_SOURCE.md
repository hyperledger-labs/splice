# Working with open sourced content

## Target State

Details of the target state and plan are in https://docs.google.com/document/d/1qT4SZbaOqYoTBgYHTJ86cQVSw6DHMMm0-Mm8itVnEuI/edit, but the gist of it is:
the vast majority of this repo will eventually move over to Splice, and we execute
our development directly against that, while engaging also non-DA contributors.

## Current State

Currently:
- Only our Daml models have been approved for open sourcing
- Our Daml models have been open sourced at https://github.com/DACH-NY/decentralized-canton-sync, copyrighted to Digital Asset
- Our Daml models have also been handed over to Splice
- We still develop the Daml models in our private repo first, and then copy
them to the open source repos. This implies that for the time being, we need
to maintain both DA's open source repo as well as Splice, until active
development moves over to Splice. Moreover, our contributions need to be on
DA's public repo *before* being pushed to Splice.

## Process

As part of creating a new CN release, please:
1. Run in the public `decentralized-canton-sync` repo:
`scripts/update.sh <CN_REPO_ROOT> <SPLICE_REPO_ROOT>`
2. Review and commit any Daml changes in `decentralized-canton-sync`
3. Once the PR in `decentralized-canton-sync` is merged, review and commit
   the same changes in the `splice` repo. Note that you will need to commit
   with `git commit -s` for your commit to be signed off in order to be able
   to merge it to Splice (unfortunately, there does not seem to be a git
   config to make that the default).
