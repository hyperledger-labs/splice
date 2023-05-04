import { useQuery, UseQueryResult } from '@tanstack/react-query';
import BigNumber from 'bignumber.js';
import { Contract } from 'common-frontend';
import { TransferOffer } from 'common-frontend/daml.js/wallet-0.1.0/lib/CN/Wallet/TransferOffer/module';

import { useWalletClient } from '../contexts/WalletServiceContext';

export const useTransferOffers: (
  coinPrice: BigNumber | undefined
) => UseQueryResult<Contract<TransferOffer>[]> = (coinPrice: BigNumber | undefined) => {
  const { listTransferOffers } = useWalletClient();
  return useQuery({
    queryKey: ['listTransferOffers'],
    queryFn: async () => {
      const { offersList } = await listTransferOffers();
      return offersList;
    },
    enabled: !!coinPrice,
  });
};
