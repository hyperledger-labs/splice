// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useScanClient } from '@lfdecentralizedtrust/splice-common-frontend/scan-api';
import { useQuery, UseQueryResult } from '@tanstack/react-query';

import {
  VoteRequest,
  DsoRules_CloseVoteRequestResult,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';
import { List } from '@daml/types';

export type ListVoteRequestResultParams = {
  actionName?: string;
  executed?: boolean;
  requester?: string;
  effectiveFrom?: string;
  effectiveTo?: string;
};

export const useListDsoRulesVoteRequests = (): UseQueryResult<Contract<VoteRequest>[]> => {
  const scanClient = useScanClient();
  return useQuery({
    queryKey: ['listDsoRulesVoteRequests'],
    queryFn: async () => {
      const result = await scanClient.listDsoRulesVoteRequests();
      return result.dso_rules_vote_requests.map(c => Contract.decodeOpenAPI(c, VoteRequest));
    },
  });
};

export const useListVoteRequestResult = (
  query: ListVoteRequestResultParams,
  limit: number = 10
): UseQueryResult<DsoRules_CloseVoteRequestResult[]> => {
  const scanClient = useScanClient();
  return useQuery({
    queryKey: ['listVoteRequestResults', DsoRules_CloseVoteRequestResult, limit, query],
    keepPreviousData: true,
    queryFn: async () => {
      const result = await scanClient.listVoteRequestResults({
        ...query,
        limit,
      });
      return List(DsoRules_CloseVoteRequestResult).decoder.runWithException(
        result.dso_rules_vote_results
      );
    },
  });
};
