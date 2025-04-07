// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
// TODO(#7675) - do we need this model?
import { AmuletPriceVote as CPVModel } from '@lfdecentralizedtrust/splice-common-frontend';
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useQuery, UseQueryResult } from '@tanstack/react-query';

import { AmuletPriceVote } from '@daml.js/splice-dso-governance/lib/Splice/DSO/AmuletPrice';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

export const useAmuletPriceVotes = (): UseQueryResult<CPVModel[]> => {
  const { listAmuletPriceVotes } = useSvAdminClient();
  return useQuery({
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
