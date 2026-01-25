// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Contract, ContractWithState } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useQuery, UseQueryResult } from '@tanstack/react-query';

import { AmuletRules } from '@daml.js/splice-amulet/lib/Splice/AmuletRules/';

import { useScanClient } from './ScanClientContext';

const useGetAmuletRules = (): UseQueryResult<ContractWithState<AmuletRules>> => {
  const scanClient = useScanClient();

  return useQuery({
    queryKey: ['scan-api', 'getAmuletRules', AmuletRules],
    queryFn: async () => {
      const response = await scanClient.getAmuletRules({});
      if (!response.amulet_rules_update.contract) {
        throw new Error(
          `There was no AmuletRules contract in response: ${JSON.stringify(response)}`
        );
      }
      const contract = Contract.decodeOpenAPI(response.amulet_rules_update.contract, AmuletRules);
      return { contract, domainId: response.amulet_rules_update.domain_id };
    },
  });
};

export default useGetAmuletRules;
