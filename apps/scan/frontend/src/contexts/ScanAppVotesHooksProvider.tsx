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
import React from 'react';

import {
  VoteRequest,
  DsoRules_CloseVoteRequestResult,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';
import { ContractId } from '@daml/types';

import * as scanHooks from '../hooks';

export const ScanAppVotesHooksProvider: React.FC<React.PropsWithChildren> = ({ children }) => {
  const hooks: VotesHooks = {
    isReadOnly: true,
    useDsoInfos(): UseQueryResult<DsoInfo> {
      return scanHooks.useDsoInfos();
    },
    useListDsoRulesVoteRequests(): UseQueryResult<Contract<VoteRequest>[]> {
      return scanHooks.useListDsoRulesVoteRequests();
    },
    useListVoteRequestResult(
      limit: number,
      actionName: string | undefined,
      requester: string | undefined,
      effectiveFrom: string | undefined,
      effectiveTo: string | undefined,
      executed: boolean | undefined
    ): UseQueryResult<DsoRules_CloseVoteRequestResult[]> {
      return scanHooks.useListVoteRequestResult(
        { actionName, requester, effectiveTo, effectiveFrom, executed },
        limit
      );
    },
    useAmuletPriceVotes(): UseQueryResult<AmuletPriceVote[]> {
      return scanHooks.useAmuletPriceVotes();
    },
    useListVotes(contractIds: ContractId<VoteRequest>[]): UseQueryResult<SvVote[]> {
      return scanHooks.useListVotes(contractIds);
    },
    useVoteRequest(contractId: ContractId<VoteRequest>): UseQueryResult<Contract<VoteRequest>> {
      return scanHooks.useVoteRequest(contractId);
    },
  };
  return <VotesHooksContext.Provider value={hooks}>{children}</VotesHooksContext.Provider>;
};
