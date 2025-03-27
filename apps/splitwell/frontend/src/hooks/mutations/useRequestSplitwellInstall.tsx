// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useMutation, UseMutationResult, useQueryClient } from '@tanstack/react-query';

import { SplitwellRules } from '@daml.js/splitwell/lib/Splice/Splitwell';

import { useSplitwellLedgerApiClient } from '../../contexts/SplitwellLedgerApiContext';
import { QuerySplitwellInstallOperationName } from '../queries/useSplitwellInstall';

interface RequestSplitwellInstallArgs {
  primaryPartyId: string;
  domainId: string;
  rules: Contract<SplitwellRules>;
}
export const useRequestSplitwellInstall = (): UseMutationResult<
  void,
  unknown,
  RequestSplitwellInstallArgs
> => {
  const queryClient = useQueryClient();
  const ledgerApiClient = useSplitwellLedgerApiClient();
  return useMutation({
    mutationFn: async ({ primaryPartyId, domainId, rules }) => {
      console.debug('SplitwellInstall not found, creating SplitwellInstallRequest');
      await ledgerApiClient.requestSplitwellInstall(primaryPartyId, domainId, rules);
      console.debug('Created SplitwellInstallRequest');
    },
    onError: (error: unknown) => {
      console.error('Failed to setup install contract: ', JSON.stringify(error));
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: [QuerySplitwellInstallOperationName] });
    },
  });
};
