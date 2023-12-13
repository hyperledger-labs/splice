import { UseMutationResult, useMutation } from '@tanstack/react-query';
import { CreateCnsEntryRequest } from 'cns-external-openapi';

import { SubscriptionRequest } from '@daml.js/wallet-payments/lib/CN/Wallet/Subscriptions';
import { ContractId } from '@daml/types';

import { useExternalCnsClient } from '../../context/ValidatorServiceContext';
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
  const cnsApi = useExternalCnsClient();

  return useMutation({
    mutationFn: async ({ entryName, suffix }) => {
      const createReq: CreateCnsEntryRequest = {
        name: toFullEntryName(entryName, suffix),
        url: '',
        description: '',
      };

      const createRes = await cnsApi.createCnsEntry(createReq);
      const subscriptionRequestCid =
        createRes.subscriptionRequestCid as ContractId<SubscriptionRequest>;

      console.debug('Created SubscriptionRequest');
      return subscriptionRequestCid;
    },
  });
};

export default useRequestEntry;
