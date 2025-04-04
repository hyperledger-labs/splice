// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { PollingStrategy } from '@lfdecentralizedtrust/splice-common-frontend-utils';
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
        others: domainsResponse.other_domain_ids,
      };
      console.debug(`Splitwell domains from provider: ${JSON.stringify(domains)}`);
      return domains;
    },
    refetchInterval: PollingStrategy.NONE,
  });
};
