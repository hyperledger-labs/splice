import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { Contract, ContractWithState, PollingStrategy } from 'common-frontend-utils';

import { CoinRules } from '@daml.js/canton-coin/lib/CC/CoinRules/';

import { useScanClient } from './ScanClientContext';

const useGetCoinRules = (): UseQueryResult<ContractWithState<CoinRules>> => {
  const scanClient = useScanClient();

  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['scan-api', 'getCoinRules', CoinRules],
    queryFn: async () => {
      const response = await scanClient.getCoinRules({});
      if (!response.coin_rules_update.contract) {
        throw new Error(`There was no CoinRules contract in response: ${JSON.stringify(response)}`);
      }
      const contract = Contract.decodeOpenAPI(response.coin_rules_update.contract, CoinRules);
      return { contract, domainId: response.coin_rules_update.domain_id };
    },
  });
};

export default useGetCoinRules;
