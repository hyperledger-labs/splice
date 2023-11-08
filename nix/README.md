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
