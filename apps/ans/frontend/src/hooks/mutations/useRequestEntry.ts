// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { UseMutationResult, useMutation } from '@tanstack/react-query';
import { CreateAnsEntryRequest } from 'ans-external-openapi';

import { SubscriptionRequest } from '@daml.js/splice-wallet-payments/lib/Splice/Wallet/Subscriptions';
import { ContractId } from '@daml/types';

import { useExternalAnsClient } from '../../context/AnsServiceContext';
import { toFullEntryName } from '../../utils';

interface RequestEntryArgs {
  entryName: string;
  suffix: string;
}
const useRequestEntry = (): UseMutationResult<
  ContractId<SubscriptionRequest>,
  string,
  RequestEntryArgs
> => {
  const ansApi = useExternalAnsClient();

  return useMutation({
    mutationFn: async ({ entryName, suffix }) => {
      const createReq: CreateAnsEntryRequest = {
        name: toFullEntryName(entryName, suffix),
        url: '',
        description: '',
      };

      const createRes = await ansApi.createAnsEntry(createReq);
      const subscriptionRequestCid =
        createRes.subscriptionRequestCid as ContractId<SubscriptionRequest>;

      console.debug('Created SubscriptionRequest');
      return subscriptionRequestCid;
    },
  });
};

export default useRequestEntry;
