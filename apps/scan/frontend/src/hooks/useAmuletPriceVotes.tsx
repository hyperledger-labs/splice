// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { AmuletPriceVote as APVModel } from 'common-frontend';
import { Contract } from 'common-frontend-utils';
import { useScanClient } from 'common-frontend/scan-api';

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
