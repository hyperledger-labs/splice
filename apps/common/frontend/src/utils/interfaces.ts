import { ContractMetadata } from 'common-protobuf/com/daml/ledger/api/v1/contract_metadata_pb';
import { Identifier, Value } from 'common-protobuf/com/daml/ledger/api/v1/value_pb';
import { Contract as ProtoContract } from 'common-protobuf/com/daml/network/v0/contract_pb';
import { ContractWithState as ProtoContractWithState } from 'common-protobuf/com/daml/network/v0/contract_with_state_pb';
import {
  Contract as OpenAPIContract,
  ContractMetadata as OpenAPIContractMetadata,
} from 'directory-openapi';

import { DisclosedContract } from '@daml/ledger';
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

const toHexString = (bytes: Uint8Array) =>
  bytes.reduce((str, byte) => str + byte.toString(16).padStart(2, '0'), '');

export const decodeProtoMetadata = (metadata: ContractMetadata): OpenAPIContractMetadata => {
  const totalMillis =
    BigInt(metadata.getCreatedAt()!.getSeconds()) * BigInt(1000) +
    BigInt(metadata.getCreatedAt()!.getNanos()) / BigInt(1000_000);
  const micros = (BigInt(metadata.getCreatedAt()!.getNanos()) / BigInt(1000)) % BigInt(1000);
  // JS date APIs only support millisecond precision so we need to do some hackery to get the microseconds in the string.
  const totalMillisFormatted = new Date(Number(totalMillis)).toISOString();
  const createdAt = `${totalMillisFormatted.slice(0, -1)}${String(micros).padStart(3, '0')}Z`;
  return {
    createdAt: createdAt,
    contractKeyHash: toHexString(metadata.getContractKeyHash_asU8()),
    // for some reason the JSON API uses URL-safe base64 encoding
    // https://github.com/openjdk/jdk/blob/0d2196f8e5b03577a14ff97505718f4fa53f3792/src/java.base/share/classes/java/util/Base64.java#L230
    driverMetadata: metadata.getDriverMetadata_asB64().replace(/\+/g, '-').replace(/\//g, '_'),
  };
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
  ): DisclosedContract => ({
    templateId: tmpl.templateId,
    contractId: c.contractId,
    payload: tmpl.encode(c.payload),
    metadata: {
      createdAt: c.metadata.createdAt,
      keyHash: c.metadata.contractKeyHash,
      driverMetadata: c.metadata.driverMetadata,
    },
  }),
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
