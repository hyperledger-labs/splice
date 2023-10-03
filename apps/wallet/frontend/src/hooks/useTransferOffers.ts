import { useQuery, UseQueryResult } from '@tanstack/react-query';
import BigNumber from 'bignumber.js';
import { Contract, PollingStrategy } from 'common-frontend';
import { TransferOffer } from 'common-frontend/daml.js/wallet-0.1.0/lib/CN/Wallet/TransferOffer/module';

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
