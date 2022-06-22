# Docs

Documentation for all CC functionality.
Intended to be published to https://network.canton.global.

## Live-preview

Running `./live-preview.sh` builds the docs using
`sphinx-autobuild`. Changes to your docs will be picked up
automatically without having to run a full build.

## Full build

`./preview.sh` runs the full build and then launches a webserver
serving the results. This is mainly useful if you’re modifying themes
or other changes that are not picked up by `sphinx-autobuild`.

## Minikube deployment

The docs can be deployed to a local `minikube` kubernetes cluster. To do so:

- Run: `./deploy-minikube.sh`

The docs page should now be accessible in a browser at `localhost:7080`

### Changes

When making a change, these are the steps to rebuild a new image and deploy it:

- Move into the cluster directory: `cd ../cluster`
- Build the docs image: `./image-build docs`
- Push the docs image: `./image-push docs`
