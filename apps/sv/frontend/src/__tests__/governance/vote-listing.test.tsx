// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { render, screen } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import {
  VoteListingData,
  VotesListingSection,
} from '../../components/governance/VotesListingSection';

const inflightVoteRequests: VoteListingData[] = [
  {
    actionName: 'Feature Application',
    votingCloses: '2025-09-25 11:00',
    voteTakesEffect: '2025-09-26 11:00',
    yourVote: 'no-vote',
    status: 'In Progress',
    voteStats: { accepted: 0, rejected: 0, 'no-vote': 0 },
    acceptanceThreshold: BigInt(11),
  },
  {
    actionName: 'Set DSO Rules Configuration',
    votingCloses: '2025-09-25 11:00',
    voteTakesEffect: '2025-09-26 11:00',
    yourVote: 'accepted',
    status: 'In Progress',
    voteStats: { accepted: 8, rejected: 2, 'no-vote': 1 },
    acceptanceThreshold: BigInt(11),
  },
];

const voteHistory: VoteListingData[] = [
  {
    actionName: 'Feature Application',
    votingCloses: '2025-09-25 11:00',
    voteTakesEffect: '2025-09-26 11:00',
    yourVote: 'no-vote',
    status: 'Implemented',
    voteStats: { accepted: 0, rejected: 0, 'no-vote': 0 },
    acceptanceThreshold: BigInt(11),
  },
  {
    actionName: 'Set DSO Rules Configuration',
    votingCloses: '2025-09-25 11:00',
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
      <VotesListingSection
        sectionTitle="Inflight Vote Requests"
        data={inflightVoteRequests}
        uniqueId="inflight-vote-request"
        showStatus
      />
    );

    expect(screen.getByTestId('inflight-vote-request-section')).toBeDefined();
    expect(await screen.findByText('Inflight Vote Requests')).toBeDefined();
  });

  test('should render all inflight vote requests', () => {
    render(
      <VotesListingSection
        sectionTitle="Inflight Vote Requests"
        data={inflightVoteRequests}
        uniqueId="inflight-vote-request"
        showStatus
      />
    );

    const rows = screen.getAllByTestId('inflight-vote-request-row');
    expect(rows.length).toBe(inflightVoteRequests.length);
  });

  test('should render inflight vote request details', () => {
    const uniqueId = 'inflight-vote-request';
    const data = {
      actionName: 'Feature Application',
      votingCloses: '2025-09-25 11:00',
      voteTakesEffect: '2025-09-26 11:00',
      yourVote: 'no-vote',
      status: 'In Progress',
      voteStats: { accepted: 2, rejected: 3, 'no-vote': 0 },
      acceptanceThreshold: BigInt(11),
    } as VoteListingData;

    render(
      <VotesListingSection
        sectionTitle="Inflight Vote Requests"
        data={[data]}
        uniqueId={uniqueId}
        showVoteStats
        showAcceptanceThreshold
      />
    );

    const table = screen.getByTestId(`${uniqueId}-section-table`);
    expect(table).toBeDefined();

    const action = screen.getByTestId(`${uniqueId}-row-action-name`);
    expect(action.textContent).toBe(data.actionName);

    const votingCloses = screen.getByTestId(`${uniqueId}-row-voting-closes`);
    expect(votingCloses.textContent).toBe(data.votingCloses);

    const voteTakesEffect = screen.getByTestId(`${uniqueId}-row-vote-takes-effect`);
    expect(voteTakesEffect.textContent).toBe(data.voteTakesEffect);

    const voteStats = screen.getByTestId(`${uniqueId}-row-vote-stats`);
    expect(voteStats.textContent).toBe('2 Accepted / 3 Rejected');

    const acceptanceThreshold = screen.getByTestId(`${uniqueId}-row-acceptance-threshold`);
    expect(acceptanceThreshold.textContent).toBe('11');

    const yourVote = screen.getByTestId(`${uniqueId}-row-your-vote`);
    expect(yourVote.textContent).toMatch(/No Vote/);

    const viewDetails = screen.getByTestId(`${uniqueId}-row-view-details`);
    expect(viewDetails).toBeDefined();
  });

  test('should render Accepted inflight vote request', () => {
    const uniqueId = 'inflight-vote-request';
    const data = {
      actionName: 'Feature Application',
      votingCloses: '2025-09-25 11:00',
      voteTakesEffect: '2025-09-26 11:00',
      yourVote: 'accepted',
      status: 'In Progress',
      voteStats: { accepted: 0, rejected: 0, 'no-vote': 0 },
      acceptanceThreshold: BigInt(11),
    } as VoteListingData;

    render(
      <VotesListingSection
        sectionTitle="Inflight Vote Requests"
        data={[data]}
        uniqueId={uniqueId}
        showStatus
      />
    );

    const yourVote = screen.getByTestId(`${uniqueId}-row-your-vote`);
    const acceptedIcon = screen.getByTestId(`${uniqueId}-row-your-vote-accepted-icon`);

    expect(acceptedIcon).toBeDefined();
    expect(yourVote.textContent).toMatch(/Accepted/);
  });

  test('should render Rejected inflight vote request', () => {
    const uniqueId = 'inflight-vote-request';
    const data = {
      actionName: 'Feature Application',
      votingCloses: '2025-09-25 11:00',
      voteTakesEffect: '2025-09-26 11:00',
      yourVote: 'rejected',
      status: 'In Progress',
      voteStats: { accepted: 0, rejected: 0, 'no-vote': 0 },
      acceptanceThreshold: BigInt(11),
    } as VoteListingData;

    render(
      <VotesListingSection
        sectionTitle="Inflight Vote Requests"
        data={[data]}
        uniqueId={uniqueId}
        showStatus
      />
    );

    const yourVote = screen.getByTestId(`${uniqueId}-row-your-vote`);
    const rejectedIcon = screen.getByTestId(`${uniqueId}-row-your-vote-rejected-icon`);

    expect(rejectedIcon).toBeDefined();
    expect(yourVote.textContent).toMatch(/Rejected/);
  });

  test('should render info when no data is available', async () => {
    const uniqueId = 'inflight-vote-request';

    render(
      <VotesListingSection
        sectionTitle="Inflight Vote Requests"
        data={[]}
        uniqueId={uniqueId}
        showStatus
      />
    );

    const sectionInfo = await screen.findByTestId(`${uniqueId}-section-info`);
    expect(sectionInfo.textContent).toMatch(/No Inflight Vote Requests available/);
  });
});

