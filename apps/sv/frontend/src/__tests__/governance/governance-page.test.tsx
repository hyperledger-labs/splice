// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { render, screen, within } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { SvConfigProvider } from '../../utils';
import userEvent from '@testing-library/user-event';
import App from '../../App';

type UserEvent = ReturnType<typeof userEvent.setup>;

const GovernanceWithConfig = () => {
  return (
    <SvConfigProvider>
      <App />
    </SvConfigProvider>
  );
};

async function login(user: UserEvent) {
  render(<GovernanceWithConfig />);

  expect(await screen.findByText('Log In')).toBeDefined();

  const input = screen.getByRole('textbox');
  await user.type(input, 'sv1');

  const button = screen.getByRole('button', { name: 'Log In' });
  user.click(button);
}

async function navigateToGovernancePage(user: UserEvent) {
  expect(await screen.findByTestId('navlink-governance-beta')).toBeDefined();
  await user.click(screen.getByText('Governance'));
}

// Skipping this test until we switch to the new UI
describe.skip('Governance Page', () => {
  test('Login and navigate to Governance Page', async () => {
    const user = userEvent.setup();

    await login(user);
    await navigateToGovernancePage(user);

    const title = screen.getByTestId('governance-page-header-title');
    expect(title).toBeInTheDocument();
  });

  test('should render all Governance Page sections', async () => {
    const user = userEvent.setup();

    render(<GovernanceWithConfig />);

    await navigateToGovernancePage(user);

    const actionRequired = screen.getByTestId('action-required-section');
    expect(actionRequired).toBeDefined();

    const inflightVoteRequests = screen.getByTestId('inflight-vote-requests-section');
    expect(inflightVoteRequests).toBeDefined();

    const voteHistory = screen.getByTestId('vote-history-section');
    expect(voteHistory).toBeDefined();
  });

  test('should display the correct number of Action Required Requests', async () => {
    const user = userEvent.setup();

    render(<GovernanceWithConfig />);

    await navigateToGovernancePage(user);

    const actions = screen.getAllByTestId('action-required-card');
    expect(actions.length).toBe(4);
  });

  test('should show the correct number of Inflight Proposals', async () => {
    const user = userEvent.setup();

    render(<GovernanceWithConfig />);

    await navigateToGovernancePage(user);

    expect(() => screen.getAllByTestId('inflight-vote-requests-row')).toThrowError(
      /Unable to find an element/
    );
  });

  test('should correctly display the number of completed Proposals', async () => {
    const user = userEvent.setup();

    render(<GovernanceWithConfig />);

    await navigateToGovernancePage(user);

    const voteRequests = screen.getAllByTestId('vote-history-row');
    expect(voteRequests.length).toBe(5);

    expect(true).toBe(true);
  });

  test('click on Details link to see Proposal Details (Action Required)', async () => {
    const user = userEvent.setup();

    render(<GovernanceWithConfig />);

    await navigateToGovernancePage(user);

    const actions = screen.getAllByTestId('action-required-card');

    const viewDetailsLink = await within(actions[0]).findByTestId('action-required-view-details');
    expect(viewDetailsLink).toBeDefined();

    await user.click(viewDetailsLink);

    const proposalDetails = screen.getByTestId('proposal-details-title');
    expect(proposalDetails).toBeDefined();
  });

  test('proposal details page should render all details', async () => {
    const user = userEvent.setup();

    render(<GovernanceWithConfig />);

    await navigateToGovernancePage(user);

    const actions = screen.getAllByTestId('action-required-card');

    const viewDetailsLink = await within(actions[0]).findByTestId('action-required-view-details');
    expect(viewDetailsLink).toBeDefined();

    await user.click(viewDetailsLink);

    const proposalDetails = screen.getByTestId('proposal-details-title');
    expect(proposalDetails).toBeDefined();

    const action = screen.getByTestId('proposal-details-action-value');
    expect(action).toBeDefined();

    const summary = screen.getByTestId('proposal-details-summary-value');
    expect(summary).toBeDefined();

    const url = screen.getByTestId('proposal-details-url-value');
    expect(url).toBeDefined();

    const votingInformationSection = screen.getByTestId('proposal-details-voting-information');
    expect(votingInformationSection).toBeDefined();

    const requesterInput = within(votingInformationSection).getByTestId(
      'proposal-details-requester-party-id-input'
    );
    expect(requesterInput).toBeDefined();

    const votingClosesIso = within(votingInformationSection).getByTestId(
      'proposal-details-voting-closes-value'
    );
    expect(votingClosesIso).toBeDefined();

    const voteTakesEffectIso = within(votingInformationSection).getByTestId(
      'proposal-details-vote-takes-effect-value'
    );
    expect(voteTakesEffectIso).toBeDefined();

    const status = screen.getByTestId('proposal-details-status-value');
    expect(status).toBeDefined();

    const votesSection = screen.getByTestId('proposal-details-votes');
    expect(votesSection).toBeDefined();

    const votes = within(votesSection).getAllByTestId('proposal-details-vote');
    expect(votes.length).toBeGreaterThan(0);

    expect(screen.getByTestId('proposal-details-your-vote-section')).toBeDefined();
    expect(screen.getByTestId('proposal-details-your-vote-input')).toBeDefined();
    expect(screen.getByTestId('proposal-details-your-vote-accept')).toBeDefined();
    expect(screen.getByTestId('proposal-details-your-vote-reject')).toBeDefined();
  });
});
