// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { UseQueryResult } from '@tanstack/react-query';
import { DsoInfo, SvVote, VotesHooks, VotesHooksContext } from 'common-frontend';
import { Contract } from 'common-frontend-utils';
import React from 'react';

import {
  VoteRequest,
  DsoRules_CloseVoteRequestResult,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';
import { ContractId } from '@daml/types';

import * as svHooks from '../hooks';
import { useDsoInfos as svAppUseDsoInfos } from '../contexts/SvContext';

export const SvAppVotesHooksProvider: React.FC<React.PropsWithChildren> = ({ children }) => {
  const hooks: VotesHooks = {
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
      accepted: boolean | undefined
    ): UseQueryResult<DsoRules_CloseVoteRequestResult[]> {
      return svHooks.useListVoteRequestResult(
        {
          actionName,
          requester,
          effectiveFrom,
          effectiveTo,
          accepted,
        },
        limit
      );
    },
    useListVotes(contractIds: ContractId<VoteRequest>[]): UseQueryResult<SvVote[]> {
      return svHooks.useListVotes(contractIds);
    },
    useVoteRequest(contractId: ContractId<VoteRequest>): UseQueryResult<Contract<VoteRequest>> {
      return svHooks.useVoteRequest(contractId);
    },
  };
  return <VotesHooksContext.Provider value={hooks}>{children}</VotesHooksContext.Provider>;
};
