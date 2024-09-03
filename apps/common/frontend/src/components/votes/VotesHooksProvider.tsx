// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { UseQueryResult } from '@tanstack/react-query';
import React, { useContext } from 'react';

import { ContractId } from '@daml/types';

import {
  DsoRules_CloseVoteRequestResult,
  VoteRequest,
} from '../../../daml.js/splice-dso-governance-0.1.8/lib/Splice/DsoRules';
import { Contract } from '../../../utils';
import { SvVote } from '../../models';
import { DsoInfo } from '../Dso';

export const VotesHooksContext = React.createContext<VotesHooks | undefined>(undefined);

export interface BaseVotesHooks {
  isReadOnly: boolean;
  useListDsoRulesVoteRequests: () => UseQueryResult<Contract<VoteRequest>[]>;
  useListVoteRequestResult: (
    limit: number,
    actionName?: string,
    requester?: string,
    effectiveFrom?: string,
    effectiveTo?: string,
    executed?: boolean
  ) => UseQueryResult<DsoRules_CloseVoteRequestResult[]>;
  useListVotes: (contractIds: ContractId<VoteRequest>[]) => UseQueryResult<SvVote[]>;
  useDsoInfos: () => UseQueryResult<DsoInfo>;
  useVoteRequest: (contractId: ContractId<VoteRequest>) => UseQueryResult<Contract<VoteRequest>>;
}

export type VotesHooks = BaseVotesHooks;

export const useVotesHooks: () => VotesHooks = () => {
  const hooks = useContext<VotesHooks | undefined>(VotesHooksContext);
  if (!hooks) {
    throw new Error('Votes hooks not initialized');
  }
  return hooks;
};
