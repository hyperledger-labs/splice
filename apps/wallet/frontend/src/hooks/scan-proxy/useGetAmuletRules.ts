// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Contract, ContractWithState } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useQuery, UseQueryResult } from '@tanstack/react-query';

import { AmuletRules } from '@daml.js/splice-amulet/lib/Splice/AmuletRules/';

import { useValidatorScanProxyClient } from '../../contexts/ValidatorScanProxyContext';

const useGetAmuletRules = (): UseQueryResult<ContractWithState<AmuletRules>> => {
  const scanClient = useValidatorScanProxyClient();

  return useQuery({
    queryKey: ['scan-api', 'getAmuletRules', AmuletRules],
    queryFn: async () => {
      const response = await scanClient.getAmuletRules();
      const contract = Contract.decodeOpenAPI(response.amulet_rules.contract, AmuletRules);
      return { contract, domainId: response.amulet_rules.domain_id };
    },
  });
};

export default useGetAmuletRules;
