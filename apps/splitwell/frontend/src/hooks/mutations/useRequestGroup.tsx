import { UseMutationResult, useMutation } from '@tanstack/react-query';

import { SplitwellInstall } from '@daml.js/splitwell/lib/CN/Splitwell';
import { ContractId } from '@daml/types';

import { useSplitwellLedgerApiClient } from '../../contexts/SplitwellLedgerApiContext';

export const useRequestGroup = (
  party: string,
  provider: string,
  svc: string,
  domainId: string,
  install: ContractId<SplitwellInstall>
): UseMutationResult<void, unknown, string> => {
  const ledgerApiClient = useSplitwellLedgerApiClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await ledgerApiClient.requestGroup(party, provider, svc, id, domainId, install);
    },
  });
};
