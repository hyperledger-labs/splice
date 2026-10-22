# Docs

Docs are maintained in https://github.com/canton-network/cf-docs. The
docs in this repository only serve to test compilation of
auto-generated references docs.

## Development Builds

### Live-preview

Running `./livepreview.sh` builds the docs using `sphinx-autobuild`. Changes
to your docs will be picked up automatically without having to run a
full build.

If you do not care about the latest daml model docs for your preview,
you can add `--skip-daml` to the command for quicker start time.

### Full build

`./preview.sh` runs the full build and then launches a webserver
serving the results. This is mainly useful if you’re modifying themes
or other changes that are not picked up by `sphinx-autobuild`.

### Changes

When making a change, rebuild the documentation using `sbt docs/bundle`.
