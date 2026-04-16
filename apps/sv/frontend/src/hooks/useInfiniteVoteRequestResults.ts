// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { List } from '@daml/types';
import { DsoRules_CloseVoteRequestResult } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';
import { InfiniteData, useInfiniteQuery, UseInfiniteQueryResult } from '@tanstack/react-query';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

export interface VoteRequestResultsPage {
  results: DsoRules_CloseVoteRequestResult[];
  nextPageToken: number | undefined;
}

const PAGE_SIZE = 20;

export const useInfiniteVoteRequestResults = (): UseInfiniteQueryResult<
  InfiniteData<VoteRequestResultsPage>
> => {
  const { listVoteRequestResults } = useSvAdminClient();
  return useInfiniteQuery({
    queryKey: ['infiniteVoteRequestResults', PAGE_SIZE],
    queryFn: async ({ pageParam }) => {
      const response = await listVoteRequestResults(
        PAGE_SIZE,
        undefined,
        undefined,
        undefined,
        undefined,
        undefined,
        pageParam ?? undefined
      );
      const results = List(DsoRules_CloseVoteRequestResult).decoder.runWithException(
        response.dso_rules_vote_results
      );
      return {
        results,
        nextPageToken: response.next_page_token,
      };
    },
    initialPageParam: null as number | null,
    getNextPageParam: lastPage => lastPage?.nextPageToken ?? null,
  });
};
