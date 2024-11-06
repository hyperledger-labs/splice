// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery, UseQueryResult } from '@tanstack/react-query';

import { useWalletClient } from '../contexts/WalletServiceContext';
import { WalletSubscription } from '../models/models';

export const useSubscriptions = (): UseQueryResult<WalletSubscription[]> => {
  const { listSubscriptions } = useWalletClient();

  return useQuery({
    queryKey: ['subscriptions'],
    queryFn: async () => {
      return (await listSubscriptions()).subscriptionsList;
    },
  });
};
