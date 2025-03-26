// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useQuery, UseQueryResult } from '@tanstack/react-query';

import {
  VoteRequest,
  DsoRules_CloseVoteRequestResult,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';
import { List } from '@daml/types';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

export type ListVoteRequestResultParams = {
  actionName?: string;
  accepted?: boolean;
  requester?: string;
  effectiveFrom?: string;
  effectiveTo?: string;
};

export const useListDsoRulesVoteRequests = (): UseQueryResult<Contract<VoteRequest>[]> => {
  const { listDsoRulesVoteRequests } = useSvAdminClient();
  return useQuery({
    queryKey: ['listDsoRulesVoteRequests'],
    queryFn: async () => {
      const { dso_rules_vote_requests } = await listDsoRulesVoteRequests();
      return dso_rules_vote_requests.map(c => Contract.decodeOpenAPI(c, VoteRequest));
    },
  });
};

export const useListVoteRequestResult = (
  query: ListVoteRequestResultParams,
  limit: number = 10
): UseQueryResult<DsoRules_CloseVoteRequestResult[]> => {
  const { listVoteRequestResults } = useSvAdminClient();
  return useQuery({
    queryKey: [
      'listVoteRequestResults',
      DsoRules_CloseVoteRequestResult,
      limit,
      query.actionName,
      query.accepted,
      query.requester,
      query.effectiveFrom,
      query.effectiveTo,
    ],
    keepPreviousData: true,
    queryFn: async () => {
      const { dso_rules_vote_results } = await listVoteRequestResults(
        limit,
        query.actionName,
        query.requester,
        query.effectiveFrom,
        query.effectiveTo,
        query.accepted
      );
      return List(DsoRules_CloseVoteRequestResult).decoder.runWithException(dso_rules_vote_results);
    },
  });
};
