import { UseMutationResult, useMutation } from '@tanstack/react-query';
import { Contract } from 'common-frontend-utils';

import { SplitwellRules } from '@daml.js/splitwell/lib/CN/Splitwell';

import { useSplitwellLedgerApiClient } from '../../contexts/SplitwellLedgerApiContext';

export const useRequestGroup = (
  party: string,
  provider: string,
  svc: string,
  domainId: string,
  rules: Contract<SplitwellRules>
): UseMutationResult<void, unknown, string> => {
  const ledgerApiClient = useSplitwellLedgerApiClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await ledgerApiClient.requestGroup(party, provider, svc, id, domainId, rules);
    },
  });
};
