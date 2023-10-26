import { useQuery, UseQueryResult } from '@tanstack/react-query';
import BigNumber from 'bignumber.js';
import { GetOpenAndIssuingMiningRoundsRequest } from 'scan-openapi';

import { OpenMiningRound } from '@daml.js/canton-coin/lib/CC/Round';

import { Contract, PollingStrategy } from '../../utils';
import { useScanClient } from './ScanClientContext';

const useCoinPrice = (): UseQueryResult<BigNumber> => {
  const scanClient = useScanClient();

  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['scan-api', 'coinPrice'],
    queryFn: async () => {
      const request: GetOpenAndIssuingMiningRoundsRequest = {
        cached_open_mining_round_contract_ids: [],
        cached_issuing_round_contract_ids: [],
      };
      const openAndIssuingMiningRounds = await scanClient.getOpenAndIssuingMiningRounds(request);

      const openOpenRounds = Object.values(openAndIssuingMiningRounds.open_mining_rounds)
        .map(mybCached => Contract.decodeOpenAPI(mybCached.contract!, OpenMiningRound))
        .filter(omr => Date.parse(omr.payload.opensAt) <= Date.now());

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
};

export default useCoinPrice;
