// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { UseMutationResult, useMutation, useQueryClient } from '@tanstack/react-query';

import { GroupId, SplitwellRules } from '@daml.js/splitwell/lib/Splice/Splitwell';

import { useSplitwellLedgerApiClient } from '../../contexts/SplitwellLedgerApiContext';
import { getGroups } from '../queries/useGroups';

export const useEnterPayment = (
  party: string,
  provider: string,
  domainId: string,
  rules: Contract<SplitwellRules>
): UseMutationResult<
  void,
  unknown,
  { groupId: GroupId; paymentAmount: string; paymentDescription: string }
> => {
  const queryClient = useQueryClient();
  const ledgerApiClient = useSplitwellLedgerApiClient();
  return useMutation({
    mutationFn: async (arg: {
      groupId: GroupId;
      paymentAmount: string;
      paymentDescription: string;
    }) => {
      const { groupId, paymentAmount, paymentDescription } = arg;
      const groups = getGroups(party, queryClient);
      await ledgerApiClient.enterPayment(
        party,
        provider,
        groupId,
        groups,
        paymentAmount,
        paymentDescription,
        domainId,
        rules
      );
    },
  });
};
