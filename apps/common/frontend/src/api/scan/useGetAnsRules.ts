// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { GetAnsRulesResponse } from '@lfdecentralizedtrust/scan-openapi';

import { AnsRules } from '@daml.js/ans/lib/Splice/Ans/';

import { useScanClient } from './ScanClientContext';

const useGetAnsRules = (): UseQueryResult<Contract<AnsRules>> => {
  const scanClient = useScanClient();
  return useGetAnsRulesFromResponse(() => scanClient.getAnsRules({}));
};

export function useGetAnsRulesFromResponse(
  getResponse: () => Promise<GetAnsRulesResponse>
): UseQueryResult<Contract<AnsRules>> {
  return useQuery({
    queryKey: ['scan-api', 'getAnsRules', AnsRules],
    queryFn: async () => {
      const response = await getResponse();
      if (!response.ans_rules_update.contract) {
        throw new Error(`There was no AnsRules contract in response: ${JSON.stringify(response)}`);
      }
      return Contract.decodeOpenAPI(response.ans_rules_update.contract, AnsRules);
    },
  });
}

export default useGetAnsRules;
