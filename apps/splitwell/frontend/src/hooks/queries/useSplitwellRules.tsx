// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { usePrimaryParty } from 'common-frontend';
import { AssignedContract, PollingStrategy } from 'common-frontend-utils';

import { SplitwellRules } from '@daml.js/splitwell/lib/Splice/Splitwell';

import { useSplitwellClient } from '../../contexts/SplitwellServiceContext';

export const QuerySplitwellRulesOperationName = 'querySplitwellRules';
export const useSplitwellRules = (): UseQueryResult<AssignedContract<SplitwellRules>[]> => {
  const splitwellClient = useSplitwellClient();
  const primaryPartyQuery = usePrimaryParty();
  const primaryPartyId = primaryPartyQuery.data;

  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: [QuerySplitwellRulesOperationName, primaryPartyId],
    queryFn: async () => {
      if (primaryPartyId) {
        const response = await splitwellClient.listSplitwellRules();
        return response.rules.map(c => AssignedContract.decodeAssignedContract(c, SplitwellRules));
      } else {
        return null;
      }
    },
    enabled: !!primaryPartyId,
  });
};
