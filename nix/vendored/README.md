# typescript libraries

We vendor the typescript libraries to support some additional features
around explicit disclosure and multi-domain.

This repo only includes the tarball and should not be edited
directly. All changes should go in the corresponding
[branch](https://github.com/digital-asset/daml/tree/canton-network) on the
Daml repo.

Note that to pick up new changes to daml-types that have been added
independently of our customizations you might need to rebase the
`canton-network` branch.

To update the patched typescript libraries run:

```
cd path/to/daml/repo
language-support/ts/codegen/extract-ts-libs.sh path/to/canton-network-node/repo/nix/vendored
```
