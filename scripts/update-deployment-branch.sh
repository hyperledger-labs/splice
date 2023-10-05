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

fetch_cluster_git_commit() {
  cluster=${1}
  # shellcheck disable=SC1090
  . "${clusters_root_dir}/${cluster}/.envrc.vars"

  count=0
  success=42
  while [ ${count} -lt 10 ]; do
    if cluster_version=$(curl -sL "http://${GCP_CLUSTER_BASENAME}.network.canton.global/version" --connect-timeout 2); then
      if [[ -n $cluster_version ]]; then
        echo "Cluster version: ${cluster_version}"
        success=0
        break
      else
        echo "Empty cluster version retrieved. Retry count $count"
      fi
    else
      echo "Failed to retrieve cluster version. Check that you are connected to the VPN."
    fi
    (( count++ ))
    sleep 3
  done
  if [ ${success} -ne 0 ]; then
     echo "Could not retrieve cluster version"
     exit 1
  fi

  if [[ ${cluster_version} =~ ^[0-9.]*-snapshot[0-9.]*v[0-9a-z]* ]]; then
    cluster_git_commit="${cluster_version#*v}"
  else
    echo "Unexpected cluster version format: ${cluster_version}"
    exit 1
  fi
}

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
  fetch_cluster_git_commit "${cluster_name}"
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
