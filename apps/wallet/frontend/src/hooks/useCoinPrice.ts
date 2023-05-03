import { useQuery, UseQueryResult } from '@tanstack/react-query';
import BigNumber from 'bignumber.js';
import { useScanClient } from 'common-frontend';

export type CoinPrice = BigNumber | undefined;

export const useCoinPrice: () => UseQueryResult<BigNumber> = () => {
  const { getCoinPrice } = useScanClient();
  return useQuery({
    queryKey: ['coinPrice'],
    queryFn: async () => {
      return await getCoinPrice();
    },
  });
};
