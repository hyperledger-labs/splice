#!/usr/bin/env bash

# This deletes the devnet-next tag. The next `publish_public_release` CI job
# with `mark-for-devnet-if-unmarked=true` will recreate it. `cidaily`
# deployments do this.

read -r -p "This will delete the devnet-next tag. Type \"I know what I'm doing\" to continue: " confirmation

if [ "$confirmation" = "I know what I'm doing" ]; then
  echo "Deleting devnet-next tag."
  git tag --delete devnet-next
  git push origin --delete devnet-next
else
  echo "You don't know what you're doing. Doing nothing."
fi
