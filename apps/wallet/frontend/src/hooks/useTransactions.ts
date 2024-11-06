// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useInfiniteQuery, UseInfiniteQueryResult } from '@tanstack/react-query';

import { useWalletClient } from '../contexts/WalletServiceContext';
import { Transaction } from '../models/models';

export const useTransactions: () => UseInfiniteQueryResult<Transaction[]> = () => {
  const { listTransactions } = useWalletClient();

  return useInfiniteQuery({
    queryKey: ['transactions'],
    queryFn: async ({ pageParam }) => {
      const txs = await listTransactions(pageParam);
      // react-query requires us to return undefined here to show that no more data is available
      return txs.length === 0 ? undefined : txs;
    },
    getNextPageParam: lastPage => {
      return lastPage && lastPage[lastPage.length - 1].id;
    },
    keepPreviousData: true,
  });
};
