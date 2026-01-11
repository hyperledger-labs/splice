// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  AmuletPriceVote,
  DsoInfo,
  SvVote,
  VotesHooks,
  VotesHooksContext,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { UseQueryResult } from '@tanstack/react-query';
import React, { useMemo } from 'react';

import {
  VoteRequest,
  DsoRules_CloseVoteRequestResult,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';
import { ContractId } from '@daml/types';

import * as svHooks from '../hooks';
import { useDsoInfos as svAppUseDsoInfos } from '../contexts/SvContext';

export const SvAppVotesHooksProvider: React.FC<React.PropsWithChildren> = ({ children }) => {
  const hooks: VotesHooks = useMemo(
    () => ({
      isReadOnly: false,
      useDsoInfos(): UseQueryResult<DsoInfo> {
        return svAppUseDsoInfos();
      },
      useListDsoRulesVoteRequests(): UseQueryResult<Contract<VoteRequest>[]> {
        return svHooks.useListDsoRulesVoteRequests();
      },
      useListVoteRequestResult(
        limit: number,
        actionName: string | undefined,
        requester: string | undefined,
        effectiveFrom: string | undefined,
        effectiveTo: string | undefined,
        accepted: boolean | undefined,
        retry: boolean = true
      ): UseQueryResult<DsoRules_CloseVoteRequestResult[]> {
        return svHooks.useListVoteRequestResult(
          {
            actionName,
            requester,
            effectiveFrom,
            effectiveTo,
            accepted,
          },
          limit,
          retry
        );
      },
      useListVotes(contractIds: ContractId<VoteRequest>[]): UseQueryResult<SvVote[]> {
        return svHooks.useListVotes(contractIds);
      },
      useAmuletPriceVotes(): UseQueryResult<AmuletPriceVote[]> {
        return svHooks.useAmuletPriceVotes();
      },
      useVoteRequest(contractId: ContractId<VoteRequest>): UseQueryResult<Contract<VoteRequest>> {
        return svHooks.useVoteRequest(contractId);
      },
    }),
    []
  );
  return <VotesHooksContext.Provider value={hooks}>{children}</VotesHooksContext.Provider>;
};
