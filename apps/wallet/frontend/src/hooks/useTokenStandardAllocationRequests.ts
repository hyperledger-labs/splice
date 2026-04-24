// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useQuery, UseQueryResult } from '@tanstack/react-query';

import { useWalletClient, AllocationRequest } from '../contexts/WalletServiceContext';

export const useTokenStandardAllocationRequests = (): UseQueryResult<
  Contract<AllocationRequest>[]
> => {
  const { listAllocationRequests } = useWalletClient();

  return useQuery({
    queryKey: ['listAllocationRequests'],
    queryFn: async () => {
      return await listAllocationRequests();
    },
  });
};
