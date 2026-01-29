// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { usePrimaryParty } from '@lfdecentralizedtrust/splice-common-frontend';
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { SplitwellInstall } from '@lfdecentralizedtrust/splitwell-openapi';

import { useSplitwellClient } from '../../contexts/SplitwellServiceContext';
import { useConnectedDomains } from './useConnectedDomains';
import { useSplitwellDomains } from './useSplitwellDomains';

export const QuerySplitwellInstallOperationName = 'querySplitwellInstalls';
export const useSplitwellInstalls = (): UseQueryResult<SplitwellInstall[] | null> => {
  const splitwellClient = useSplitwellClient();
  const primaryPartyQuery = usePrimaryParty();
  const primaryPartyId = primaryPartyQuery.data;
  const splitwellDomainIds = useSplitwellDomains().data;
  const connectedDomainIds = useConnectedDomains().data;

  return useQuery({
    queryKey: [QuerySplitwellInstallOperationName, primaryPartyId],
    queryFn: async () => {
      if (primaryPartyId) {
        const response = await splitwellClient.listSplitwellInstalls(primaryPartyId);
        return response.installs;
      } else {
        return null;
      }
    },
    refetchInterval: query => {
      const data = query.state.data;
      if (data !== null && data !== undefined && connectedDomainIds && splitwellDomainIds) {
        const connectedSplitwellDomainIds = connectedDomainIds.filter(
          d => splitwellDomainIds.preferred === d || splitwellDomainIds.others.includes(d)
        );
        const connectedDomainsWithoutInstall = connectedSplitwellDomainIds.filter(
          connected => !data.find(installed => connected === installed.domain_id)
        );
        if (connectedDomainsWithoutInstall.length === 0) {
          console.debug(
            'useSplitwellInstalls: all connected splitwell domains have an install contract, stopping'
          );
          return false;
        } else {
          console.debug(
            `useSplitwellInstalls: splitwell domains ${connectedDomainsWithoutInstall} still do not have an install contract, refetching`
          );
          return 500;
        }
      } else if (query.state.status === 'error') {
        console.debug('useSplitwellInstalls: query error, stopping');
        return false;
      } else {
        console.debug('useSplitwellInstalls: no data, refetching');
        return 500;
      }
    },
    enabled: !!primaryPartyId && !!connectedDomainIds && !!splitwellDomainIds,
  });
};
