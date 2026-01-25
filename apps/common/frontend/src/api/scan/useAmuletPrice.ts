// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import BigNumber from 'bignumber.js';
import { GetOpenAndIssuingMiningRoundsRequest } from '@lfdecentralizedtrust/scan-openapi';

import { OpenMiningRound } from '@daml.js/splice-amulet/lib/Splice/Round';

import { useScanClient } from './ScanClientContext';

const useAmuletPrice = (): UseQueryResult<BigNumber> => {
  const scanClient = useScanClient();

  const request: GetOpenAndIssuingMiningRoundsRequest = {
    cached_open_mining_round_contract_ids: [],
    cached_issuing_round_contract_ids: [],
  };

  return useAmuletPriceFromOpenRounds(() =>
    scanClient.getOpenAndIssuingMiningRounds(request).then(response => {
      return Object.values(response.open_mining_rounds).map(mybCached =>
        Contract.decodeOpenAPI(mybCached.contract!, OpenMiningRound)
      );
    })
  );
};

export function useAmuletPriceFromOpenRounds(
  getOpenRounds: () => Promise<Contract<OpenMiningRound>[]>
): UseQueryResult<BigNumber> {
  return useQuery({
    queryKey: ['scan-api', 'amuletPrice'],
    queryFn: async () => {
      const openOpenRounds = (await getOpenRounds()).filter(
        omr => Date.parse(omr.payload.opensAt) <= Date.now()
      );

      if (openOpenRounds.length > 0) {
        const latestOpenRound = openOpenRounds.reduce((prevOmr, currentOmr) =>
          prevOmr.payload.round.number > currentOmr.payload.round.number ? prevOmr : currentOmr
        );
        return new BigNumber(latestOpenRound.payload.amuletPrice);
      } else {
        return new BigNumber(0);
      }
    },
  });
}

export default useAmuletPrice;
