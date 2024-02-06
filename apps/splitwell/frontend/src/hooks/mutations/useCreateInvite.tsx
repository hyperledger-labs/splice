import { UseMutationResult, useMutation, useQueryClient } from '@tanstack/react-query';
import { Contract } from 'common-frontend-utils';

import { GroupId, SplitwellRules } from '@daml.js/splitwell/lib/CN/Splitwell';

import { useSplitwellLedgerApiClient } from '../../contexts/SplitwellLedgerApiContext';
import { getGroups } from '../queries/useGroups';

export const useCreateInvite = (
  party: string,
  provider: string,
  domainId: string,
  rules: Contract<SplitwellRules>
): UseMutationResult<void, unknown, GroupId> => {
  const queryClient = useQueryClient();
  const ledgerApiClient = useSplitwellLedgerApiClient();
  return useMutation({
    mutationFn: async (groupId: GroupId) => {
      const groups = getGroups(party, queryClient);
      await ledgerApiClient.createGroupInvite(party, provider, groupId, groups, domainId, rules);
    },
  });
};
