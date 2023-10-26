import { useQuery, UseQueryResult } from '@tanstack/react-query';
import BigNumber from 'bignumber.js';
import { Contract, PollingStrategy } from 'common-frontend';

import { TransferOffer } from '@daml.js/wallet/lib/CN/Wallet/TransferOffer/module';

import { useWalletClient } from '../contexts/WalletServiceContext';

export const useTransferOffers = (
  coinPrice?: BigNumber
): UseQueryResult<Contract<TransferOffer>[]> => {
  const { listTransferOffers } = useWalletClient();
  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['listTransferOffers'],
    queryFn: async () => {
      const { offersList } = await listTransferOffers();
      return offersList;
    },
    enabled: !!coinPrice,
  });
};
