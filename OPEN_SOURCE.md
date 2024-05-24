# Working with open sourced content

## Target State

Details of the target state and plan are in https://docs.google.com/document/d/1qT4SZbaOqYoTBgYHTJ86cQVSw6DHMMm0-Mm8itVnEuI/edit, but the gist of it is:
the vast majority of this repo will eventually move over to Splice, and we execute
our development directly against that, while engaging also non-DA contributors.

## Current State

Currently:
- Only our Daml models have been approved for open sourcing
- Our Daml models have been open sourced at https://github.com/DACH-NY/decentralized-canton-sync, copyrighted to Digital Asset
- The code has NOT yet been handed over to HLF and not been copied over to Splice
- We still develop the Daml models in our private repo first, and then copy them to the open source repo

## Process

As part of creating a new CN release, please run in the public
`decentralized-canton-sync` repo:
`scripts/update.sh <CN_REPO_ROOT>`, review and commit any Daml changes there, with a
PR comment explaining the changes.
