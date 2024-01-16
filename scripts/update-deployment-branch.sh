#!/usr/bin/env bash

# For each cluster, we maintain a branch called deployment/<cluster_name>
# that tracks the latest commit deployed to that cluster.
# This script updates the deployment branch for the provided cluster to the
# provided git revision (commit hash/branch/tag).

#   USAGE: update-deployment-branch.sh <cluster-name> [git-revision]

# In case a specific revision is not provided as the second argument,
# we query the cluster to determine the latest commit deployed there.
# This enables using this script to update the branch for an already deployed cluster.
# Note that this mode of operation requires VPN access.

set -eou pipefail

. .envrc.vars
clusters_root_dir="${REPO_ROOT}/cluster/deployment"

cluster_name=${1-}
git_revision=${2-}

if [ -z "${cluster_name}" ]; then
  echo "No cluster name provided"
  echo "USAGE: $0 <cluster-name> [git-revision]"
  exit 1
fi

if [ ! -d "${clusters_root_dir}/${cluster_name}" ]; then
  echo "Provide a valid cluster name. There is no ${cluster_name} directory under ${clusters_root_dir}."
  exit 1
fi

if [ -z "${git_revision}" ]; then
  echo "No git revision provided as input"
  echo "Retrieving source code version for cluster ${cluster_name}"
  cluster_version=$("$REPO_ROOT/scripts/fetch-cluster-version.sh" "${cluster_name}")
  cluster_git_commit=${cluster_version#*v}
  echo "Cluster Git revision: ${cluster_git_commit}"
  git_revision=${cluster_git_commit}
else
  echo "Input Git revision: ${git_revision}"
fi

if [ "${CI-}" ==  true ]; then
  if [ -z "${GITHUB_TOKEN-}" ]; then
    echo "Env var GITHUB_TOKEN must be set"
    exit 1
  fi
  git remote set-url origin "https://githubuser-da:${GITHUB_TOKEN}@github.com/DACH-NY/canton-network-node.git"
fi

git fetch origin
git_commit=$(git rev-parse --verify "${git_revision}")
echo "Updating deployment branch for cluster ${cluster_name} to ${git_commit}"
remote_branch_name="deployment/${cluster_name}"
git push --force origin "${git_commit}:refs/heads/${remote_branch_name}"
echo
