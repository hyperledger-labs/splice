import { useQuery, UseQueryResult } from '@tanstack/react-query';

import { CoinRules } from '@daml.js/canton-coin-0.1.0/lib/CC/Coin/';

import { Contract } from '../../utils';
import { useScanClient } from './ScanClientContext';

const useGetCoinRules = (): UseQueryResult<Contract<CoinRules>> => {
  const scanClient = useScanClient();

  return useQuery({
    queryKey: ['scan-api', 'getCoinRules', CoinRules],
    queryFn: async () => {
      const response = await scanClient.getCoinRules({});
      if (!response.coin_rules_update.contract) {
        throw new Error(`There was no CoinRules contract in response: ${JSON.stringify(response)}`);
      }
      return Contract.decodeOpenAPI(response.coin_rules_update.contract, CoinRules);
    },
  });
};

export default useGetCoinRules;
