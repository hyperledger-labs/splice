import { useQuery, UseQueryResult } from '@tanstack/react-query';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';
import { CoinPriceVote } from '../models/models';

export const useCoinPriceVotes: () => UseQueryResult<CoinPriceVote[]> = () => {
  const { listCoinPriceVotes } = useSvAdminClient();
  return useQuery({
    queryKey: ['listCoinPriceVotes'],
    queryFn: async () => {
      const { coinPriceVotes } = await listCoinPriceVotes();
      return coinPriceVotes.map(vote => {
        return {
          sv: vote.payload.sv,
          coinPrice: vote.payload.coinPrice,
          lastUpdatedAt: new Date(vote.payload.lastUpdatedAt),
        };
      });
    },
  });
};
