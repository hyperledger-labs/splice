import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { Contract, ContractWithState, PollingStrategy } from 'common-frontend';

import { CoinRules } from '@daml.js/canton-coin/lib/CC/CoinRules/';

import { useValidatorScanProxyClient } from '../../contexts/ValidatorScanProxyContext';

const useGetCoinRules = (): UseQueryResult<ContractWithState<CoinRules>> => {
  const scanClient = useValidatorScanProxyClient();

  return useQuery({
    refetchInterval: PollingStrategy.FIXED,
    queryKey: ['scan-api', 'getCoinRules', CoinRules],
    queryFn: async () => {
      const response = await scanClient.getCoinRules();
      const contract = Contract.decodeOpenAPI(response.coin_rules.contract, CoinRules);
      return { contract, domainId: response.coin_rules.domain_id };
    },
  });
};

export default useGetCoinRules;
