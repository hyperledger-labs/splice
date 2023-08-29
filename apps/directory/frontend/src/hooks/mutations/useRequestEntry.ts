import { UseMutationResult, useMutation } from '@tanstack/react-query';
import { useLedgerApiClient, usePrimaryParty } from 'common-frontend';

import { DirectoryInstall } from '@daml.js/directory/lib/CN/Directory';
import { SubscriptionRequest } from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Subscriptions';
import { ContractId } from '@daml/types';

import { useDirectoryInstall, useProviderParty } from '..';
import { toFullEntryName } from '../../utils';

interface RequestEntryArgs {
  entryName: string;
  suffix: string;
}
const useRequestEntry = (): UseMutationResult<
  ContractId<SubscriptionRequest>,
  string,
  RequestEntryArgs
> => {
  const ledgerApiClient = useLedgerApiClient();
  const { data: primaryPartyId } = usePrimaryParty();
  const { data: providerPartyId } = useProviderParty();
  const directoryInstall = useDirectoryInstall().data?.contractId;

  return useMutation({
    mutationFn: async ({ entryName, suffix }) => {
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
        // TODO(#6862) pass this value from form data
        { name: toFullEntryName(entryName, suffix), url: '', description: '' }
      );

      console.debug('Created SubscriptionRequest');
      return response._2;
    },
  });
};

export default useRequestEntry;
