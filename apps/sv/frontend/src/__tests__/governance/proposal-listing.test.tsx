// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { render, screen } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { ProposalListingSection } from '../../components/governance/ProposalListingSection';
import { VoteRequest } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { ContractId } from '@daml/types';
import { ProposalListingData } from '../../utils/types';
import { MemoryRouter } from 'react-router';

const inflightVoteRequests: ProposalListingData[] = [
  {
    actionName: 'Feature Application',
    contractId: '2abcde123456' as ContractId<VoteRequest>,
    votingThresholdDeadline: '2025-09-25 11:00',
    voteTakesEffect: '2025-09-26 11:00',
    yourVote: 'no-vote',
    status: 'In Progress',
    voteStats: { accepted: 0, rejected: 0, 'no-vote': 0 },
    acceptanceThreshold: BigInt(11),
  },
  {
    actionName: 'Set DSO Rules Configuration',
    contractId: 'bcde123456' as ContractId<VoteRequest>,
    votingThresholdDeadline: '2025-09-25 11:00',
    voteTakesEffect: '2025-09-26 11:00',
    yourVote: 'accepted',
    status: 'In Progress',
    voteStats: { accepted: 8, rejected: 2, 'no-vote': 1 },
    acceptanceThreshold: BigInt(11),
  },
];

const voteHistory: ProposalListingData[] = [
  {
    actionName: 'Feature Application',
    contractId: '2abcde123456' as ContractId<VoteRequest>,
    votingThresholdDeadline: '2025-09-25 11:00',
    voteTakesEffect: '2025-09-26 11:00',
    yourVote: 'no-vote',
    status: 'Implemented',
    voteStats: { accepted: 0, rejected: 0, 'no-vote': 0 },
    acceptanceThreshold: BigInt(11),
  },
  {
    actionName: 'Set DSO Rules Configuration',
    contractId: '2bcde123456' as ContractId<VoteRequest>,
    votingThresholdDeadline: '2025-09-25 11:00',
    voteTakesEffect: '2025-09-26 11:00',
    yourVote: 'accepted',
    status: 'Rejected',
    voteStats: { accepted: 2, rejected: 8, 'no-vote': 1 },
    acceptanceThreshold: BigInt(11),
  },
];

describe('Inflight Vote Requests', () => {
  test('should render inflight vote requests section', async () => {
    render(
      <MemoryRouter>
        <ProposalListingSection
          sectionTitle="Inflight Vote Requests"
          data={inflightVoteRequests}
          noDataMessage="No Inflight Vote Requests available"
          uniqueId="proposals-request"
          showStatus
        />
      </MemoryRouter>
    );

    expect(screen.getByTestId('proposals-request-section')).toBeDefined();
    expect(await screen.findByText('Inflight Vote Requests')).toBeDefined();
  });

  test('should render all inflight vote requests', () => {
    render(
      <MemoryRouter>
        <ProposalListingSection
          sectionTitle="Inflight Vote Requests"
          data={inflightVoteRequests}
          noDataMessage="No Inflight Vote Requests available"
          uniqueId="proposals-request"
          showStatus
        />
      </MemoryRouter>
    );

    const rows = screen.getAllByTestId('proposals-request-row');
    expect(rows.length).toBe(inflightVoteRequests.length);
  });

  test('should render inflight vote request details', () => {
    const uniqueId = 'proposals-request';
    const data = {
      actionName: 'Feature Application',
      votingThresholdDeadline: '2025-09-25 11:00',
      voteTakesEffect: '2025-09-26 11:00',
      yourVote: 'no-vote',
      status: 'In Progress',
      voteStats: { accepted: 2, rejected: 3, 'no-vote': 0 },
      acceptanceThreshold: BigInt(11),
    } as ProposalListingData;

    render(
      <MemoryRouter>
        <ProposalListingSection
          sectionTitle="Inflight Vote Requests"
          data={[data]}
          noDataMessage="No Inflight Vote Requests available"
          uniqueId={uniqueId}
          showVoteStats
          showThresholdDeadline
        />
      </MemoryRouter>
    );

    const table = screen.getByTestId(`${uniqueId}-section-table`);
    expect(table).toBeDefined();

    const action = screen.getByTestId(`${uniqueId}-row-action-name`);
    expect(action.textContent).toBe(data.actionName);

    const votingThresholdDeadline = screen.getByTestId(`${uniqueId}-row-voting-threshold-deadline`);
    expect(votingThresholdDeadline.textContent).toBe(data.votingThresholdDeadline);

    const voteTakesEffect = screen.getByTestId(`${uniqueId}-row-vote-takes-effect`);
    expect(voteTakesEffect.textContent).toBe(data.voteTakesEffect);

    const acceptedVoteStats = screen.getByTestId(`${uniqueId}-row-all-votes-stats-accepted`);
    expect(acceptedVoteStats.textContent).toBe('2 Accepted');

    const rejectedVoteStats = screen.getByTestId(`${uniqueId}-row-all-votes-stats-rejected`);
    expect(rejectedVoteStats.textContent).toBe('3 Rejected');

    const yourVote = screen.getByTestId(`${uniqueId}-row-your-vote`);
    expect(yourVote.textContent).toMatch(/No Vote/);
  });

  test('should render Accepted inflight vote request', () => {
    const uniqueId = 'proposals-request';
    const data = {
      actionName: 'Feature Application',
      votingThresholdDeadline: '2025-09-25 11:00',
      voteTakesEffect: '2025-09-26 11:00',
      yourVote: 'accepted',
      status: 'In Progress',
      voteStats: { accepted: 0, rejected: 0, 'no-vote': 0 },
      acceptanceThreshold: BigInt(11),
    } as ProposalListingData;

    render(
      <MemoryRouter>
        <ProposalListingSection
          sectionTitle="Inflight Vote Requests"
          data={[data]}
          noDataMessage="No Inflight Vote Requests available"
          uniqueId={uniqueId}
          showStatus
        />
      </MemoryRouter>
    );

    const yourVote = screen.getByTestId(`${uniqueId}-row-your-vote`);
    const acceptedIcon = screen.getByTestId(`${uniqueId}-row-your-vote-stats-accepted-icon`);

    expect(acceptedIcon).toBeInTheDocument();
    expect(yourVote.textContent).toMatch(/Accepted/);
  });

  test('should render Rejected inflight vote request', () => {
    const uniqueId = 'proposals-request';
    const data = {
      actionName: 'Feature Application',
      votingThresholdDeadline: '2025-09-25 11:00',
      voteTakesEffect: '2025-09-26 11:00',
      yourVote: 'rejected',
      status: 'In Progress',
      voteStats: { accepted: 0, rejected: 0, 'no-vote': 0 },
      acceptanceThreshold: BigInt(11),
    } as ProposalListingData;

    render(
      <MemoryRouter>
        <ProposalListingSection
          sectionTitle="Inflight Vote Requests"
          data={[data]}
          noDataMessage="No Inflight Vote Requests available"
          uniqueId={uniqueId}
          showStatus
        />
      </MemoryRouter>
    );

    const yourVote = screen.getByTestId(`${uniqueId}-row-your-vote`);
    const rejectedIcon = screen.getByTestId(`${uniqueId}-row-your-vote-stats-rejected-icon`);

    expect(rejectedIcon).toBeInTheDocument();
    expect(yourVote.textContent).toMatch(/Rejected/);
  });

  test('should render info when no data is available', async () => {
    const uniqueId = 'proposals-request';

    render(
      <MemoryRouter>
        <ProposalListingSection
          sectionTitle="Inflight Vote Requests"
          data={[]}
          noDataMessage="No Inflight Vote Requests available"
          uniqueId={uniqueId}
          showStatus
        />
      </MemoryRouter>
    );

    const sectionInfo = await screen.findByTestId(`${uniqueId}-section-info`);
    expect(sectionInfo.textContent).toMatch(/No Inflight Vote Requests available/);
  });
});

