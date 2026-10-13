// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import { Box, Button, Stack, Typography } from '@mui/material';
import { useMemo, useState } from 'react';
import {
  ActionRequiredSection,
  ActionRequiredData,
} from '../components/governance/ActionRequiredSection';
import { ProposalListingSection } from '../components/governance/ProposalListingSection';
import ProposalSearch from '../components/governance/ProposalSearch';
import { Loading, useVotesHooks } from '@canton-network/splice-common-frontend';
import { ContractId } from '@daml/types';
import { VoteRequest } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { useSvConfig } from '../utils';
import { PageHeader } from '../components/ui';
import {
  actionTagToTitle,
  buildVoteHistoryData,
  computeVoteStats,
  computeYourVote,
  getGovernanceActionTag,
  getRequesterPartyId,
} from '../utils/governance';
import { filterByContractId, isValidContractId } from '../utils/proposalSearch';
import { SupportedActionTag, ProposalListingData } from '../utils/types';
import { Link as RouterLink, useSearchParams } from 'react-router';
import { InfoOutlined, WarningAmberOutlined } from '@mui/icons-material';
import { useInfiniteVoteRequestResults, useVoteRequestResultsCount } from '../hooks';
import { usePaginatedVoteRequestResultsByContractId } from '../hooks/useListVoteRequests';

