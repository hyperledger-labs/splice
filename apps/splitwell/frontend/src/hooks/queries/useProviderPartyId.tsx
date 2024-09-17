// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { PollingStrategy } from 'common-frontend-utils';

import { useSplitwellClient } from '../../contexts/SplitwellServiceContext';

export const useProviderPartyId = (): UseQueryResult<string> => {
  const splitwellClient = useSplitwellClient();

  return useQuery({
    refetchInterval: PollingStrategy.NONE,
    queryKey: ['providerPartyId'],
    queryFn: async () => {
      const response = await splitwellClient.getProviderPartyId();
      return response.provider_party_id;
    },
  });
};
