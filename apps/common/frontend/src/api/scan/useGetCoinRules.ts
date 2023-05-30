import { useQuery, UseQueryResult } from '@tanstack/react-query';

import { CoinRules } from '../../../daml.js/canton-coin-0.1.0/lib/CC/Coin';
import { Contract } from '../../utils';
import { useScanClient } from './ScanClientContext';

const useGetCoinRules = (): UseQueryResult<Contract<CoinRules>> => {
  const scanClient = useScanClient();

  return useQuery({
    queryKey: ['getCoinRules', CoinRules],
    queryFn: async () => {
      const response = await scanClient.getCoinRules({});
      if (!response.coinRulesUpdate.contract) {
        throw new Error(`There was no CoinRules contract in response: ${JSON.stringify(response)}`);
      }
      return Contract.decodeOpenAPI(response.coinRulesUpdate.contract, CoinRules);
    },
  });
};

export default useGetCoinRules;