describe('Vote history', () => {
  test('should render vote history section', async () => {
    render(<VotesListingSection sectionTitle="Vote History" data={[]} uniqueId="vote-history" />);

    expect(screen.getByTestId('vote-history-section')).toBeDefined();
    expect(await screen.findByText('Vote History')).toBeDefined();
  });

  test('should render all vote history', () => {
    render(
      <VotesListingSection sectionTitle="Vote History" data={voteHistory} uniqueId="vote-history" />
    );

    const rows = screen.getAllByTestId('vote-history-row');
    expect(rows.length).toBe(voteHistory.length);
  });

  test('should render vote history details', () => {
    const uniqueId = 'vote-history';
    const data = {
      actionName: 'Feature Application',
      votingCloses: '2024-09-25 11:00',
      voteTakesEffect: '2024-09-26 11:00',
      yourVote: 'no-vote',
      status: 'Implemented',
      voteStats: { accepted: 0, rejected: 0, 'no-vote': 0 },
      acceptanceThreshold: BigInt(11),
    } as VoteListingData;

    render(
      <VotesListingSection
        sectionTitle="Vote History"
        data={[data]}
        uniqueId={uniqueId}
        showStatus
      />
    );

    const table = screen.getByTestId(`${uniqueId}-section-table`);
    expect(table).toBeDefined();

    const action = screen.getByTestId(`${uniqueId}-row-action-name`);
    expect(action.textContent).toBe(data.actionName);

    const votingCloses = screen.getByTestId(`${uniqueId}-row-voting-closes`);
    expect(votingCloses.textContent).toBe(data.votingCloses);

    const voteTakesEffect = screen.getByTestId(`${uniqueId}-row-vote-takes-effect`);
    expect(voteTakesEffect.textContent).toBe(data.voteTakesEffect);

    const status = screen.getByTestId(`${uniqueId}-row-status`);
    expect(status.textContent).toBe(data.status);

    const yourVote = screen.getByTestId(`${uniqueId}-row-your-vote`);
    expect(yourVote.textContent).toMatch(/No Vote/);

    const viewDetails = screen.getByTestId(`${uniqueId}-row-view-details`);
    expect(viewDetails).toBeDefined();
  });

  test('should show info message when no vote history is available', async () => {
    render(
      <VotesListingSection
        sectionTitle="Voting History"
        data={[]}
        showStatus
        uniqueId="voting-history"
      />
    );

    const alertInfo = screen.getByTestId('voting-history-section-info');
    expect(alertInfo).toBeDefined();
    expect(alertInfo.textContent).toMatch(/No Voting History available/);
  });
});
