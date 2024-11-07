// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery, UseQueryResult } from '@tanstack/react-query';
// TODO(#7675) - do we need this model?
import { AmuletPriceVote as CPVModel } from 'common-frontend';
import { Contract, PollingStrategy } from 'common-frontend-utils';

import { AmuletPriceVote } from '@daml.js/splice-dso-governance/lib/Splice/DSO/AmuletPrice';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

export const useAmuletPriceVotes = (): UseQueryResult<CPVModel[]> => {
  const { listAmuletPriceVotes } = useSvAdminClient();
  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['listAmuletPriceVotes'],
    queryFn: async () => {
      const { amulet_price_votes } = await listAmuletPriceVotes();
      return amulet_price_votes
        .map(vote => Contract.decodeOpenAPI(vote, AmuletPriceVote))
        .map(vote => {
          return {
            sv: vote.payload.sv,
            amuletPrice: vote.payload.amuletPrice,
            lastUpdatedAt: new Date(vote.payload.lastUpdatedAt),
          };
        });
    },
  });
};
