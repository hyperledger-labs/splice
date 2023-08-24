import { UseMutationResult, useMutation } from '@tanstack/react-query';
import { Contract } from 'common-frontend';

import { GroupInvite, SplitwellRules } from '@daml.js/splitwell/lib/CN/Splitwell';

import { useSplitwellLedgerApiClient } from '../../contexts/SplitwellLedgerApiContext';

interface JoinGroupArgs {
  party: string;
  provider: string;
  rules: Contract<SplitwellRules>;
  inviteDomainId: string;
  inviteContract: Contract<GroupInvite>;
}
export const useJoinGroup = (): UseMutationResult<void, unknown, JoinGroupArgs> => {
  const ledgerApiClient = useSplitwellLedgerApiClient();
  return useMutation({
    mutationFn: ({ party, provider, rules, inviteDomainId, inviteContract }) =>
      ledgerApiClient.acceptInvite(
        party,
        provider,
        inviteContract.contractId,
        inviteDomainId,
        rules,
        inviteContract
      ),
  });
};
