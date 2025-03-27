// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ContractWithState } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useQuery, UseQueryResult } from '@tanstack/react-query';

import { AppPaymentRequest } from '@daml.js/splice-wallet-payments/lib/Splice/Wallet/Payment';

import { useWalletClient } from '../contexts/WalletServiceContext';

export const useAppPaymentRequest = (
  cid: string
): UseQueryResult<ContractWithState<AppPaymentRequest>> => {
  const { getAppPaymentRequest } = useWalletClient();
  return useQuery({
    queryKey: ['appPaymentRequest', cid],
    queryFn: async () => {
      return await getAppPaymentRequest(cid);
    },
    retry: 30,
    retryDelay: 500,
  });
};
