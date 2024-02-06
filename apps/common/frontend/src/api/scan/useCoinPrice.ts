import { useQuery, UseQueryResult } from '@tanstack/react-query';
import BigNumber from 'bignumber.js';
import { Contract, PollingStrategy } from 'common-frontend-utils';
import { GetOpenAndIssuingMiningRoundsRequest } from 'scan-openapi';

import { OpenMiningRound } from '@daml.js/canton-coin/lib/CC/Round';

import { useScanClient } from './ScanClientContext';

const useCoinPrice = (): UseQueryResult<BigNumber> => {
  const scanClient = useScanClient();

  const request: GetOpenAndIssuingMiningRoundsRequest = {
    cached_open_mining_round_contract_ids: [],
    cached_issuing_round_contract_ids: [],
  };

  return useCoinPriceFromOpenRounds(() =>
    scanClient.getOpenAndIssuingMiningRounds(request).then(response => {
      return Object.values(response.open_mining_rounds).map(mybCached =>
        Contract.decodeOpenAPI(mybCached.contract!, OpenMiningRound)
      );
    })
  );
};

export function useCoinPriceFromOpenRounds(
  getOpenRounds: () => Promise<Contract<OpenMiningRound>[]>
): UseQueryResult<BigNumber> {
  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['scan-api', 'coinPrice'],
    queryFn: async () => {
      const openOpenRounds = (await getOpenRounds()).filter(
        omr => Date.parse(omr.payload.opensAt) <= Date.now()
      );

      if (openOpenRounds.length > 0) {
        const latestOpenRound = openOpenRounds.reduce((prevOmr, currentOmr) =>
          prevOmr.payload.round.number > currentOmr.payload.round.number ? prevOmr : currentOmr
        );
        return new BigNumber(latestOpenRound.payload.coinPrice);
      } else {
        return new BigNumber(0);
      }
    },
  });
}

export default useCoinPrice;
