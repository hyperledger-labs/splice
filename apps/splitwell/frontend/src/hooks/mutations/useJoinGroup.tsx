// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { UseMutationResult, useMutation } from '@tanstack/react-query';

import { GroupInvite, SplitwellRules } from '@daml.js/splitwell/lib/Splice/Splitwell';

import { useSplitwellLedgerApiClient } from '../../contexts/SplitwellLedgerApiContext';

interface JoinGroupArgs {
  party: string;
  provider: string;
  rules: Contract<SplitwellRules>;
  inviteDomainId: string;
  inviteContract: Contract<GroupInvite>;
}
export const useJoinGroup = (): UseMutationResult<void, unknown, JoinGroupArgs> => {
  const ledgerApiClient = useSplitwellLedgerApiClient();
  return useMutation({
    mutationFn: ({ party, provider, rules, inviteDomainId, inviteContract }) =>
      ledgerApiClient.acceptInvite(
        party,
        provider,
        inviteContract.contractId,
        inviteDomainId,
        rules,
        inviteContract
      ),
  });
};
