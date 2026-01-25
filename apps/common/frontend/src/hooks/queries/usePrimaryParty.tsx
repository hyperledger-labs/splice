// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { PollingStrategy } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { UseQueryResult, useQuery } from '@tanstack/react-query';

import { useLedgerApiClient } from '../../contexts/LedgerApiContext';

const usePrimaryParty = (): UseQueryResult<string> => {
  const ledgerApi = useLedgerApiClient();

  return useQuery({
    queryKey: ['fetchPrimaryParty', ledgerApi],
    refetchInterval: PollingStrategy.NONE, // primary party ID is static
    queryFn: async () => {
      try {
        return ledgerApi!.getPrimaryParty();
      } catch (err) {
        console.error('Error finding primary party for user', err);
        console.error(JSON.stringify(err));
        throw new Error(
          'Error finding primary party for user, please confirm user onboarded to this participant.'
        );
      }
    },
    enabled: !!ledgerApi, // wait for dependencies to be defined
  });
};

export default usePrimaryParty;
