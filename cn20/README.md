# Proposals for Token Standards in Canton Network

## What you see

This is a proposal for a set of RFCs (Request for Comments) that we would like
to publish via the GSF (Global Synchronizer Foundation, see
https://sync.global/) to foster the development of a set of standards that allow
asset registry apps, wallet apps, and trading apps to compose uniformly, but evolve
independently.

We recommend to review this code as follows:

1. Watch this [presentation](https://docs.google.com/presentation/d/1ffBf2uv3jfeupvrvLUwIFt2bjcIPzmpVNvt8eibPlSE/edit#slide=id.g2722663396b_0_1589) to get an overview
of the why and what.
2. Review the code and its tests in the directories of the form `cnrc-*`
jointly with the examples in `examples/`. Examples of particular interest are:

    - [`examples/cn-token-test/daml/Splice/Scripts/TestCnTokenTransfer.daml`](examples/cn-token-test/daml/Splice/Scripts/TestCnTokenTransfer.daml)
    - [`examples/cn-token-test/daml/Splice/Scripts/TestCnTokenDvP.daml`](examples/cn-token-test/daml/Splice/Scripts/TestCnTokenDvP.daml)
    - [`examples/cn-token-test/daml/TestCnTokenUtilityVsAmuletDvP.daml`](examples/cn-token-test/daml/TestCnTokenUtilityVsAmuletDvP.daml)

