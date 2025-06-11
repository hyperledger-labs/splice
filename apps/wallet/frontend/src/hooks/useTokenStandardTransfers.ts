// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useQuery, UseQueryResult } from '@tanstack/react-query';

import { AmuletTransferInstruction } from '@daml.js/splice-amulet-0.1.10/lib/Splice/AmuletTransferInstruction';

import { useWalletClient } from '../contexts/WalletServiceContext';

export const useTokenStandardTransfers = (): UseQueryResult<
  Contract<AmuletTransferInstruction>[]
> => {
  const { listTokenStandardTransfers } = useWalletClient();

  return useQuery({
    queryKey: ['listTokenStandardTransfers'],
    queryFn: async () => {
      const { transfers } = await listTokenStandardTransfers();
      return transfers;
    },
  });
};
