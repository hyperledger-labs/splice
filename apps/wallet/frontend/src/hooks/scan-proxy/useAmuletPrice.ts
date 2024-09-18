// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { UseQueryResult } from '@tanstack/react-query';
import BigNumber from 'bignumber.js';
import { Contract } from 'common-frontend-utils';
import { useAmuletPriceFromOpenRounds } from 'common-frontend/scan-api';

import { OpenMiningRound } from '@daml.js/splice-amulet/lib/Splice/Round';

import { useValidatorScanProxyClient } from '../../contexts/ValidatorScanProxyContext';

const useAmuletPrice = (): UseQueryResult<BigNumber> => {
  const scanClient = useValidatorScanProxyClient();

  return useAmuletPriceFromOpenRounds(() =>
    scanClient
      .getOpenAndIssuingMiningRounds()
      .then(response =>
        response.open_mining_rounds.map(cwt =>
          Contract.decodeOpenAPI(cwt.contract, OpenMiningRound)
        )
      )
  );
};

export default useAmuletPrice;
