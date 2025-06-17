import { ContractId } from '@daml/types';
import { useVoteRequest } from './useVoteRequest';
import { VoteRequest } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { useVotesHooks } from '@lfdecentralizedtrust/splice-common-frontend';

const QUERY_LIMIT = 50;

//TODO: Add an issue to move this to the backend!
export const useVoteRequestResultByCid = (contractId: ContractId<VoteRequest>) => {
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

  const hasVoteRequest =
    voteRequestQuery.isSuccess &&
    voteRequestQuery.data != null &&
    voteRequestQuery.data != undefined;

  const hasVoteResult =
    (acceptedResultsQuery.isSuccess &&
      acceptedResultsQuery.data.length > 0 &&
      acceptedResultsQuery.data != null &&
      acceptedResultsQuery.data != undefined) ||
    (notAcceptedResultsQuery.isSuccess &&
      notAcceptedResultsQuery.data.length > 0 &&
      notAcceptedResultsQuery.data != null &&
      notAcceptedResultsQuery.data != undefined);

  const isPending =
    voteRequestQuery.isPending ||
    acceptedResultsQuery.isPending ||
    notAcceptedResultsQuery.isPending;

  const isComplete =
    (voteRequestQuery.isSuccess || voteRequestQuery.isError) &&
    (acceptedResultsQuery.isSuccess || acceptedResultsQuery.isError) &&
    (notAcceptedResultsQuery.isSuccess || notAcceptedResultsQuery.isError);

  const voteRequest = voteRequestQuery.data;
  const voteResult = [
    ...(acceptedResultsQuery.data ?? []),
    ...(notAcceptedResultsQuery.data ?? []),
  ].find(vr => vr.request.trackingCid === contractId);

  return {
    voteRequest: voteRequest,
    voteResult: voteResult,
    hasVoteRequest,
    hasVoteResult,
    isPending,
    isComplete,
  };
};
