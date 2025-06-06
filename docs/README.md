# Docs

Developer Documentation for all Splice functionalities.

## Development Builds

### Live-preview

Running `./livepreview.sh` builds the docs using `sphinx-autobuild`. Changes
to your docs will be picked up automatically without having to run a
full build.

### Full build

`./preview.sh` runs the full build and then launches a webserver
serving the results. This is mainly useful if you’re modifying themes
or other changes that are not picked up by `sphinx-autobuild`.

### Changes

When making a change, rebuild the documentation using `sbt docs/bundle`.
