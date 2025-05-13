// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { InfiniteData, useInfiniteQuery, UseInfiniteQueryResult } from '@tanstack/react-query';

import { useWalletClient } from '../contexts/WalletServiceContext';
import { Transaction } from '../models/models';

export const useTransactions: () => UseInfiniteQueryResult<InfiniteData<Transaction[]>> = () => {
  const { listTransactions } = useWalletClient();

  return useInfiniteQuery({
    queryKey: ['transactions'],
    queryFn: async ({ pageParam }) => {
      const txs = await listTransactions(pageParam === '' ? undefined : pageParam);
      // react-query requires us to return undefined here to show that no more data is available
      return txs.length === 0 ? undefined : txs;
    },
    initialPageParam: '',
    getNextPageParam: lastPage => {
      return lastPage && lastPage[lastPage.length - 1].id;
    },
  });
};
