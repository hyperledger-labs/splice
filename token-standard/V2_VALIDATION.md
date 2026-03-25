## Token Standard V2 Daml API Validation

The V2 Daml APIs are ready for initial validation. A small number of minor cleanups remain, but these are **not expected to block validation efforts**.

Please provide feedback on this [PR that shows the V2 API changes relative to V1](https://github.com/hyperledger-labs/splice/pull/4562/changes), or via Slack/IM/Email.


### Scope

This validation phase aims to confirm that the V2 APIs can be correctly implemented and used in both token registries and trading applications, including mixed V1/V2 settlement scenarios.
It aims to do so by writing Daml script tests that mirror real-world use cases and workflows, using the provided test packages as a reference.

### Required Actions

**Token registry developers**

* Implement the V2 APIs in your token registry.
* Validate correctness by running TokenDvP-style tests against your implementation (e.g., analogous to `Splice.Tests.TestAmuletTokenDvP_V1V2Mixed`)

**Trading application developers**

* Adapt core workflows to use the V2 APIs.
* Verify that mixed-version (V1/V2) settlement works as expected.
* Use `Splice.Tests.TestAmuletTokenDvP_V1V2Mixed` and `Splice.Testing.Apps.TradingAppV2` as a reference and adapt them to your application.

### Setup

**Important:** base all your actions on the
   [`token-standard-v2-daml-preview` branch](https://github.com/hyperledger-labs/splice/tree/token-standard-v2-daml-preview)

1. Copy the non-test DARs from `/daml/dars/` in the into your validation project.
2. Copy the Daml script test packages from the same branch **as source code** to avoid cross-SDK issues.
3. Use the following as blueprints and adapt them to your asset and workflows:

   * `Splice.Tests.TestAmuletTokenDvP_V1V2Mixed`
   * `Splice.Testing.Apps.TradingAppV2`

### Expected Changes

* The API surface is considered stable enough for validation, but small refinements may still occur.
* Test coverage and example implementations will continue to improve.

---

## Appendix: Planned Cleanup (Non-blocking)

* Move `TradingAppV2` into its own package to enable reuse in integration tests.
* Merge `splice-token-standard-test-v1` and `splice-token-standard-test-v2` into a single test package, with separate modules for V1 and V2 tests.

Cleanup performed so far:

* Replace `ChoiceExecutionMetadata` with concrete result types for `AllocationRequest_Reject`
  and `AllocationRequest_Withdraw` choices to prepare for an eventual future where interface definitions
  may be upgraded
- Use `authorizerHoldingCids` instead of `senderHoldingCids` in all V2 choice results that
  return holdings of the allocation authorizer
- Add an explicit `AllocationRequest_Accept` choice to provide a standard way for wallets to signal acceptance
  and provide replay protection for the creation of the corresponding allocations
- Add a new `Splice.Util.TokenWallet.BatchingUtilityV2` template with choices that implement the standard
  logic for accepting V1 and V2 requests in a V2 wallet.
- Reordered the `HoldingV2.Account` fields to put `owner` first for improved readability of debug output
- Return "holding change" as `TextMap [ContractId Holding]` where the keys are `instrumentId.id`s, so that
  callers can identify the holdings for a specific instrument without needing to fetch the holding.
- Replace buggy `netAllocationCreditAmount` with `netAllocationCreditAmounts` that properly distinguishes
  between legs of different instruments, and a map of credit amounts by instrument id.
- Export 'require' and 'isGreaterOrEqualR' and its variants from `Splice.TokenStandard.Utils` to simplify
  writing validation code in choice bodies.
- Extend token standard test infrastructure:
  - Add `TestTokenV1` to simulate settlement involving V1-only tokens
  - Add `TestTokenV2` to simulate settlement involving tokens with support for
    accountable holdings and multiple instruments maintained by the same `admin`
  - Add `MultiRegistry` to simulate the off-ledger APIs of multiple registries in a single test environment
  - Extend `TradingAppV2` to support mixed version settlement of trades involving V1-only tokens, and V1/V2 tokens
    whose allocations are created through either a V1 wallet or a V2 wallet
- Remove redundant `Splice.Testing.UtilsV2` module: use `Splice.Testing.Utils` instead
- Improve commentary on `V2.AllocationRequest` choices
- Add missing choice observers to `V2.TransferFactory_Transfer`
- Renamed `_extraObserverDefaultImpl` to `_extraObserversDefaultImpl` to reflect that it can return multiple observers
- Clarify that input holdings for `V2.TransferFactory_Transfer` must be of the transferred instrument
- Extend test infrastructure:
  - Add transfer support for `TestTokenV2`
  - `WalletClientV2` support for listing and accepting V1 and V2 transfer offers using the same functions
- Extend `BatchingUtilityV2` with support for accepting V2 transfer instructions
- Add utility functions to create metadata for [V1 transaction history parsing](https://docs.digitalasset.com/integrate/devnet/exchange-integration/txingestion.html#differences-between-1-step-deposits-and-withdrawals)
  to `Splice.TokenStandard.Utils`
- Bump the Daml SDK for building the V2 API packages to 3.4.11
- Change default implementation of `SettlementFactory_SettleBatch` to check uniqueness of transfer leg ids
  to provide better safety guarantees for code that identifies legs by their ids
- add default implementations for extra observers for `SettlementFactory_SettleBatch`  for public and private assets
- use `Account.id : Text` instead of `Optional Text` to ensure that wallets do not have to deal with
  two different kinds of defalt accounts (empty string vs None). Use "" as the default account identifier.
- Change version numbers of `splice-api-token-*-v2` packages to `1.0.0` to be consistent with the existing
  `splice-api-featured-app-v2` package, which also has version `1.0.0`
- *Drop the need for `extraSettlementAuthorizers` and `extraReceiptAuthorizers`* to use
  allocations created using `V1.AllocationFactory_Allocate` in a V2 settlement.
  - **motivation**: the extra actors on `SettlementFactory_SettleBatch` made it impossible to use V1 allocation with privacy,
    which was discovered by app providers attempting to implement the compatibility mode.
  - **key changes**:
    - The `V2.Allocation_Settle` choice always only requires authorization from the `executors` and
      the instrument `admin`. Apps can thus call `V2.SettlementFactory_SettleBatch` using `executors`
      authority only.
    - Apps need to create missing receipt allocations using their own delegation contracts from their
      traders. See the `TradingAppV2` implementation for an example of how to do this. Also note the
      use of `Splice.TokenStandard.Utils.ensureIsReceiptAllocation` to check
      that the allocation factory call delegation is for a receipt allocation only.
    - Asset owners must be aware that allocations created using `V1.AllocationFactory_Allocate` can
      be settled with only `executor` authority. They must only create allocations for `executors`
      that they trust to atomically settle trades involving their allocations.
- Introduce and implement event based reporting of transfers:
   - motivation:
       - decouple the logic of executing a transfer from reporting it to external systems
       - simplify parsing for wallets: just look for `TransferEventV2` events to understand all balance changes
   - add new API package `splice-api-token-transfer-events-v2`
   - add `Splice.Api.Token.TransferEventV2` module
   - add `Splice.TokenStandard.Utils` convenience functions to log events for burns, mints, and transfers
   - emit `TransferEventV2` events for all amulet choices, including properly tagging burns from ANS payments
   - emit `TransferEventV2` events for `TestTokenV2` choices
- Introduce `AllocationRequest.originalRequestId` and `Allocation.originalAllocationId` fields, which
  are used to track the same request or allocation across state updates,
  analogously to the `originalInstructionCid` of transfer and allocation instructions.
