import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { Contract, PollingStrategy } from 'common-frontend-utils';

import { CnsRules } from '@daml.js/cns/lib/CN/Cns/';

import { useScanClient } from './ScanClientContext';

const useGetCnsRules = (): UseQueryResult<Contract<CnsRules>> => {
  const scanClient = useScanClient();

  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['scan-api', 'getCnsRules', CnsRules],
    queryFn: async () => {
      const response = await scanClient.getCnsRules({});
      if (!response.cns_rules_update.contract) {
        throw new Error(`There was no CnsRules contract in response: ${JSON.stringify(response)}`);
      }
      return Contract.decodeOpenAPI(response.cns_rules_update.contract, CnsRules);
    },
  });
};

export default useGetCnsRules;
