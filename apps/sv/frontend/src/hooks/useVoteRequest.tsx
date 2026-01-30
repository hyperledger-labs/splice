// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useQuery, UseQueryResult } from '@tanstack/react-query';

import { VoteRequest } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';
import { ContractId } from '@daml/types';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

export const useVoteRequest = (
  contractId: ContractId<VoteRequest>,
  retry: boolean = true
): UseQueryResult<Contract<VoteRequest>> => {
  const { lookupDsoRulesVoteRequest } = useSvAdminClient();
  return useQuery({
    queryKey: ['listDsoRulesVoteRequests', contractId],
    queryFn: async () => {
      const request = await lookupDsoRulesVoteRequest(contractId);
      return Contract.decodeOpenAPI(request.dso_rules_vote_request, VoteRequest);
    },
    retry,
  });
};
