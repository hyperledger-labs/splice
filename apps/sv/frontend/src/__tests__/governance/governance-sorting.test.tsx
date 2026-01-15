// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { render, screen } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
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
    test('should render items sorted by voting closes date ascending (closest deadline first)', () => {
      const unsortedRequests: ActionRequiredData[] = [
        {
          actionName: 'Action C - Latest deadline',
          contractId: 'c' as ContractId<VoteRequest>,
          votingCloses: '2025-01-25 12:00',
          createdAt: '2025-01-10 12:00',
          requester: 'sv1',
        },
        {
          actionName: 'Action A - Earliest deadline',
          contractId: 'a' as ContractId<VoteRequest>,
          votingCloses: '2025-01-15 12:00',
          createdAt: '2025-01-10 12:00',
          requester: 'sv1',
        },
        {
          actionName: 'Action B - Middle deadline',
          contractId: 'b' as ContractId<VoteRequest>,
          votingCloses: '2025-01-20 12:00',
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
      expect(cards).toHaveLength(3);

      const actionNames = cards.map(
        card => card.querySelector('[data-testid="action-required-action-content"]')?.textContent
      );
      expect(actionNames[0]).toBe('Action A - Earliest deadline');
      expect(actionNames[1]).toBe('Action B - Middle deadline');
      expect(actionNames[2]).toBe('Action C - Latest deadline');
    });

    test('should handle same-day deadlines with different times', () => {
      const unsortedRequests: ActionRequiredData[] = [
        {
          actionName: 'Action B - Later time',
          contractId: 'b' as ContractId<VoteRequest>,
          votingCloses: '2025-01-15 18:00',
          createdAt: '2025-01-10 12:00',
          requester: 'sv1',
        },
        {
          actionName: 'Action A - Earlier time',
          contractId: 'a' as ContractId<VoteRequest>,
          votingCloses: '2025-01-15 10:00',
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
      expect(actionNames[0]).toBe('Action A - Earlier time');
      expect(actionNames[1]).toBe('Action B - Later time');
    });
  });

  describe('Inflight Votes Section', () => {
    const baseData: Omit<
      ProposalListingData,
      'actionName' | 'contractId' | 'voteTakesEffect' | 'votingThresholdDeadline'
    > = {
      yourVote: 'accepted',
      status: 'In Progress',
      voteStats: { accepted: 5, rejected: 2, 'no-vote': 1 },
      acceptanceThreshold: BigInt(11),
    };

    test('should render items sorted by effective date ascending (closest first)', () => {
      const unsortedRequests: ProposalListingData[] = [
        {
          ...baseData,
          actionName: 'Action C - Latest effective',
          contractId: 'c' as ContractId<VoteRequest>,
          voteTakesEffect: '2025-01-30 12:00',
          votingThresholdDeadline: '2025-01-25 12:00',
        },
        {
          ...baseData,
          actionName: 'Action A - Earliest effective',
          contractId: 'a' as ContractId<VoteRequest>,
          voteTakesEffect: '2025-01-20 12:00',
          votingThresholdDeadline: '2025-01-15 12:00',
        },
        {
          ...baseData,
          actionName: 'Action B - Middle effective',
          contractId: 'b' as ContractId<VoteRequest>,
          voteTakesEffect: '2025-01-25 12:00',
          votingThresholdDeadline: '2025-01-20 12:00',
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
      expect(rows).toHaveLength(3);

      const actionNames = rows.map(
        row => row.querySelector('[data-testid="inflight-votes-row-action-name"]')?.textContent
      );
      expect(actionNames[0]).toBe('Action A - Earliest effective');
      expect(actionNames[1]).toBe('Action B - Middle effective');
      expect(actionNames[2]).toBe('Action C - Latest effective');
    });

    test('should sort Threshold items by their voting deadline alongside dated items', () => {
      const unsortedRequests: ProposalListingData[] = [
        {
          ...baseData,
          actionName: 'Threshold Action',
          contractId: 't' as ContractId<VoteRequest>,
          voteTakesEffect: 'Threshold',
          votingThresholdDeadline: '2025-01-15 12:00',
        },
        {
          ...baseData,
          actionName: 'Dated Action',
          contractId: 'd' as ContractId<VoteRequest>,
          voteTakesEffect: '2025-01-30 12:00',
          votingThresholdDeadline: '2025-01-25 12:00',
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

      expect(actionNames[0]).toBe('Threshold Action');
      expect(actionNames[1]).toBe('Dated Action');
    });

    test('should sort multiple Threshold items by voting deadline', () => {
      const unsortedRequests: ProposalListingData[] = [
        {
          ...baseData,
          actionName: 'Threshold C - Latest deadline',
          contractId: 'c' as ContractId<VoteRequest>,
          voteTakesEffect: 'Threshold',
          votingThresholdDeadline: '2025-01-25 12:00',
        },
        {
          ...baseData,
          actionName: 'Threshold A - Earliest deadline',
          contractId: 'a' as ContractId<VoteRequest>,
          voteTakesEffect: 'Threshold',
          votingThresholdDeadline: '2025-01-15 12:00',
        },
        {
          ...baseData,
          actionName: 'Threshold B - Middle deadline',
          contractId: 'b' as ContractId<VoteRequest>,
          voteTakesEffect: 'Threshold',
          votingThresholdDeadline: '2025-01-20 12:00',
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

      expect(actionNames[0]).toBe('Threshold A - Earliest deadline');
      expect(actionNames[1]).toBe('Threshold B - Middle deadline');
      expect(actionNames[2]).toBe('Threshold C - Latest deadline');
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

    test('should render items sorted by effective date descending (most recent first)', () => {
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
          voteTakesEffect: '2025-01-20 12:00',
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
      expect(rows).toHaveLength(3);

      const actionNames = rows.map(
        row => row.querySelector('[data-testid="vote-history-row-action-name"]')?.textContent
      );
      expect(actionNames[0]).toBe('Action C - Most recent');
      expect(actionNames[1]).toBe('Action B - Middle');
      expect(actionNames[2]).toBe('Action A - Oldest');
    });

    test('should handle same-day effective dates with different times', () => {
      const unsortedRequests: ProposalListingData[] = [
        {
          ...baseData,
          actionName: 'Action A - Earlier time',
          contractId: 'a' as ContractId<VoteRequest>,
          voteTakesEffect: '2025-01-15 10:00',
          votingThresholdDeadline: '2025-01-10 12:00',
        },
        {
          ...baseData,
          actionName: 'Action B - Later time',
          contractId: 'b' as ContractId<VoteRequest>,
          voteTakesEffect: '2025-01-15 18:00',
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

      expect(actionNames[0]).toBe('Action B - Later time');
      expect(actionNames[1]).toBe('Action A - Earlier time');
    });
  });
});
