// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { Contract } from 'common-frontend-utils';

import { VoteRequest } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';
import { ContractId } from '@daml/types';

import { useSvAdminClient } from '../contexts/SvAdminServiceContext';

export const useVoteRequest = (
  contractId: ContractId<VoteRequest>
): UseQueryResult<Contract<VoteRequest>> => {
  const { lookupDsoRulesVoteRequest } = useSvAdminClient();
  return useQuery({
    queryKey: ['listDsoRulesVoteRequests', contractId],
    queryFn: async () => (await lookupDsoRulesVoteRequest(contractId)).dso_rules_vote_request,
  });
};
