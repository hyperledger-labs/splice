# Proposals for Token Standards in Canton Network

## What you see

This is a draft of the code for a CN token standard that addresses

1. the needs of asset registries to manage assets so that they are available in all
CN wallets,
2. the needs of CN wallets to handle all CN assets, and
3. the needs of apps to construct (multi-legged) DvP transactions across arbitrary CN assets.

This code together with appropriate documentation is ultimately
meant to be submitted as a Canton Improvement Proposal (CIP) to the [Global Synchronizer Foundation](https://sync.global/). The current state of the code is intended to support a presentation of the design
shared with interested parties via the [CIP discussion mailing list](https://lists.sync.global/g/cip-discuss/topics).

We recommend starting with watching that presentation before reviewing the code itself.
Once done so we recommend reviewing the code and its tests in the directories of the form `cnrc-*`
jointly with the examples in `examples/`. Examples of particular interest are:

- [`examples/cn-token-test/daml/Splice/Scripts/TestCnTokenTransfer.daml`](examples/cn-token-test/daml/Splice/Scripts/TestCnTokenTransfer.daml):
    an example that shows how to execute a "free-of-payment transfer" of `Amulet` tokens using the standard.

- [`examples/cn-token-test/daml/Splice/Scripts/TestCnTokenDvP.daml`](examples/cn-token-test/daml/Splice/Scripts/TestCnTokenDvP.daml):
    an example that shows how to execute a DvP using the standard.

## How to compile the Daml code yourself

1. Setup development tools using your package manager of choice. Required tools:

   - `java >= 17`
   - [`yq >= 4.x`](https://github.com/mikefarah/yq)

2. Install the Daml SDK from scratch:

    ```bash
    ./script/install-daml-sdk.sh
    ```

    Or in case you already have a recent (> 2.9) SDK you can skip downloading the bootstrappng SDK using:

    ```bash
    ./script/install-daml-sdk.sh --no-bootstrap
    ```

3. Build the Daml Models:

    ```bash
    daml build --all
    ```

4. Run the tests:

    ```bash
    ./script/daml-test-all.sh
    ```
