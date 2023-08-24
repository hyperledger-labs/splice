import { UseMutationResult, useMutation, useQueryClient } from '@tanstack/react-query';
import { Contract } from 'common-frontend';

import { AcceptedGroupInvite, SplitwellRules } from '@daml.js/splitwell/lib/CN/Splitwell';

import { useSplitwellLedgerApiClient } from '../../contexts/SplitwellLedgerApiContext';
import { getGroups } from '../queries/useGroups';

export const useAddMember = (
  party: string,
  provider: string,
  domainId: string,
  rules: Contract<SplitwellRules>
): UseMutationResult<void, unknown, Contract<AcceptedGroupInvite>> => {
  const queryClient = useQueryClient();
  const ledgerApiClient = useSplitwellLedgerApiClient();
  return useMutation({
    mutationFn: async (invite: Contract<AcceptedGroupInvite>) => {
      const groups = getGroups(party, queryClient);
      await ledgerApiClient.joinGroup(
        party,
        provider,
        invite.payload.groupKey.id,
        groups,
        invite.contractId,
        domainId,
        rules
      );
    },
  });
};
