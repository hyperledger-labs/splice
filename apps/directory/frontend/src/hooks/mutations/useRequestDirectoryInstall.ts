import { UseMutationResult, useMutation, useQueryClient } from '@tanstack/react-query';
import { LedgerApiClient } from 'common-frontend';

import { DirectoryInstallRequest } from '@daml.js/directory/lib/CN/Directory';

import { QueryDirectoryInstallOperationName } from '../queries/useDirectoryInstall';

interface RequestDirectoryInstallArgs {
  primaryPartyId: string;
  providerPartyId: string;
  ledgerApiClient: LedgerApiClient;
}

const useRequestDirectoryInstall: () => UseMutationResult<
  void,
  unknown,
  RequestDirectoryInstallArgs
> = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ primaryPartyId, providerPartyId, ledgerApiClient }) => {
      console.debug('DirectoryInstall not found, creating DirectoryInstallRequest');
      await ledgerApiClient.create([primaryPartyId], DirectoryInstallRequest, {
        user: primaryPartyId,
        provider: providerPartyId,
      });
      console.debug('Created DirectoryInstallRequest');
    },
    onError: (error: unknown) => {
      console.error('Failed to setup install contract: ', error);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [QueryDirectoryInstallOperationName] });
    },
    retry: 3,
    retryDelay: 500,
  });
};

export default useRequestDirectoryInstall;
