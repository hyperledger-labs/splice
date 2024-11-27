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
To disable this behaviour we have set `PULUMI_HOME` to the nix pulumi path. The behavior is disabled because we will lack permissions to create new files in that location.

### Extra Plugins

The `extra-pulumi-packages.nix` defines a list of plugins that will be installed on top of the ones comes with the nix version.
The file is based on the default [pulumi setup](https://github.com/NixOS/nixpkgs/blob/master/pkgs/tools/admin/pulumi-bin/data.nix) and is generated using `generate_pulumi_packages.sh`.

If you need to add a new plugin, you can do so by adding the plugin name and version to the `generate_pulumi_packages.sh` script and then run it.
Examine the `extra-pulumi-packages.nix` to ensure that the plugin is correctly added.

We have a number of older plugins in our extra packages. These are either plugins that are not yet in the official nix package (eg: command) or packages
that require multiple versions while we upgrade the pulumi stack to the latest version.

The version of a pulumi plugin must exactly match the javascript version of the pulumi package used for the same plugin.
