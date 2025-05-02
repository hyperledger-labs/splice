# Changelog for Splice Token Standards

## Unreleased

Major changes:

- Adjusted the `AllocationInstruction` interfaces to follow the structure of `TransferInstruction`, and
  use a common `AllocationInstructionResult` result type to report on the result of all steps that
  create or advance the creation of an allocation.

Polishing changes:

- Report the `holdingCids` backing an `Allocation` to enable wallets to correlate them.
- Report the `inputHoldingCids` that are provided as part of an `AllocationInstruction` to
  create an allocation. This allows wallets to correlate holdings with pending allocation
  instructions.
- Report the `senderHoldingCids` for sender change returned as part of advancing the state
  of an `AllocationInstruction`.
- Add a `requestedAt` field to the `AllocationInstructionView` to report the
  time when the creation of an allocation was requested by a wallet.
- Make a polishing pass across the Daml docs of all token standard APIs.
- Bump the version of the `splice-api-*` packages and the `splice-token-standard-test`
  harness to `1.0.0` in preparation of their first release.
- Switched Metadata from `Map Text` to `TextMap` to improve the JSON encoding to a normal object instead
  of an array of key-value pairs.
- Add the `/v1/` prefix to all OpenAPI specs and the paths in the specs to
  be leave space for an eventual `/v2/` version of the API.
- Add number of `decimals` used by an instrument as a mandatory field to the instrument
  metadata returned by a registry's off-ledger endpoint
- Switch to using text for identifying transfer legs in allocation and allocation requests.

## 2025-04-15

Major changes:

- Extended the lock information on a `Holding` with an optional human-readable context description,
  and optional lock expiry information.
- Populate this information in the `LockedAmulet` implementation and allow using amulet `Holding`s
  with expired locks as inputs to transfers and allocations. Thereby enabling a combined unlock and
  use of amulet tokens in a single transaction.
- Removed the `RegistryAppInstall` interface from the token metadata API, as it turned out to be
  difficult to standardize in a uniform way. We expect to reintroduce a separate standard to
  aid wallets in navigating to registry specific UIs running locally against an investor's
  Canton node in the future.
- Moved `BurnMintFactory` to a dedicated `splice-api-burn-mint-v1`
  packages. `BurnMintFactory` is intended to decouple apps like
  bridges that need to execute burns and mints from the underlying
  token model which isn't a core part of holdings.
- Added additional metadata keys to support universal transaction history
  parsing of registry-internal workflows. It is based on annotating exercise nodes on choices
  that are not standardized with a `splice.lfdecentralizedtrust.org/tx-kind`
  metadata key with one of the following values:
    1. `transfer`: transfer control over a holding from sender to one or more receivers and/or lock holders
    2. `expire-dust`: sweep a dust holding (e.g. an expired `Amulet`)
    3. `merge-split`: a transaction that merges and then splits a number of holdings by the same owner
    4. `burn`: a transaction intended to burn some amount of holding
    5. `mint`: a transation for minting some amount of holding
    6. `unlock`: unlock a locked holding

  Transfers should additionally be annoted with `splice.lfdecentralizedtrust.org/sender` to denote
  the sender of the transfer. These annotations are only required on the non-standardized choices.

  These annotations can be provided on non-standardized choices using smart-contract upgrading to
  add a `meta : Optional TextMap` field to the choice return type. The transaction history parser
  will just match that field by name to extract the metadata of an unknown choice.

  Additionally, both non-standard and standardized choices MAY be annotated with
  `splice.lfdecentralizedtrust.org/burned` to annotate the amount of holdings burned in this transaction.
  The amount minted is inferred by the transaction history parser as the difference between
  the amount of holdings before and after the transaction minus the burn. This construction only supports
  choices that consume holdings from a single owner and a single instrument only, which we deem
  to be a reasonably assumption. Choices that consume holdings from multiple senders or multiple holding
  types are parsed as a batch of multiple events.

Polishing changes

- Removed `inputHoldingsAmount` from
  `BurnMintFactory_BurnMintResult`. If you need that information, use
  the ledger API's `GetEventsByContractId` endpoint to lookup the
  holding by contract id.
- Rename the `AllocationInstruction.inputHoldings` field and the
  `Transfer.holdingCids` field to `inputHoldingCids` for uniformity
- Removed the `validUntil` field from choice contexts returned by off-ledger APIs,
  as that is a left-over from a design stage where we did not require registries to support
  a 24h prepare-submission delay time by default.
- Improve extensibility by allowing to pass the metadata intended to be used
  when exercising a choice into the off-ledger API for fetching the choice context.
- Distinguish in the `TransferInstructionResult` betweeen successful and failed completions
  of a transfer instruction; and allow both create and update steps for transfer instructions
  to return "change" to the sender. Thereby improving the sender's ability to batch multiple
  transfer instruction steps in a single Daml transaction for high throughput use-cases.
- Move amulet specific metadata keys under `amulet.splice.lfdecentralizedtrust.org/`
  to distinguish them from keys that are intended for all tokens implementing the token standard.

## 2025-03-31

Major changes:

