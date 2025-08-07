// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Contract } from '@lfdecentralizedtrust/wallet-openapi';
import { ContractTypeCompanion } from '@daml/types';

let contractIdCounter = 0;

export function mkContract<T extends object, K, I extends string>(
  companion: ContractTypeCompanion<T, K, I>,
  payload: unknown
): Contract {
  return {
    template_id: companion.templateId,
    contract_id: (++contractIdCounter).toString(),
    payload,
    created_event_blob: 'deadbeef',
    created_at: '2024-08-05T13:44:35.878681Z',
  };
}
