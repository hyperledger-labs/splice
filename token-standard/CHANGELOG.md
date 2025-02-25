# Changelog for Splice Token Standards

## Unreleased

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


## Initial open source release of the standard proposal

* Initial release to https://github.com/digital-asset/cn-token-standard-proposal
