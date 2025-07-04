# Docs

Developer Documentation for all Splice functionalities.

## Contributing to the Splice docs

In order to setup your development environment, please see the [Development README](../DEVELOPMENT.md).

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

## Simplified build

For non developers who would rather not [setup a full developer environmnet](../DEVELOPMENT.md), you can preview a version of the doc by:

- Installing the requirements for the doc only: 

```bash
./install-docs-requirements.sh
```

- Building the docs locally:

```bash
make livehtml
```
