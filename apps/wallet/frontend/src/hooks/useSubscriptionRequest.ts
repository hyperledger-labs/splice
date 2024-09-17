// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { Contract, PollingStrategy } from 'common-frontend-utils';

import { SubscriptionRequest } from '@daml.js/splice-wallet-payments/lib/Splice/Wallet/Subscriptions';

import { useWalletClient } from '../contexts/WalletServiceContext';

export const useSubscriptionRequest = (
  cid: string
): UseQueryResult<Contract<SubscriptionRequest>> => {
  const { getSubscriptionRequest } = useWalletClient();

  return useQuery({
    refetchInterval: PollingStrategy.NONE,
    queryKey: ['subscriptionRequest', cid],
    queryFn: async () => {
      return await getSubscriptionRequest(cid);
    },
  });
};
