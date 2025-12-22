// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery, UseQueryResult } from '@tanstack/react-query';

import {
  MintingDelegationProposalWithStatus,
  useWalletClient,
} from '../contexts/WalletServiceContext';

export const useMintingDelegationProposals = (): UseQueryResult<
  MintingDelegationProposalWithStatus[]
> => {
  const { listMintingDelegationProposals } = useWalletClient();

  return useQuery({
    queryKey: ['listMintingDelegationProposals'],
    queryFn: async () => {
      return await listMintingDelegationProposals();
    },
  });
};
