# canton-coin Cluster Management

This is a set of scripts for managing the Canton Network devnet
cluster. It provides facilities for creating the cluster from scratch,
deleting the cluster, and updating it with a new configuration.

All of these scripts must be run from within this directory, and none
of them take command line arguments of any kind.

* `cluster-create` - Create the cluster and apply a configuration
  if it does not already exist.
* `cluster-connect` - Connect to a cluster that already exists,
   allowing `kubectl` commands to be used to inspect and manage state.
* `cluster-apply` - Apply the configuration from
  `canton-network-config.yaml` into the cluster.
* `cluster-delete` - Ask for interactive confirmation and delete
  the cluster if it exists.

## Configuration Settings

These scripts take configuration settings via the environment. The
following variables are required to be present, and an error will be
thrown if they are missing.

| Variable Name      | Meaning                                                               |
| ------------------ | --------------------------------------------------------------------- |
| `GCP_PROJECT_NAME` | Name of the Google Cloud project in which the cluster is located.     |
| `GCP_CLUSTER_NAME` | Name of the GKE cluster within the cloud project.                     |
| `GCP_REPO_NAME`    | Name of the image repository used to manage project container images. |
| `GCP_IP_NAME`      | Name of the publically exposed IP address assigned to the cluster.    |


#!/usr/bin/env bash