* Added support for two-step transfers based on an offer-accept workflow in the `TransferFactory` API.
  This allows to transfer tokens to parties that have not pre-approved the receipt of the tokens from
  the sender. Amulet also implements this two-step transfer flow.

Polishing changes:

* Add a `requestedAt` field to `Transfer` to enable computing the lock duration for two-step transfers w/o
  relying on `getTime` in Daml, which would introduce a tight one minute prepare-submission delay.
* Add a `originalInstructionId` field to allocation and transfer instructions to aid wallets in
  tracking the evolution of an instruction over multiple steps.
* Switch openapi specs to use camelCase to be consistent with the participant JSON API.
* Rename `providerId` to `adminId` in token metadata openapi.
* Remove the admin party from listing instrument ids as it must be the
  same as the admin party of the overall registry.
* Replace `supportedStandards` by `supportedApis` in the openapi.
* Add `supportedApis` to each instrument to support different instruments supporting different APIs.

## 2025-03-24

Major changes:

Polishing changes:

* Add `TransferFactory_PublicFetch`, `AllocationFactory_PublicFetch` and `BurnMintFactory_PublicFetch` choices.
* Added `meta` field to all interface view types.

## 2025-03-17

Major changes:

* Added a `BurnMintFactory` API to `Splice.Api.Token.HoldingV1` to
  capture generic burns and mints that do not fall under the transfer
  or allocation APIs.

Polishing changes:

* Added the new holdings to the result types of `TransferFactor_Transfer`, `TransferInstruction_ReportSuccess` and `Allocation_ExecuteTransfer`.

## 2025-02-28

Major changes:

* Move development https://github.com/hyperledger-labs/splice/tree/canton-3.3/token-standard.
  to reflect that the API definitions of the token standard will be developed and open sourced as part of the splice project.
* Rename modules as follows
  * `Canton.Network.RC1.TokenMetadata` -> `Splice.Api.Token.MetadataV1`
  * `Canton.Network.RC2.Holding` -> `Splice.Api.Token.HoldingV1`
  * `Canton.Network.RC4.Allocation` -> `Splice.Api.Token.AllocationV1`
  * `Canton.Network.RC5.AllocationRequest` -> `Splice.Api.Token.AllocationRequestV1`
  * `Canton.Network.RC6.AllocationInstruction` -> `Splice.Api.Token.AllocationInstructionV1`
* Rename packages as follows
  * `cnrc-1-token-metadata` -> `splice-api-token-metadata-v1`
  * `cnrc-2-holdings` -> `splice-api-holding-v1`
  * `cnrc-3-transfer-instruction` -> `splice-api-transfer-instruction-v1`
  * `cnrc-4-allocation` -> `splice-api-allocation-v1`
  * `cnrc-5-allocation-request` -> `splice-api-allocation-request-v1`
  * `cnrc-6-allocation-instruction` -> `splice-api-allocation-instruction-v1`
* Removed support for delegates from `TransferInstruction`. This was added to support 24h delays between
  preparing and executing for tokens that require a two-step flow due to reliance on ephemeral contracts.
  The only token that makes use of this point is Amulet and we expect to remove that requirement later
  so it doesn't make sense to standardize. If you do need 24h delays for amulet, for now you need to
  use the non-standardized APIs for amulet as the standardized ones are limited to a 1min delay.
* Made all choices of the `Allocation` interface consuming to simplify their implementation and
  avoid the implementation bug of forgetting to archive the allocation.

Polishing changes:

* Decouple `AllocationRequest` from `splice-api-transfer-instruction-v1` by having it define its own `TransferLeg` type to represent a leg of a multi-part DvP transfer from
  `splice-api-token-allocation-v1`'s `Transfer` type
* Switch `settlementCid` in `SettlementInfo` from `AnyContractId` to a
  record type `Reference` containing an optional contract id and a
  text id.
* Switch `ChoiceContext` from a type synonym to a record.
* Consistently use `meta` as the field name for fields holding metadata.
* Remove `TransferInstructionV1.TransferSpecification` in favor of `TransferInstructionV1.Transfer`
* Rename `AllocationV1.Transfer` in favor of `AllocationV1.TransferLeg`
* Remove `AIS_Failed` status from `AllocationInstruction` and
  introduce explicit `AllocationInstruction_Allocate` and
  `AllocationInstruction_Abort` choices.
* Consistently add a `self` argument containing the contract id of the contract to all interface choices.
* Inline the `pendingActions` field in both `TransferInstructionView` and `AllocationInstructionView` to represent them more directly
* Use a shared `ChoiceExecutionMetadata` type for choices that only return metadata about their execution.
* Added a `name` field to `ChoiceContext`.
* Add `expectedAdmin` field to `AllocationFactory_Allocate` and `TransferFactory_Transfer` to allow safely executing those choices even if
  the explicitly disclosed factory contracts were read from a not fully trusted off-ledger API.
* Change all interface choice implementation functions to have the signature `ContractId Interface -> ChoiceArgument -> Update ChoiceResult`.
* Report the time of computation of the `total_supply` in the `TokenMetadata` off-ledger API.

## Initial open source release of the standard proposal

* Initial release to https://github.com/digital-asset/cn-token-standard-proposal
