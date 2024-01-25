import { UseQueryResult } from '@tanstack/react-query';
import BigNumber from 'bignumber.js';
import { Contract } from 'common-frontend';
import { useCoinPriceFromOpenRounds } from 'common-frontend/scan-api';

import { OpenMiningRound } from '@daml.js/canton-coin/lib/CC/Round';

import { useValidatorScanProxyClient } from '../../contexts/ValidatorScanProxyContext';

const useCoinPrice = (): UseQueryResult<BigNumber> => {
  const scanClient = useValidatorScanProxyClient();

  return useCoinPriceFromOpenRounds(() =>
    scanClient
      .getOpenAndIssuingMiningRounds()
      .then(response =>
        response.open_mining_rounds.map(cwt =>
          Contract.decodeOpenAPI(cwt.contract, OpenMiningRound)
        )
      )
  );
};

export default useCoinPrice;
