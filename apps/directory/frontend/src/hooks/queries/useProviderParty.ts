import { UseQueryResult, useQuery } from '@tanstack/react-query';
import { useDirectoryClient } from 'common-frontend';

const useProviderParty = (): UseQueryResult<string> => {
  const directoryClient = useDirectoryClient();

  return useQuery({
    queryKey: ['fetchProviderParty'],
    refetchInterval: false, // provider party ID is static
    queryFn: async () => {
      return directoryClient.getProviderPartyId().then(response => response.provider_party_id);
    },
  });
};

export default useProviderParty;
