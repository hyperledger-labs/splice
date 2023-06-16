import { UseMutationResult, useMutation, useQueryClient } from '@tanstack/react-query';

import { GroupId, SplitwellInstall } from '@daml.js/splitwell/lib/CN/Splitwell';
import { ContractId } from '@daml/types';

import { useSplitwellLedgerApiClient } from '../../contexts/SplitwellLedgerApiContext';
import { getGroups } from '../queries/useGroups';

export const useEnterPayment = (
  party: string,
  provider: string,
  domainId: string,
  install: ContractId<SplitwellInstall>
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
        install
      );
    },
  });
};
