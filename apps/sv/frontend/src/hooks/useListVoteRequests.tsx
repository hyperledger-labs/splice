// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { List } from '@daml/types';
import {
  DsoRules_CloseVoteRequestResult,
  VoteRequest,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { type UseQueryResult, useQuery } from '@tanstack/react-query';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';
import { useConfigPollInterval } from '../utils';

export type ListVoteRequestResultParams = {
  actionName?: string;
  accepted?: boolean;
  requester?: string;
  effectiveFrom?: string;
  effectiveTo?: string;
};

export const useListDsoRulesVoteRequests = (
  refetchInterval?: number
): UseQueryResult<Contract<VoteRequest>[]> => {
  const { listDsoRulesVoteRequests } = useSvAdminClient();
  const defaultRefetchInterval = useConfigPollInterval();

  return useQuery({
    queryKey: ['listDsoRulesVoteRequests'],
    queryFn: async () => {
      const { dso_rules_vote_requests } = await listDsoRulesVoteRequests();
      return dso_rules_vote_requests.map(c => Contract.decodeOpenAPI(c, VoteRequest));
    },
    refetchInterval: refetchInterval ?? defaultRefetchInterval,
  });
};

export const useListVoteRequestResult = (
  query: ListVoteRequestResultParams,
  limit: number = 10,
  retry: boolean = true
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
    retry,
  });
};
