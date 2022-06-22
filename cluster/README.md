# canton-coin Cluster Management

This is a set of scripts for managing the Canton Network devnet
cluster. It provides facilities for creating the cluster from scratch,
deleting the cluster, and updating it with a new configuration.

All of these scripts must be run from within this directory, and none
of them take command line arguments of any kind.

* `cluster-create.sh` - Create the cluster and apply a configuration
  if it does not already exist.
* `cluster-connect.sh` - Connect to a cluster that already exists,
    allowing `kubectl` commands to be used to inspect and manage state.
* `cluster-apply.sh` - Apply the configuration from
  `canton-network-config.yaml` into the cluster.
* `cluster-delete.sh` - Ask for interactive confirmation and delete
  the cluster if it exists.

## Configuration Settings

These scripts do not take command line options and are currently
designed to manage a single instance of a Canton Network cluster, for
the M1 Devnet.

However, if there is a need to run the scripts against an alternate
GCP Project or Cluster, both of those configurations are stored in
[`config`](https://github.com/DACH-NY/the-real-canton-coin/blob/main/cluster/config).
