// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { UseMutationResult, useMutation } from '@tanstack/react-query';

import { SplitwellRules } from '@daml.js/splitwell/lib/Splice/Splitwell';

import { useSplitwellLedgerApiClient } from '../../contexts/SplitwellLedgerApiContext';

export const useRequestGroup = (
  party: string,
  provider: string,
  dso: string,
  domainId: string,
  rules: Contract<SplitwellRules>
): UseMutationResult<void, unknown, string> => {
  const ledgerApiClient = useSplitwellLedgerApiClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await ledgerApiClient.requestGroup(party, provider, dso, id, domainId, rules);
    },
  });
};
