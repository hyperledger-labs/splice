import { useQuery, UseQueryResult } from '@tanstack/react-query';

import { useSplitwellClient } from '../../contexts/SplitwellServiceContext';

type SplitwellDomains = {
  preferred: string;
  others: string[];
};
export const useSplitwellDomains = (): UseQueryResult<SplitwellDomains> => {
  const splitwellClient = useSplitwellClient();

  return useQuery({
    queryKey: ['splitwellDomains'],
    queryFn: async () => {
      console.debug('Querying backend for splitwell domain');
      const domainsResponse = await splitwellClient.getSplitwellDomainIds();
      const domains: SplitwellDomains = {
        preferred: domainsResponse.preferred,
        others: domainsResponse.otherDomainIds,
      };
      console.debug(`Splitwell domains from provider: ${JSON.stringify(domains)}`);
      return domains;
    },
    refetchInterval: false,
  });
};
