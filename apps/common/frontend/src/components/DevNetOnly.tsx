import { UseQueryResult, useQuery } from '@tanstack/react-query';
import React from 'react';

import { CoinRules } from '../../daml.js/canton-coin-0.1.0/lib/CC/Coin';
import { useScanClient } from '../contexts';
import { Contract } from '../utils';

const useGetCoinRules = (): UseQueryResult<Contract<CoinRules>> => {
  const { getCoinRules } = useScanClient();

  return useQuery({
    queryKey: ['getCoinRules'],
    queryFn: async () => {
      return await getCoinRules();
    },
    refetchInterval: false, // No need to refresh: it'll stay the same.
  });
};

export const DevNetOnly: React.FC<{ children: React.ReactElement }> = props => {
  const { data: coinRules, error } = useGetCoinRules();

  if (error) {
    console.error('Failed to resolve isDevNet', error);
    return null;
  }

  const isDevNet = coinRules?.payload.isDevNet;

  if (!isDevNet) {
    return null;
  } else {
    return props.children;
  }
};

export default DevNetOnly;
