#!/usr/bin/env bash

# Support both "./lock-first-scratch.sh" and "source lock-first-scratch.sh / .lock-first-scratch.sh"
# The latter puts you in the scratch directory, so it's slightly more convenient.
script_is_not_sourced="(( SHLVL >= 2 ))"
if (( script_is_not_sourced )); then
  set -eou pipefail
fi


scratches=(
  "scratchneta"
  "scratchnetb"
  "scratchnetc"
  "scratchnetd"
  "scratchnete"
)

successful=""

for scratch in "${scratches[@]}"; do
  echo "Attempting to lock $scratch..."
  cd "$REPO_ROOT/cluster/deployment/$scratch"
  locked_scratch="$scratch"
  if (( script_is_not_sourced )); then
    direnv exec . cncluster lock && successful="true" && break
  else
    cncluster lock && successful="true" && break
  fi
done

if [ -z "$successful" ]; then
  echo "Failed to lock any scratch."
  exit 1
else
  echo "Successfully locked $locked_scratch."
  if (( script_is_not_sourced )) ; then
      echo "Run 'cd $REPO_ROOT/cluster/deployment/$locked_scratch'."
      echo "Tip: next time, if you run the script with 'source lock-first-scratch.sh', you will be put in the scratch directory directly."
  fi
fi
