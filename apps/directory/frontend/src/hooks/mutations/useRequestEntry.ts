import { UseMutationResult, useMutation } from '@tanstack/react-query';

import { DirectoryInstall } from '@daml.js/directory/lib/CN/Directory';
import { SubscriptionRequest } from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Subscriptions';
import { ContractId } from '@daml/types';

import { useDirectoryInstall, usePrimaryParty, useProviderParty } from '..';
import { useLedgerApiClient } from '../../contexts/LedgerApiContext';

const useRequestEntry = (): UseMutationResult<ContractId<SubscriptionRequest>, string, string> => {
  const ledgerApiClient = useLedgerApiClient();
  const { data: primaryPartyId } = usePrimaryParty();
  const { data: providerPartyId } = useProviderParty();
  const directoryInstall = useDirectoryInstall().data?.contractId;

  return useMutation({
    mutationFn: async (entryName: string) => {
      if (!ledgerApiClient) {
        throw new Error('No ledgerAPIClient available while requesting entry');
      }
      if (!primaryPartyId) {
        throw new Error('No primary party found while requesting entry');
      }
      if (!providerPartyId) {
        throw new Error('No provider party found while requesting entry');
      }
      if (!directoryInstall) {
        throw new Error('Failed to find DirectoryInstall');
      }

      const response = await ledgerApiClient.exercise(
        [primaryPartyId],
        [],
        DirectoryInstall.DirectoryInstall_RequestEntry,
        directoryInstall,
        { name: entryName }
      );

      console.debug('Created SubscriptionRequest');
      return response._2;
    },
  });
};

export default useRequestEntry;
