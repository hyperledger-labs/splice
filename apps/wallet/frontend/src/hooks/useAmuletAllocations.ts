// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useQuery, UseQueryResult } from '@tanstack/react-query';

import { AmuletAllocation, useWalletClient } from '../contexts/WalletServiceContext';

export const useAmuletAllocations = (): UseQueryResult<Contract<AmuletAllocation>[]> => {
  const { listAmuletAllocations } = useWalletClient();

  return useQuery({
    queryKey: ['listAmuletAllocations'],
    queryFn: async () => {
      return await listAmuletAllocations();
    },
  });
};
