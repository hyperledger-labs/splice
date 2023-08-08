import { useQuery, UseQueryResult } from '@tanstack/react-query';

import { useSplitwellClient } from '../../contexts/SplitwellServiceContext';

export const useProviderPartyId = (): UseQueryResult<string> => {
  const splitwellClient = useSplitwellClient();

  return useQuery({
    queryKey: ['providerPartyId'],
    queryFn: async () => {
      const response = await splitwellClient.getProviderPartyId();
      return response.providerPartyId;
    },
  });
};
