// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

export interface InterfaceId {
  packageName: string;
  moduleName: string;
  entityName: string;
  toString(): string;
  matches(interfaceId: string): boolean;
}
function buildInterfaceId(
  packageName: string,
  moduleName: string,
  entityName: string,
): InterfaceId {
  return {
    packageName,
    moduleName,
    entityName,
    toString() {
      return `#${packageName}:${moduleName}:${entityName}`;
    },
    matches(interfaceId: string) {
      return interfaceId.endsWith(`${moduleName}:${entityName}`);
    },
  };
}
export const HoldingInterface = buildInterfaceId(
  "splice-api-token-holding-v1",
  "Splice.Api.Token.HoldingV1",
  "Holding",
);

export const TransferFactoryInterface = buildInterfaceId(
  "splice-api-token-transfer-instruction-v1",
  "Splice.Api.Token.TransferInstructionV1",
  "TransferFactory",
);

export const TransferInstructionInterface = buildInterfaceId(
  "splice-api-token-transfer-instruction-v1",
  "Splice.Api.Token.TransferInstructionV1",
  "TransferInstruction",
);

// TODO (#18500): include allocations
export const TokenStandardTransactionInterfaces = [
  HoldingInterface,
  TransferFactoryInterface,
  TransferInstructionInterface,
];

const SpliceMetaKeyPrefix = "splice.lfdecentralizedtrust.org/";
export const TxKindMetaKey = `${SpliceMetaKeyPrefix}tx-kind`;
export const SenderMetaKey = `${SpliceMetaKeyPrefix}sender`;
export const ReasonMetaKey = `${SpliceMetaKeyPrefix}reason`;
export const BurnedMetaKey = `${SpliceMetaKeyPrefix}burned`;
export const AllKnownMetaKeys = [
  TxKindMetaKey,
  SenderMetaKey,
  ReasonMetaKey,
  BurnedMetaKey,
];
