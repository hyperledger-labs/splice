import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { PollingStrategy } from 'common-frontend-utils';

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
        others: domainsResponse.other_domain_ids,
      };
      console.debug(`Splitwell domains from provider: ${JSON.stringify(domains)}`);
      return domains;
    },
    refetchInterval: PollingStrategy.NONE,
  });
};
