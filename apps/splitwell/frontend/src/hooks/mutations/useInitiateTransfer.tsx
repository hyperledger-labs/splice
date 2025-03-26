// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { UseMutationResult, useMutation, useQueryClient } from '@tanstack/react-query';

import {
  AppPaymentRequest,
  ReceiverAmuletAmount,
} from '@daml.js/splice-wallet-payments/lib/Splice/Wallet/Payment';
import { GroupId, SplitwellRules } from '@daml.js/splitwell/lib/Splice/Splitwell';
import { ContractId } from '@daml/types';

import { useSplitwellLedgerApiClient } from '../../contexts/SplitwellLedgerApiContext';
import { getGroups } from '../queries/useGroups';

export const useInitiateTransfer = (
  party: string,
  provider: string,
  domainId: string,
  rules: Contract<SplitwellRules>
): UseMutationResult<
  ContractId<AppPaymentRequest>,
  unknown,
  { groupId: GroupId; amounts: ReceiverAmuletAmount[] }
> => {
  const ledgerApiClient = useSplitwellLedgerApiClient();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (arg: { groupId: GroupId; amounts: ReceiverAmuletAmount[] }) => {
      const { groupId, amounts } = arg;
      const groups = getGroups(party, queryClient);

      return await ledgerApiClient.initiateTransfer(
        party,
        provider,
        groupId,
        groups,
        amounts,
        domainId,
        rules
      );
    },
  });
};
