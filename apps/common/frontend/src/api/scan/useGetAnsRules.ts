import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { Contract, PollingStrategy } from 'common-frontend-utils';

import { AnsRules } from '@daml.js/ans/lib/Splice/Ans/';

import { useScanClient } from './ScanClientContext';

const useGetAnsRules = (): UseQueryResult<Contract<AnsRules>> => {
  const scanClient = useScanClient();

  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['scan-api', 'getAnsRules', AnsRules],
    queryFn: async () => {
      const response = await scanClient.getAnsRules({});
      if (!response.ans_rules_update.contract) {
        throw new Error(`There was no AnsRules contract in response: ${JSON.stringify(response)}`);
      }
      return Contract.decodeOpenAPI(response.ans_rules_update.contract, AnsRules);
    },
  });
};

export default useGetAnsRules;
