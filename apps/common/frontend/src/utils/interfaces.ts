import {
  AssignedContract as OpenAPIAssignedContract,
  Contract as OpenAPIContract,
  ContractWithState as OpenAPIContractWithState,
} from 'splitwell-openapi';

import { DisclosedContract } from '@daml/ledger';
import { ContractId, ContractTypeCompanion, Template } from '@daml/types';

// The generated OpenAPI def is a class which doesn't match react-query’s default
// structural sharing logic so we define our own.
export interface ContractMetadata {
  createdAt: string;
  keyHash: string;
  driverMetadata: string;
}

export interface Contract<T> {
  contractId: ContractId<T>;
  payload: T;
  metadata: ContractMetadata;
}

export interface AssignedContract<T> {
  contract: Contract<T>;
  domainId: string;
}

// eslint-disable-next-line @typescript-eslint/no-redeclare
export const Contract = {
  // TODO(#7670) - try to improve type safety of openAPI contract decoding
  decodeOpenAPI: <T extends object, K, I extends string>(
    c: OpenAPIContract,
    tmpl: ContractTypeCompanion<T, K, I>
  ): Contract<T> => ({
    contractId: c.contract_id as ContractId<T>,
    payload: tmpl.decoder.runWithException(c.payload),
    metadata: {
      createdAt: c.metadata.createdAt,
      keyHash: c.metadata.contractKeyHash,
      driverMetadata: c.metadata.driverMetadata,
    },
  }),
  toDisclosedContract: <T extends object, K>(
    tmpl: Template<T, K>,
    c: Contract<T>
  ): DisclosedContract => ({
    templateId: tmpl.templateId,
    contractId: c.contractId,
    payload: tmpl.encode(c.payload),
    metadata: c.metadata,
  }),
  // TODO(#7670) -- trust that our json string is valid, perhaps eventually use zod to validate
  fromJsonString: <T extends object>(j: string): Contract<T> => JSON.parse(j),
};

// eslint-disable-next-line @typescript-eslint/no-redeclare
export const AssignedContract = {
  // undefined if c is in-flight between domains
  decodeContractWithState<T extends object, K>(
    cws: OpenAPIContractWithState,
    tmpl: Template<T, K>
  ): AssignedContract<T> | undefined {
    const c = cws.contract;
    const domainId = cws.domain_id;
    return c && domainId ? { contract: Contract.decodeOpenAPI(c, tmpl), domainId } : undefined;
  },

  decodeAssignedContract<T extends object, K>(
    contract: OpenAPIAssignedContract,
    tmpl: Template<T, K>
  ): AssignedContract<T> {
    return {
      contract: Contract.decodeOpenAPI(contract.contract, tmpl),
      domainId: contract.domain_id,
    };
  },
};
