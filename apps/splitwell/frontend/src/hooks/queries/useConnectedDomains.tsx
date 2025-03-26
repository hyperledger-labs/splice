// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { usePrimaryParty } from '@lfdecentralizedtrust/splice-common-frontend';
import { PollingStrategy } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useQuery, UseQueryResult } from '@tanstack/react-query';

import { useSplitwellClient } from '../../contexts/SplitwellServiceContext';

export const useConnectedDomains = (): UseQueryResult<string[] | null> => {
  const splitwellClient = useSplitwellClient();
  const primaryPartyQuery = usePrimaryParty();
  const primaryPartyId = primaryPartyQuery.data;

  return useQuery({
    queryKey: ['connectedDomains', primaryPartyId],
    queryFn: async () => {
      if (primaryPartyId) {
        console.debug('Querying for connected domains');
        const response = await splitwellClient.getConnectedDomains(primaryPartyId);
        const domainIds = response.domain_ids;
        console.debug(`Connected domains: ${domainIds}`);
        return domainIds;
      } else {
        return null;
      }
    },
    refetchInterval: PollingStrategy.NONE,
  });
};
