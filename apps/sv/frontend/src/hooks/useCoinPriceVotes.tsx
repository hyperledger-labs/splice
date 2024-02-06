import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { Contract, PollingStrategy } from 'common-frontend-utils';

import { CoinPriceVote } from '@daml.js/svc-governance/lib/CN/SVC/CoinPrice';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';
// TODO(#7675) - do we need this model?
import { CoinPriceVote as CPVModel } from '../models/models';

export const useCoinPriceVotes = (): UseQueryResult<CPVModel[]> => {
  const { listCoinPriceVotes } = useSvAdminClient();
  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['listCoinPriceVotes'],
    queryFn: async () => {
      const { coin_price_votes } = await listCoinPriceVotes();
      return coin_price_votes
        .map(vote => Contract.decodeOpenAPI(vote, CoinPriceVote))
        .map(vote => {
          return {
            sv: vote.payload.sv,
            coinPrice: vote.payload.coinPrice,
            lastUpdatedAt: new Date(vote.payload.lastUpdatedAt),
          };
        });
    },
  });
};
