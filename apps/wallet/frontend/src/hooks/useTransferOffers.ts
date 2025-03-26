// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import BigNumber from 'bignumber.js';

import { TransferOffer } from '@daml.js/splice-wallet/lib/Splice/Wallet/TransferOffer/module';

import { useWalletClient } from '../contexts/WalletServiceContext';

export const useTransferOffers = (
  amuletPrice?: BigNumber
): UseQueryResult<Contract<TransferOffer>[]> => {
  const { listTransferOffers } = useWalletClient();

  return useQuery({
    queryKey: ['listTransferOffers'],
    queryFn: async () => {
      const { offersList } = await listTransferOffers();
      return offersList;
    },
    enabled: !!amuletPrice,
  });
};
