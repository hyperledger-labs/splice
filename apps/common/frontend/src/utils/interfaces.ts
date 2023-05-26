import * as google_protobuf_timestamp_pb from 'google-protobuf/google/protobuf/timestamp_pb';
import { DisclosedContract } from 'common-protobuf/com/daml/ledger/api/v1/commands_pb';
import { ContractMetadata } from 'common-protobuf/com/daml/ledger/api/v1/contract_metadata_pb';
import { Identifier, Value } from 'common-protobuf/com/daml/ledger/api/v1/value_pb';
import { Contract as ProtoContract } from 'common-protobuf/com/daml/network/v0/contract_pb';
import { ContractWithState as ProtoContractWithState } from 'common-protobuf/com/daml/network/v0/contract_with_state_pb';
import {
  Contract as OpenAPIContract,
  ContractMetadata as OpenAPIContractMetadata,
} from 'directory-openapi';

import { ContractId, ContractTypeCompanion, Template } from '@daml/types';

export interface Contract<T> {
  contractId: ContractId<T>;
  payload: T;
  metadata: OpenAPIContractMetadata;
}

export interface ReadyContract<T> {
  contract: Contract<T>;
  domainId: string;
}

export const templateIdToIdentifier = (templateId: string): Identifier => {
  const [packageId, moduleName, entityName] = templateId.split(':');
  return new Identifier()
    .setPackageId(packageId)
    .setModuleName(moduleName)
    .setEntityName(entityName);
};

const fromHexString = (hexString: string) => {
  return Uint8Array.from((hexString.match(/.{1,2}/g) || []).map(byte => parseInt(byte, 16)));
};

const toHexString = (bytes: Uint8Array) =>
  bytes.reduce((str, byte) => str + byte.toString(16).padStart(2, '0'), '');

export const decodeProtoMetadata = (metadata: ContractMetadata): OpenAPIContractMetadata => {
  return {
    createdAt: (
      BigInt(metadata.getCreatedAt()!.getSeconds()) * BigInt(1000_000) +
      BigInt(metadata.getCreatedAt()!.getNanos()) / BigInt(1000)
    ).toString(10),
    contractKeyHash: toHexString(metadata.getContractKeyHash_asU8()),
    driverMetadata: toHexString(metadata.getDriverMetadata_asU8()),
  };
};

export const encodeProtoMetadata = (metadata: OpenAPIContractMetadata): ContractMetadata => {
  const createdAtMicros = BigInt(metadata.createdAt);
  const createdAtSeconds = createdAtMicros / BigInt(1000_000);
  const createdAtNanos = (createdAtMicros % BigInt(1000_000)) * BigInt(1000);
  const createdAt = new google_protobuf_timestamp_pb.Timestamp()
    .setSeconds(Number(createdAtSeconds))
    .setNanos(Number(createdAtNanos));
  return new ContractMetadata()
    .setCreatedAt(createdAt)
    .setContractKeyHash(fromHexString(metadata.contractKeyHash))
    .setDriverMetadata(fromHexString(metadata.driverMetadata));
};

// eslint-disable-next-line @typescript-eslint/no-redeclare
export const Contract = {
  decode: <T extends object, K>(c: ProtoContract, tmpl: Template<T, K>): Contract<T> => {
    const metadata: OpenAPIContractMetadata = decodeProtoMetadata(c.getMetadata()!);
    return {
      contractId: c.getContractId() as ContractId<T>,
      payload: tmpl.decodeProto(new Value().setRecord(c.getPayload()!)),
      metadata: metadata,
    };
  },
  decodeOpenAPI: <T extends object, K, I extends string>(
    c: OpenAPIContract,
    tmpl: ContractTypeCompanion<T, K, I>
  ): Contract<T> => ({
    contractId: c.contractId as ContractId<T>,
    payload: tmpl.decoder.runWithException(c.payload),
    metadata: c.metadata,
  }),
  toDisclosedContract: <T extends object, K>(
    tmpl: Template<T, K>,
    c: Contract<T>
  ): DisclosedContract => {
    return new DisclosedContract()
      .setTemplateId(templateIdToIdentifier(tmpl.templateId))
      .setContractId(c.contractId)
      .setCreateArguments(tmpl.encodeProto(c.payload).getRecord())
      .setMetadata(encodeProtoMetadata(c.metadata));
  },
};

// eslint-disable-next-line @typescript-eslint/no-redeclare
export const ReadyContract = {
  // undefined if c is in-flight between domains
  decodeContractWithState<T extends object, K>(
    cws: ProtoContractWithState,
    tmpl: Template<T, K>
  ): ReadyContract<T> | undefined {
    const c = cws.getContract();
    const domainId = cws.getDomainId();
    return c === undefined || domainId === ''
      ? undefined
      : { contract: Contract.decode(c, tmpl), domainId };
  },
};
