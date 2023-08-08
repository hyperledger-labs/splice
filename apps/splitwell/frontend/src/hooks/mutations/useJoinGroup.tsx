import { UseMutationResult, useMutation } from '@tanstack/react-query';
import { Contract } from 'common-frontend';

import { GroupInvite, SplitwellInstall } from '@daml.js/splitwell/lib/CN/Splitwell';
import { ContractId } from '@daml/types';

import { useSplitwellLedgerApiClient } from '../../contexts/SplitwellLedgerApiContext';

interface JoinGroupArgs {
  party: string;
  provider: string;
  installContractId: ContractId<SplitwellInstall>;
  inviteDomainId: string;
  inviteContract: Contract<GroupInvite>;
}
export const useJoinGroup = (): UseMutationResult<void, unknown, JoinGroupArgs> => {
  const ledgerApiClient = useSplitwellLedgerApiClient();
  return useMutation({
    mutationFn: ({ party, provider, installContractId, inviteDomainId, inviteContract }) =>
      ledgerApiClient.acceptInvite(
        party,
        provider,
        inviteContract.contractId,
        inviteDomainId,
        installContractId,
        inviteContract
      ),
  });
};
