// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useScanClient } from '@lfdecentralizedtrust/splice-common-frontend/scan-api';
import { useQuery, UseQueryResult } from '@tanstack/react-query';

import { VoteRequest } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';
import { ContractId } from '@daml/types';

export const useVoteRequest = (
  contractId: ContractId<VoteRequest>
): UseQueryResult<Contract<VoteRequest>> => {
  const scanClient = useScanClient();
  return useQuery({
    queryKey: ['listDsoRulesVoteRequests', contractId],
    queryFn: async () =>
      (await scanClient.lookupDsoRulesVoteRequest(contractId)).dso_rules_vote_request,
  });
};
