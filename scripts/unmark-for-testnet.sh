#!/usr/bin/env bash

# This deletes the testnet-next tag. The next `publish_public_release` CI job
# with `mark-for-testnet-if-unmarked=true` will recreate it. `cidayly`
# deployments do this.

read -r -p "This will delete the testnet-next tag. Type \"I know what I'm doing\" to continue: " confirmation

if [ "$confirmation" = "I know what I'm doing" ]; then
  echo "Deleting testnet-next tag."
  git tag --delete testnet-next
  git push origin --delete testnet-next
else
  echo "You don't know what you're doing. Doing nothing."
fi
