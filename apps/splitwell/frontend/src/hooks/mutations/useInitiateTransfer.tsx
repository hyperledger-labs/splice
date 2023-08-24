import { UseMutationResult, useMutation, useQueryClient } from '@tanstack/react-query';
import { Contract } from 'common-frontend';

import { GroupId, SplitwellRules } from '@daml.js/splitwell/lib/CN/Splitwell';
import {
  AppPaymentRequest,
  ReceiverCCAmount,
} from '@daml.js/wallet-payments/lib/CN/Wallet/Payment';
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
  { groupId: GroupId; amounts: ReceiverCCAmount[] }
> => {
  const ledgerApiClient = useSplitwellLedgerApiClient();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (arg: { groupId: GroupId; amounts: ReceiverCCAmount[] }) => {
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
