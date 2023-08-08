import { useMutation, UseMutationResult, useQueryClient } from '@tanstack/react-query';
import { LedgerApiClient } from 'common-frontend';

import { SplitwellInstallRequest } from '@daml.js/splitwell/lib/CN/Splitwell';

import { QuerySplitwellInstallOperationName } from '../queries/useSplitwellInstall';

interface RequestSplitwellInstallArgs {
  ledgerApiClient: LedgerApiClient;
  primaryPartyId: string;
  providerPartyId: string;
  domainId: string;
}
export const useRequestSplitwellInstall = (): UseMutationResult<
  void,
  unknown,
  RequestSplitwellInstallArgs
> => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ primaryPartyId, providerPartyId, domainId, ledgerApiClient }) => {
      console.debug('SplitwellInstall not found, creating SplitwellInstallRequest');
      await ledgerApiClient.create(
        [primaryPartyId],
        SplitwellInstallRequest,
        {
          user: primaryPartyId,
          provider: providerPartyId,
        },
        domainId
      );
      console.debug('Created SplitwellInstallRequest');
    },
    onError: (error: unknown) => {
      console.error('Failed to setup install contract: ', JSON.stringify(error));
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [QuerySplitwellInstallOperationName] });
    },
  });
};
