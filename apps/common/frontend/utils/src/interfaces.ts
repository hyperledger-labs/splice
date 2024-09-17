// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Contract as OpenAPIContract } from 'scan-openapi';
import {
  AssignedContract as OpenAPIAssignedContract,
  ContractWithState as OpenAPIContractWithState,
} from 'splitwell-openapi';
import { z } from 'zod';

import { DisclosedContract } from '@daml/ledger';
import { ContractId, ContractTypeCompanion, Serializable, Template } from '@daml/types';

export interface Contract<T> {
  templateId: string;
  contractId: ContractId<T>;
  payload: T;
  createdEventBlob: string;
  createdAt: string;
}

export interface AssignedContract<T> {
  contract: Contract<T>;
  domainId: string;
}

export interface ContractWithState<T> {
  contract: Contract<T>;
  domainId?: string;
}

// eslint-disable-next-line @typescript-eslint/no-redeclare
export const Contract = {
  decodeOpenAPI: <T extends object, K, I extends string>(
    c: OpenAPIContract,
    tmpl: ContractTypeCompanion<T, K, I>
  ): Contract<T> => ({
    templateId: c.template_id,
    contractId: c.contract_id as ContractId<T>,
    payload: tmpl.decoder.runWithException(c.payload),
    createdEventBlob: c.created_event_blob,
    createdAt: c.created_at!,
  }),
  toDisclosedContract: <T extends object, K>(
    tmpl: Template<T, K>,
    c: Contract<T>
  ): DisclosedContract => ({
    templateId: c.templateId,
    contractId: c.contractId,
    createdEventBlob: c.createdEventBlob!,
  }),
  fromUnknown: <T extends object, K, I extends string>(
    data: unknown,
    tmpl: ContractTypeCompanion<T, K, I>
  ): Contract<T> => {
    const payloadSchema = z.custom<T>(val => tmpl.decoder.run(val).ok);
    const contractSchema = z.object({
      templateId: z.string(),
      contractId: z.custom<ContractId<T>>(val => typeof val === 'string'),
      // for some reason, TS cant infer types correctly if we in-line the generic custom payload schema here,
      // so we'll just validate it separately
      payload: z.any(),
      createdEventBlob: z.string(),
      createdAt: z.string(),
    });

    const contract = contractSchema.parse(data);

    return { ...contract, payload: payloadSchema.parse(contract.payload) };
  },
  encode: <T>(tmpl: Serializable<T>, c: Contract<T>): unknown => {
    return {
      ...c,
      payload: tmpl.encode(c.payload),
    };
  },
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
