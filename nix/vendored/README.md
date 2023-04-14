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
cd path/to/daml/repo
language-support/ts/extract-codegen-cabal.sh path/to/canton-coin/repo/nix/vendored/daml2ts-1.0.0.tar.gz
```

If you change dependencies, you also need to run `cabal2nix daml2ts-1.0.0 > ../daml2ts.nix`.

To update the patched typescript libraries run:

```
cd path/to/daml/repo
language-support/ts/codegen/extract-ts-libs.sh path/to/canton-coin/repo/nix/vendored
```

## Bumping the TS Codegen fork

If you encounter errors like the following during a Canton upgrade,
you likely need to rebase our TS codegen fork to be compatible with
new Daml-LF changes.

```
[error] daml2ts: user error (ProtobufError "UnknownEnum \"ExprSumBuiltin\" 149")
```

To do so, rebase https://github.com/digital-asset/daml/tree/ts-proto
to the SDK release you're upgrading to and repeat the two extraction
commands above.
