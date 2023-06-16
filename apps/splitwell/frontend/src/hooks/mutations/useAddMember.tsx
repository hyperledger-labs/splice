import { UseMutationResult, useMutation, useQueryClient } from '@tanstack/react-query';
import { Contract } from 'common-frontend';

import { AcceptedGroupInvite, SplitwellInstall } from '@daml.js/splitwell/lib/CN/Splitwell';
import { ContractId } from '@daml/types';

import { useSplitwellLedgerApiClient } from '../../contexts/SplitwellLedgerApiContext';
import { getGroups } from '../queries/useGroups';

export const useAddMember = (
  party: string,
  provider: string,
  domainId: string,
  install: ContractId<SplitwellInstall>
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
        install
      );
    },
  });
};
