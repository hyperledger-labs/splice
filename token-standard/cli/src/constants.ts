// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
export const HoldingInterface =
  "#splice-api-token-holding-v1:Splice.Api.Token.HoldingV1:Holding";

export const TransferInstructionInterface =
  "#splice-api-token-transfer-instruction-v1:Splice.Api.Token.TransferInstructionV1:TransferFactory";

// TODO (#18500): include allocations
export const TokenStandardTransactionInterfaces = [
  HoldingInterface,
  TransferInstructionInterface,
];

const SpliceMetaKeyPrefix = "splice.lfdecentralizedtrust.org/";
export const TxKindMetaKey = `${SpliceMetaKeyPrefix}tx-kind`;
export const SenderMetaKey = `${SpliceMetaKeyPrefix}sender`;
export const ReasonMetaKey = `${SpliceMetaKeyPrefix}reason`;
export const AllKnownMetaKeys = [TxKindMetaKey, SenderMetaKey, ReasonMetaKey];
