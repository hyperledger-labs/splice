// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery, UseQueryResult } from '@tanstack/react-query';

import {
  MintingDelegationWithStatus,
  useWalletClient,
} from '../contexts/WalletServiceContext';

export const useMintingDelegations = (): UseQueryResult<MintingDelegationWithStatus[]> => {
  const { listMintingDelegations } = useWalletClient();

  return useQuery({
    queryKey: ['listMintingDelegations'],
    queryFn: async () => {
      return await listMintingDelegations();
    },
  });
};
