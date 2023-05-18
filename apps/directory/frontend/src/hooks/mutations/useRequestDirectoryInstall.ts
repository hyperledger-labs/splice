import { UseMutationResult, useMutation } from '@tanstack/react-query';

import { DirectoryInstallRequest } from '@daml.js/directory/lib/CN/Directory';

import { LedgerApiClient } from '../../contexts/LedgerApiContext';

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
  });
};

export default useRequestDirectoryInstall;