export const Governance: React.FC = () => {
  const svConfig = useSvConfig();
  const amuletName = svConfig.spliceInstanceNames.amuletName;
  const [searchParams] = useSearchParams();
  const initialSearchQuery = searchParams.get('q') ?? '';
  const [searchQuery, setSearchQuery] = useState(() =>
    isValidContractId(initialSearchQuery) ? initialSearchQuery.trim() : ''
  );

  const votesHooks = useVotesHooks();
  const dsoInfosQuery = votesHooks.useDsoInfos();
  const listVoteRequestsQuery = votesHooks.useListDsoRulesVoteRequests();
  const voteResultsInfiniteQuery = useInfiniteVoteRequestResults();
  const voteResultsCountQuery = useVoteRequestResultsCount();

  const voteRequestIds = listVoteRequestsQuery.data
    ? listVoteRequestsQuery.data.map(v => v.payload.trackingCid || v.contractId)
    : [];
  const votesQuery = votesHooks.useListVotes(voteRequestIds);

  const svPartyId = dsoInfosQuery.data?.svPartyId;
  const votingThreshold = dsoInfosQuery.data?.votingThreshold;
  const svs = dsoInfosQuery.data?.dsoRules.payload.svs;
  const alreadyVotedRequestIds: Set<ContractId<VoteRequest>> = useMemo(() => {
    return svPartyId && votesQuery.data
      ? new Set(votesQuery.data.filter(v => v.voter === svPartyId).map(v => v.requestCid))
      : new Set();
  }, [votesQuery.data, svPartyId]);

  const voteHistory = useMemo(() => {
    const pages = voteResultsInfiniteQuery.data?.pages;
    if (!pages || !svPartyId || votingThreshold === undefined) return [];

    const allVoteResults = pages.flatMap(page => page.results);
    return buildVoteHistoryData(allVoteResults, amuletName, svPartyId, votingThreshold, svs);
  }, [voteResultsInfiniteQuery.data?.pages, amuletName, svPartyId, votingThreshold, svs]);

  const voteRequests = listVoteRequestsQuery.data;

  const actionRequiredBase = useMemo(() => {
    if (!voteRequests) {
      return [];
    }

    return voteRequests
      .filter(v => !alreadyVotedRequestIds.has(v.payload.trackingCid || v.contractId))
      .map(vr => ({
        contractId: vr.payload.trackingCid || vr.contractId,
        actionName:
          actionTagToTitle(amuletName)[
            getGovernanceActionTag(vr.payload.action) as SupportedActionTag
          ],
        description: vr.payload.reason.body,
        votingCloses: vr.payload.voteBefore,
        createdAt: vr.createdAt,
        requester: getRequesterPartyId(vr.payload.requester, svs),
      })) as ActionRequiredData[];
  }, [voteRequests, alreadyVotedRequestIds, amuletName, svs]);

  const inflightBase = useMemo(() => {
    if (!voteRequests || votingThreshold === undefined) {
      return [];
    }

    return voteRequests
      .filter(v => alreadyVotedRequestIds.has(v.payload.trackingCid || v.contractId))
      .map(v => {
        const effectiveAt = v.payload.targetEffectiveAt ? v.payload.targetEffectiveAt : 'Threshold';
        const votes = v.payload.votes.entriesArray().map(e => e[1]);

        return {
          contractId: v.payload.trackingCid || v.contractId,
          actionName:
            actionTagToTitle(amuletName)[
              getGovernanceActionTag(v.payload.action) as SupportedActionTag
            ],
          description: v.payload.reason.body,
          votingThresholdDeadline: v.payload.voteBefore,
          voteTakesEffect: effectiveAt,
          yourVote: computeYourVote(votes, svPartyId),
          status: 'In Progress',
          voteStats: computeVoteStats(votes),
          acceptanceThreshold: votingThreshold,
          requester: getRequesterPartyId(v.payload.requester, svs),
        } as ProposalListingData;
      });
  }, [voteRequests, votingThreshold, alreadyVotedRequestIds, amuletName, svPartyId, svs]);

  const isLoading =
    dsoInfosQuery.isPending ||
    listVoteRequestsQuery.isPending ||
    votesQuery.isPending ||
    voteResultsInfiniteQuery.isPending;

  const hasSearch = isValidContractId(searchQuery);

  const actionRequiredRequests = useMemo(
    () => (hasSearch ? filterByContractId(actionRequiredBase, searchQuery) : actionRequiredBase),
    [hasSearch, actionRequiredBase, searchQuery]
  );

  const inflightRequests = useMemo(
    () => (hasSearch ? filterByContractId(inflightBase, searchQuery) : inflightBase),
    [hasSearch, inflightBase, searchQuery]
  );

  const loadedVoteHistoryMatches = useMemo(
    () => (hasSearch ? filterByContractId(voteHistory, searchQuery) : []),
    [hasSearch, voteHistory, searchQuery]
  );

  const needsClosedVoteFetch =
    !isLoading &&
    hasSearch &&
    actionRequiredRequests.length === 0 &&
    inflightRequests.length === 0 &&
    loadedVoteHistoryMatches.length === 0;

  const searchVoteResults = usePaginatedVoteRequestResultsByContractId(needsClosedVoteFetch, {
    contractId: searchQuery,
  });

  const searchVoteHistoryBase = useMemo(() => {
    if (!svPartyId || votingThreshold === undefined) {
      return [];
    }

    return buildVoteHistoryData(
      searchVoteResults.results,
      amuletName,
      svPartyId,
      votingThreshold,
      svs
    );
  }, [searchVoteResults.results, amuletName, svPartyId, votingThreshold, svs]);

  const filteredVoteHistory = useMemo(() => {
    if (!hasSearch) {
      return voteHistory;
    }

    if (loadedVoteHistoryMatches.length > 0) {
      return loadedVoteHistoryMatches;
    }

    return filterByContractId(searchVoteHistoryBase, searchQuery);
  }, [hasSearch, voteHistory, searchQuery, loadedVoteHistoryMatches, searchVoteHistoryBase]);

  const showVoteHistorySectionLoading =
    needsClosedVoteFetch && !searchVoteResults.isComplete && filteredVoteHistory.length === 0;

  const hasLoadedAllVoteHistoryPages = !voteResultsInfiniteQuery.hasNextPage;

  const showEmptyState =
    !isLoading &&
    !hasSearch &&
    actionRequiredRequests.length === 0 &&
    inflightRequests.length === 0 &&
    filteredVoteHistory.length === 0 &&
    hasLoadedAllVoteHistoryPages;

  if (isLoading) {
    return <Loading />;
  }

  if (
    dsoInfosQuery.isError ||
    listVoteRequestsQuery.isError ||
    votesQuery.isError ||
    voteResultsInfiniteQuery.isError
  ) {
    return <ErrorStateSection />;
  }

  return (
    <Box sx={{ p: 4 }}>
      <PageHeader
        title="Governance"
        actionElement={
          <Button
            id="initiate-proposal-button"
            variant="pill"
            component={RouterLink}
            to={`/governance/proposals/create`}
          >
            Initiate Proposal
          </Button>
        }
        data-testid="governance-page-header"
      />

      <ProposalSearch onSearchChange={setSearchQuery} />

      {showEmptyState ? (
        <EmptyStateSection />
      ) : (
        <>
          <ActionRequiredSection
            actionRequiredRequests={actionRequiredRequests}
            noDataMessage={
              hasSearch
                ? 'No action required items match your search.'
                : 'No Action Required items available'
            }
          />

          <ProposalListingSection
            sectionTitle="In-flight Proposals"
            badgeCount={inflightRequests.length}
            data={inflightRequests}
            noDataMessage={
              hasSearch
                ? 'No in-flight proposals match your search.'
                : 'No proposals are currently in flight. Proposals you have voted on will appear here while awaiting the voting threshold or deadline.'
            }
            uniqueId="inflight-proposals"
            showVoteStats
            showThresholdDeadline
            sortOrder="effectiveAtAsc"
          />

          <ProposalListingSection
            sectionTitle="Vote History"
            badgeCount={
              hasSearch
                ? showVoteHistorySectionLoading
                  ? undefined
                  : filteredVoteHistory.length
                : voteResultsCountQuery.data
            }
            data={filteredVoteHistory}
            isLoading={showVoteHistorySectionLoading}
            loadingMessage="Searching vote history…"
            noDataMessage={
              hasSearch
                ? 'No vote history matches your search.'
                : 'No data to show. You can see your vote history here after proposals meet their threshold deadline.'
            }
            uniqueId="vote-history"
            showStatus
            showVoteStats
            fetchNextPage={hasSearch ? undefined : voteResultsInfiniteQuery.fetchNextPage}
            hasNextPage={hasSearch ? false : (voteResultsInfiniteQuery.hasNextPage ?? false)}
            isFetchingNextPage={hasSearch ? false : voteResultsInfiniteQuery.isFetchingNextPage}
            pageCount={
              hasSearch
                ? undefined
                : voteResultsInfiniteQuery.data?.pages.filter(p => p.results.length > 0).length
            }
          />
        </>
      )}
    </Box>
  );
};

const EmptyStateSection: React.FC = () => (
  <Stack mt={11} alignItems="center" gap="14px">
    <InfoOutlined color="secondary" fontSize="large" />
    <Typography fontSize={20} fontWeight="bold" mt={1}>
      No data to show
    </Typography>
    <Typography fontSize={16}>
      This page will automatically update once there are in-flight proposals
    </Typography>
  </Stack>
);

const ErrorStateSection: React.FC = () => (
  <Stack mt={11} alignItems="center" gap="14px">
    <WarningAmberOutlined color="warning" fontSize="large" />
    <Typography fontSize={20} fontWeight="bold" mt={1}>
      Something went wrong
    </Typography>
    <Typography fontSize={16}>Please try to reload this page or contact support</Typography>
  </Stack>
);