describe('Vote history', () => {
  test('should render vote history section', async () => {
    render(
      <MemoryRouter>
        <ProposalListingSection
          sectionTitle="Vote History"
          data={[]}
          noDataMessage="No Vote History available"
          uniqueId="vote-history"
        />
      </MemoryRouter>
    );

    expect(screen.getByTestId('vote-history-section')).toBeDefined();
    expect(await screen.findByText('Vote History')).toBeDefined();
  });

  test('should render all vote history', () => {
    render(
      <MemoryRouter>
        <ProposalListingSection
          sectionTitle="Vote History"
          data={voteHistory}
          noDataMessage="No Vote History available"
          uniqueId="vote-history"
        />
      </MemoryRouter>
    );

    const rows = screen.getAllByTestId('vote-history-row');
    expect(rows.length).toBe(voteHistory.length);
  });

  test('should render vote history details', () => {
    const uniqueId = 'vote-history';
    const data = {
      actionName: 'Feature Application',
      votingThresholdDeadline: '2024-09-25 11:00',
      voteTakesEffect: '2024-09-26 11:00',
      yourVote: 'accepted',
      status: 'Implemented',
      voteStats: { accepted: 9, rejected: 2, 'no-vote': 0 },
      acceptanceThreshold: BigInt(11),
    } as ProposalListingData;

    render(
      <MemoryRouter>
        <ProposalListingSection
          sectionTitle="Vote History"
          data={[data]}
          noDataMessage="No Vote History available"
          uniqueId={uniqueId}
          showStatus
          showVoteStats
        />
      </MemoryRouter>
    );

    const table = screen.getByTestId(`${uniqueId}-section-table`);
    expect(table).toBeDefined();

    const action = screen.getByTestId(`${uniqueId}-row-action-name`);
    expect(action.textContent).toBe(data.actionName);

    const voteTakesEffect = screen.getByTestId(`${uniqueId}-row-vote-takes-effect`);
    expect(voteTakesEffect.textContent).toBe(data.voteTakesEffect);

    const status = screen.getByTestId(`${uniqueId}-row-status`);
    expect(status.textContent).toBe(data.status);

    const acceptedVoteStats = screen.getByTestId(`${uniqueId}-row-all-votes-stats-accepted`);
    expect(acceptedVoteStats.textContent).toBe('9 Accepted');

    const rejectedVoteStats = screen.getByTestId(`${uniqueId}-row-all-votes-stats-rejected`);
    expect(rejectedVoteStats.textContent).toBe('2 Rejected');

    const yourVote = screen.getByTestId(`${uniqueId}-row-your-vote`);
    expect(yourVote.textContent).toMatch(/Accepted/);
  });

  test('should show info message when no vote history is available', async () => {
    render(
      <MemoryRouter>
        <ProposalListingSection
          sectionTitle="Vote History"
          data={[]}
          noDataMessage="No Vote History available"
          showStatus
          uniqueId="voting-history"
        />
      </MemoryRouter>
    );

    const alertInfo = screen.getByTestId('voting-history-section-info');
    expect(alertInfo).toBeDefined();
    expect(alertInfo.textContent).toMatch(/No Vote History available/);
  });
});
