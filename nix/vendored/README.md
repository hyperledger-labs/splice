# daml2ts & typescript libraries

We vendor daml2ts sources to include patches that also support
decoding/encoding to grpc-web protobuf types.

This repo only includes a tarball and should not be edited
directly. All changes should go in the corresponding
[branch](https://github.com/digital-asset/daml/tree/ts-proto) on the
Daml repo.

Note that to pick up new changes to daml-types that have been added
independently of our customizations you might need to rebase the
ts-proto branch.

To update the tarball in this repository run:

```
path/to/daml/repo/language-support/ts/extract-codegen-cabal.sh daml2ts-1.0.0.tar.gz
```

If you change dependencies, you also need to run `cabal2nix daml2ts-1.0.0 > ../daml2ts.nix`.

To update the patched typescript libraries run:

```
path/to/daml/repo/language-support/ts/extract-ts-libs.sh nix/vendored
```
