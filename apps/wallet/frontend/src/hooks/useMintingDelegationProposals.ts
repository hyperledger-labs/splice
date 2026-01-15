// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useQuery, UseQueryResult } from '@tanstack/react-query';

import { useWalletClient } from '../contexts/WalletServiceContext';
import { MintingDelegationProposal } from '@daml.js/splice-wallet/lib/Splice/Wallet/MintingDelegation/module';

export const useMintingDelegationProposals = (): UseQueryResult<
  Contract<MintingDelegationProposal>[]
> => {
  const { listMintingDelegationProposals } = useWalletClient();

  return useQuery({
    queryKey: ['listMintingDelegationProposals'],
    queryFn: async () => {
      return await listMintingDelegationProposals();
    },
  });
};
