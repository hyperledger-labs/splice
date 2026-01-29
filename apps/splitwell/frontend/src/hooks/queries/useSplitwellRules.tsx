// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { usePrimaryParty } from '@lfdecentralizedtrust/splice-common-frontend';
import { AssignedContract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useQuery, UseQueryResult } from '@tanstack/react-query';

import { SplitwellRules } from '@daml.js/splitwell/lib/Splice/Splitwell';

import { useSplitwellClient } from '../../contexts/SplitwellServiceContext';

export const QuerySplitwellRulesOperationName = 'querySplitwellRules';
export const useSplitwellRules = (): UseQueryResult<AssignedContract<SplitwellRules>[] | null> => {
  const splitwellClient = useSplitwellClient();
  const primaryPartyQuery = usePrimaryParty();
  const primaryPartyId = primaryPartyQuery.data;

  return useQuery({
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
