// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { AmuletPriceVote as APVModel } from '@lfdecentralizedtrust/splice-common-frontend';
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useScanClient } from '@lfdecentralizedtrust/splice-common-frontend/scan-api';
import { useQuery, UseQueryResult } from '@tanstack/react-query';

import { AmuletPriceVote } from '@daml.js/splice-dso-governance/lib/Splice/DSO/AmuletPrice';

export const useAmuletPriceVotes = (): UseQueryResult<APVModel[]> => {
  const scanClient = useScanClient();
  return useQuery({
    queryKey: ['listAmuletPriceVotes'],
    queryFn: async () => {
      const { amulet_price_votes } = await scanClient.listAmuletPriceVotes();

      return amulet_price_votes
        .map(v => Contract.decodeOpenAPI(v, AmuletPriceVote))
        .map(vote => ({
          sv: vote.payload.sv,
          amuletPrice: vote.payload.amuletPrice,
          lastUpdatedAt: new Date(vote.payload.lastUpdatedAt),
        }));
    },
  });
};
