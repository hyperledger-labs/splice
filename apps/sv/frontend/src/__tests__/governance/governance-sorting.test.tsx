// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { render, screen } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { MemoryRouter } from 'react-router';
import { ContractId } from '@daml/types';
import { VoteRequest } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import {
  ActionRequiredSection,
  ActionRequiredData,
} from '../../components/governance/ActionRequiredSection';
import { ProposalListingSection } from '../../components/governance/ProposalListingSection';
import { ProposalListingData } from '../../utils/types';

describe('Governance Page Sorting', () => {
  describe('Action Required Section', () => {
    test('should sort by voting closes date ascending (closest deadline first)', () => {
      const unsortedRequests: ActionRequiredData[] = [
        {
          actionName: 'Action C - Latest',
          contractId: 'c' as ContractId<VoteRequest>,
          votingCloses: '2025-01-25 12:00',
          createdAt: '2025-01-10 12:00',
          requester: 'sv1',
        },
        {
          actionName: 'Action A - Earliest',
          contractId: 'a' as ContractId<VoteRequest>,
          votingCloses: '2025-01-15 10:00',
          createdAt: '2025-01-10 12:00',
          requester: 'sv1',
        },
        {
          actionName: 'Action B - Middle',
          contractId: 'b' as ContractId<VoteRequest>,
          votingCloses: '2025-01-15 18:00',
          createdAt: '2025-01-10 12:00',
          requester: 'sv1',
        },
      ];

      render(
        <MemoryRouter>
          <ActionRequiredSection actionRequiredRequests={unsortedRequests} />
        </MemoryRouter>
      );

      const cards = screen.getAllByTestId('action-required-card');
      const actionNames = cards.map(
        card => card.querySelector('[data-testid="action-required-action-content"]')?.textContent
      );

      expect(actionNames).toEqual([
        'Action A - Earliest',
        'Action B - Middle',
        'Action C - Latest',
      ]);
    });
  });

  describe('Inflight Votes Section', () => {
    const baseData: Omit<
      ProposalListingData,
      'actionName' | 'contractId' | 'voteTakesEffect' | 'votingThresholdDeadline' | 'voteStats'
    > = {
      yourVote: 'accepted',
      status: 'In Progress',
      acceptanceThreshold: BigInt(11),
    };

    test('should sort with Threshold items first (by votes desc, then deadline asc), then dated items by effective date asc', () => {
      const unsortedRequests: ProposalListingData[] = [
        {
          ...baseData,
          actionName: 'Dated - Later',
          contractId: 'd2' as ContractId<VoteRequest>,
          voteTakesEffect: '2025-01-30 12:00',
          votingThresholdDeadline: '2025-01-25 12:00',
          voteStats: { accepted: 5, rejected: 2, 'no-vote': 1 },
        },
        {
          ...baseData,
          actionName: 'Threshold - 5 votes, later deadline',
          contractId: 't2' as ContractId<VoteRequest>,
          voteTakesEffect: 'Threshold',
          votingThresholdDeadline: '2025-01-25 12:00',
          voteStats: { accepted: 3, rejected: 2, 'no-vote': 3 },
        },
        {
          ...baseData,
          actionName: 'Dated - Earlier',
          contractId: 'd1' as ContractId<VoteRequest>,
          voteTakesEffect: '2025-01-20 12:00',
          votingThresholdDeadline: '2025-01-15 12:00',
          voteStats: { accepted: 5, rejected: 2, 'no-vote': 1 },
        },
        {
          ...baseData,
          actionName: 'Threshold - 10 votes',
          contractId: 't1' as ContractId<VoteRequest>,
          voteTakesEffect: 'Threshold',
          votingThresholdDeadline: '2025-01-30 12:00',
          voteStats: { accepted: 7, rejected: 3, 'no-vote': 0 },
        },
        {
          ...baseData,
          actionName: 'Threshold - 5 votes, earlier deadline',
          contractId: 't3' as ContractId<VoteRequest>,
          voteTakesEffect: 'Threshold',
          votingThresholdDeadline: '2025-01-15 12:00',
          voteStats: { accepted: 4, rejected: 1, 'no-vote': 3 },
        },
      ];

      render(
        <MemoryRouter>
          <ProposalListingSection
            sectionTitle="Inflight Votes"
            data={unsortedRequests}
            noDataMessage="No data"
            uniqueId="inflight-votes"
            showVoteStats
            showAcceptanceThreshold
            showThresholdDeadline
            sortOrder="effectiveAtAsc"
          />
        </MemoryRouter>
      );

      const rows = screen.getAllByTestId('inflight-votes-row');
      const actionNames = rows.map(
        row => row.querySelector('[data-testid="inflight-votes-row-action-name"]')?.textContent
      );

      expect(actionNames).toEqual([
        'Threshold - 10 votes', // Threshold first, most votes
        'Threshold - 5 votes, earlier deadline', // Same 5 votes, earlier deadline wins
        'Threshold - 5 votes, later deadline', // Same 5 votes, later deadline
        'Dated - Earlier', // Dated items sorted by effective date asc
        'Dated - Later',
      ]);
    });
  });

  describe('Vote History Section', () => {
    const baseData: Omit<
      ProposalListingData,
      'actionName' | 'contractId' | 'voteTakesEffect' | 'votingThresholdDeadline'
    > = {
      yourVote: 'accepted',
      status: 'Implemented',
      voteStats: { accepted: 8, rejected: 2, 'no-vote': 1 },
      acceptanceThreshold: BigInt(11),
    };

    test('should sort by effective date descending (most recent first)', () => {
      const unsortedRequests: ProposalListingData[] = [
        {
          ...baseData,
          actionName: 'Action A - Oldest',
          contractId: 'a' as ContractId<VoteRequest>,
          voteTakesEffect: '2025-01-10 12:00',
          votingThresholdDeadline: '2025-01-05 12:00',
        },
        {
          ...baseData,
          actionName: 'Action C - Most recent',
          contractId: 'c' as ContractId<VoteRequest>,
          voteTakesEffect: '2025-01-20 18:00',
          votingThresholdDeadline: '2025-01-15 12:00',
        },
        {
          ...baseData,
          actionName: 'Action D - Same day, earlier time',
          contractId: 'd' as ContractId<VoteRequest>,
          voteTakesEffect: '2025-01-20 10:00',
          votingThresholdDeadline: '2025-01-15 12:00',
        },
        {
          ...baseData,
          actionName: 'Action B - Middle',
          contractId: 'b' as ContractId<VoteRequest>,
          voteTakesEffect: '2025-01-15 12:00',
          votingThresholdDeadline: '2025-01-10 12:00',
        },
      ];

      render(
        <MemoryRouter>
          <ProposalListingSection
            sectionTitle="Vote History"
            data={unsortedRequests}
            noDataMessage="No data"
            uniqueId="vote-history"
            showStatus
            showVoteStats
            showAcceptanceThreshold
            sortOrder="effectiveAtDesc"
          />
        </MemoryRouter>
      );

      const rows = screen.getAllByTestId('vote-history-row');
      const actionNames = rows.map(
        row => row.querySelector('[data-testid="vote-history-row-action-name"]')?.textContent
      );

      expect(actionNames).toEqual([
        'Action C - Most recent',
        'Action D - Same day, earlier time',
        'Action B - Middle',
        'Action A - Oldest',
      ]);
    });
  });
});
