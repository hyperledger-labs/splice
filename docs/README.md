# Docs

Documentation for all CC functionality.
Intended to be published to https://network.canton.global.

## Development Builds

### Live-preview

Running `make run` builds the docs using `sphinx-autobuild`. Changes
to your docs will be picked up automatically without having to run a
full build.

### Full build

`./preview.sh` runs the full build and then launches a webserver
serving the results. This is mainly useful if you’re modifying themes
or other changes that are not picked up by `sphinx-autobuild`.

### Changes

When making a change, push a new docker image into the Docker
container, use `make clean && make docker-push`.
