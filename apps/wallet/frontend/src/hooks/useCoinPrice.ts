import { useQuery, UseQueryResult } from '@tanstack/react-query';
import BigNumber from 'bignumber.js';
import { useScanClient } from 'common-frontend';

export const useCoinPrice = (): UseQueryResult<BigNumber> => {
  const { getCoinPrice } = useScanClient();
  return useQuery({
    queryKey: ['coinPrice'],
    queryFn: async () => {
      return await getCoinPrice();
    },
    // BigNumber is not a plain object so default structural sharing fails.
    structuralSharing: (oldData, newData) => (oldData && oldData.eq(newData) ? oldData : newData),
  });
};
