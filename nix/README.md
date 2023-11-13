# Nix

`nix` definitions for build dependencies, loaded using `direnv` as explained
in the [`README.md`](../README.md) at the repo root.

When splitting-off a top-level directory into its own repo, we expect to use
`nix` and `direnv` there as well for providing build dependencies.


## Updating flakes

To update the versions used in the project follow the steps:
- Open a terminal outside a direnv nix environment
- Run `nix flake update path:<path-to-the-canton-network-repository>/nix`
- Commit the changes to the `flake.lock` file


## Pulumi setup

Pulumi by default installs by itself any missing plugins, in the default `PULUMI_HOME` location (`$HOME/pulumi` by default).
This could lead to higher build times in our CI as those plugins might not be cached. Also, the version of those plugin might
change without us being explicitly aware of it.

### Extra Plugins

The `extra-pulumi-packages.nix` defines a list of plugins that will be installed on top of the ones comes with the nix version.
The file is based on the default [pulumi setup](https://github.com/NixOS/nixpkgs/blob/master/pkgs/tools/admin/pulumi-bin/data.nix).
To add a new one use the same format for the urls, changing the version and populate the sha checksums from the github release page. Eg for kubernetes https://github.com/pulumi/pulumi-kubernetes/releases/tag/v3.30.1

We have a number of older plugins in our extra packages. These are as follows:

- gcp 6.50.0 required by the gateway stack. Should no longer be required once we apply the latest upgrade to all the infra stacks.
