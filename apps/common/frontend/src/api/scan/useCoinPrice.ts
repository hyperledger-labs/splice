import { useQuery, UseQueryResult } from '@tanstack/react-query';
import BigNumber from 'bignumber.js';
import { GetOpenAndIssuingMiningRoundsRequest } from 'scan-openapi';

import { OpenMiningRound } from '../../../daml.js/canton-coin-0.1.0/lib/CC/Round';
import { Contract } from '../../utils';
import { useScanClient } from './ScanClientContext';

const useCoinPrice = (): UseQueryResult<BigNumber> => {
  const scanClient = useScanClient();

  return useQuery({
    queryKey: ['coinPrice'],
    queryFn: async () => {
      const request: GetOpenAndIssuingMiningRoundsRequest = {
        cachedOpenMiningRoundContractIds: [],
        cachedIssuingRoundContractIds: [],
      };
      const openAndIssuingMiningRounds = await scanClient.getOpenAndIssuingMiningRounds(request);

      const openOpenRounds = Object.values(openAndIssuingMiningRounds.openMiningRounds)
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
    // BigNumber is not a plain object so default structural sharing fails.
    structuralSharing: (oldData, newData) => (oldData && oldData.eq(newData) ? oldData : newData),
  });
};

export default useCoinPrice;
