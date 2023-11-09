import { UseMutationResult, useMutation } from '@tanstack/react-query';
import { CreateDirectoryEntryRequest } from 'directory-external-openapi';

import { SubscriptionRequest } from '@daml.js/wallet-payments/lib/CN/Wallet/Subscriptions';
import { ContractId } from '@daml/types';

import { useExternalDirectoryClient } from '../../context/ValidatorServiceContext';
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
  const directoryApi = useExternalDirectoryClient();

  return useMutation({
    mutationFn: async ({ entryName, suffix }) => {
      const createReq: CreateDirectoryEntryRequest = {
        name: toFullEntryName(entryName, suffix),
        url: '',
        description: '',
      };

      const createRes = await directoryApi.createDirectoryEntry(createReq);
      const subscriptionRequestCid =
        createRes.subscriptionRequestCid as ContractId<SubscriptionRequest>;

      console.debug('Created SubscriptionRequest');
      return subscriptionRequestCid;
    },
  });
};

export default useRequestEntry;
