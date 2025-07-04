// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { ContractId } from '@daml/types';
import { useVoteRequest } from './useVoteRequest';
import {
  DsoRules_CloseVoteRequestResult,
  VoteRequest,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { useVotesHooks } from '@lfdecentralizedtrust/splice-common-frontend';
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';

const QUERY_LIMIT = 50;

interface UseVoteRequestResultByCidResult {
  voteRequest: Contract<VoteRequest> | undefined;
  voteResult: DsoRules_CloseVoteRequestResult | undefined;
  hasVoteRequest: boolean;
  hasVoteResult: boolean;
  isPending: boolean;
  isComplete: boolean;
}

//TODO(#1208): Move this logic to the backend and expose via a new endpoint
export function useVoteRequestResultByCid(
  contractId: ContractId<VoteRequest>
): UseVoteRequestResultByCidResult {
  const votesHooks = useVotesHooks();
  const voteRequestQuery = useVoteRequest(contractId, false);

  const voteResultsWithAcceptedQuery = (accepted: boolean) =>
    votesHooks.useListVoteRequestResult(
      QUERY_LIMIT,
      undefined,
      undefined,
      undefined,
      undefined,
      accepted,
      false
    );
  const acceptedResultsQuery = voteResultsWithAcceptedQuery(true);
  const notAcceptedResultsQuery = voteResultsWithAcceptedQuery(false);

  const acceptedResult = acceptedResultsQuery.data?.find(
    vr => vr.request.trackingCid === contractId
  );
  const notAcceptedResult = notAcceptedResultsQuery.data?.find(
    vr => vr.request.trackingCid === contractId
  );

  const hasVoteRequest =
    voteRequestQuery.isSuccess &&
    voteRequestQuery.data != null &&
    voteRequestQuery.data != undefined;

  const hasVoteResult =
    (acceptedResultsQuery.isSuccess && acceptedResult != undefined) ||
    (notAcceptedResultsQuery.isSuccess && notAcceptedResult != undefined);

  const isPending =
    voteRequestQuery.isPending ||
    acceptedResultsQuery.isPending ||
    notAcceptedResultsQuery.isPending;

  const isComplete =
    (voteRequestQuery.isSuccess || voteRequestQuery.isError) &&
    (acceptedResultsQuery.isSuccess || acceptedResultsQuery.isError) &&
    (notAcceptedResultsQuery.isSuccess || notAcceptedResultsQuery.isError);

  const voteRequest = voteRequestQuery.data;
  const voteResult = acceptedResult || notAcceptedResult;

  return {
    voteRequest: voteRequest,
    voteResult: voteResult,
    hasVoteRequest,
    hasVoteResult,
    isPending,
    isComplete,
  };
}
