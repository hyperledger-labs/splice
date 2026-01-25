// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { GetOpenAndIssuingMiningRoundsRequest } from '@lfdecentralizedtrust/scan-openapi';

import { OpenMiningRound } from '@daml.js/splice-amulet/lib/Splice/Round';

import { useScanClient } from './ScanClientContext';

const useOpenRounds = (): UseQueryResult<Contract<OpenMiningRound>[]> => {
  const scanClient = useScanClient();

  const request: GetOpenAndIssuingMiningRoundsRequest = {
    cached_open_mining_round_contract_ids: [],
    cached_issuing_round_contract_ids: [],
  };

  return useQueryFromOpenRounds(() =>
    scanClient.getOpenAndIssuingMiningRounds(request).then(response => {
      return Object.values(response.open_mining_rounds).map(mybCached =>
        Contract.decodeOpenAPI(mybCached.contract!, OpenMiningRound)
      );
    })
  );
};

function useQueryFromOpenRounds(
  getOpenRounds: () => Promise<Contract<OpenMiningRound>[]>
): UseQueryResult<Contract<OpenMiningRound>[]> {
  return useQuery({
    queryKey: ['scan-api', 'openRounds'],
    queryFn: async () => {
      const openOpenRounds = (await getOpenRounds()).filter(
        omr => Date.parse(omr.payload.opensAt) <= Date.now()
      );

      return openOpenRounds;
    },
  });
}

export default useOpenRounds;
