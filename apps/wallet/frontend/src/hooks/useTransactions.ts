// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { InfiniteData, useInfiniteQuery, UseInfiniteQueryResult } from '@tanstack/react-query';

import { useWalletClient } from '../contexts/WalletServiceContext';
import { Transaction } from '../models/models';
import { usePrimaryParty } from './usePrimaryParty';

export const useTransactions: () => UseInfiniteQueryResult<InfiniteData<Transaction[]>> = () => {
  const { listTransactions } = useWalletClient();
  const primaryPartyId = usePrimaryParty();

  return useInfiniteQuery({
    queryKey: ['transactions', primaryPartyId],
    queryFn: async ({ pageParam }) => listTransactions(pageParam === '' ? undefined : pageParam),
    initialPageParam: '',
    getNextPageParam: lastPage => {
      // returning undefined for an empty page tells react-query that no more data is available
      return lastPage.at(-1)?.id;
    },
  });
};
