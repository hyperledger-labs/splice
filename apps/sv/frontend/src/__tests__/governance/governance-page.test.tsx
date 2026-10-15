// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { SvConfigProvider } from '../../utils';
import userEvent from '@testing-library/user-event';
import App from '../../App';
import { navigateToGovernancePage } from '../helpers';
import {
  activeProposalCid,
  closedVoteCid,
  voteResultsAmuletRules,
  voteResultsDsoRules,
} from '../mocks/constants';
import { CONTRACT_ID_VALIDATION_MESSAGE } from '../../utils/proposalSearch';

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

  expect(await screen.findByText('Log In')).toBeInTheDocument();

  const input = screen.getByRole('textbox');
  await user.type(input, 'sv1');

  const button = screen.queryByRole('button', { name: 'Log In' });
  if (button) {
    await user.click(button);
  }
}

describe('Governance Page', () => {
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
    expect(actionRequired).toBeInTheDocument();

    const inflightVoteRequests = screen.getByTestId('inflight-proposals-section');
    expect(inflightVoteRequests).toBeInTheDocument();

    const voteHistory = screen.getByTestId('vote-history-section');
    expect(voteHistory).toBeInTheDocument();
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

    expect(() => screen.getAllByTestId('inflight-proposals-row')).toThrowError(
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

  test('should display total vote history count in the section badge', async () => {
    const user = userEvent.setup();

    render(<GovernanceWithConfig />);

    await navigateToGovernancePage(user);

    const expectedCount = voteResultsAmuletRules.dso_rules_vote_results
      .concat(voteResultsDsoRules.dso_rules_vote_results)
      .filter(
        r => r.outcome.tag !== 'VRO_Accepted' || new Date(r.outcome.value.effectiveAt) < new Date()
      ).length;

    const badge = await screen.findByTestId('vote-history-section-badge-count');
    await waitFor(() => expect(badge).toHaveTextContent(`${expectedCount}`));
  });

  test('should display inflight votes count in the section badge', async () => {
    const user = userEvent.setup();

    render(<GovernanceWithConfig />);

    await navigateToGovernancePage(user);

    const badge = screen.getByTestId('inflight-proposals-section-badge-count');
    expect(badge).toHaveTextContent('');
  });

  test('vote history details show the actual effective time for closed votes without targetEffectiveAt', async () => {
    const user = userEvent.setup();

    await login(user);

    await navigateToGovernancePage(user);

    // The first DsoRules vote result simulates an old-model accepted vote:
    // no targetEffectiveAt on the request, actual effective time on the outcome.
    // outcome.value.effectiveAt = '2024-10-01T22:10:01.253341Z', rendered at UTC+2 (TZ pinned in vitest.global-setup.ts).
    const expectedEffectiveAt = '2024-10-02 00:10 (UTC+02:00)';

    const rows = screen.getAllByTestId('vote-history-row');
    const targetRow = rows.find(
      row =>
        within(row).getByTestId('vote-history-row-vote-takes-effect').textContent ===
        expectedEffectiveAt
    );
    expect(targetRow).toBeDefined();

    await user.click(within(targetRow!).getByTestId('vote-history-row-action-name'));

    const votingInformation = await screen.findByTestId('proposal-details-voting-information');

    const voteTakesEffectDuration = within(votingInformation).getByTestId(
      'proposal-details-vote-takes-effect-duration'
    );
    expect(voteTakesEffectDuration.textContent?.trim()).not.toBe('Threshold');

    const voteTakesEffectIso = within(votingInformation).getByTestId(
      'proposal-details-vote-takes-effect-value'
    );
    expect(voteTakesEffectIso).toHaveTextContent(expectedEffectiveAt);
  });

  test('click on Details link to see Proposal Details (Action Required)', async () => {
    const user = userEvent.setup();

    render(<GovernanceWithConfig />);

    await navigateToGovernancePage(user);

    const actions = screen.getAllByTestId('action-required-card');

    const viewDetailsLink = await within(actions[0]).findByTestId('action-required-view-details');
    expect(viewDetailsLink).toBeInTheDocument();

    await user.click(viewDetailsLink);

    const proposalDetails = screen.getByTestId('proposal-details-proposal-details');
    expect(proposalDetails).toBeInTheDocument();
  });

  test('proposal details page should render all details', async () => {
    const user = userEvent.setup();

    await login(user);

    await navigateToGovernancePage(user);

    const actions = screen.getAllByTestId('action-required-card');

    const viewDetailsLink = await within(actions[0]).findByTestId('action-required-view-details');
    expect(viewDetailsLink).toBeInTheDocument();

    await user.click(viewDetailsLink);

    const proposalDetails = screen.getByTestId('proposal-details-proposal-details');
    expect(proposalDetails).toBeInTheDocument();

    const action = screen.getByTestId('proposal-details-action-value');
    expect(action).toBeInTheDocument();

    const summary = screen.getByTestId('proposal-details-summary-value');
    expect(summary).toBeInTheDocument();

    const url = screen.getByTestId('proposal-details-url');
    expect(url).toBeInTheDocument();

    const votingInformationSection = screen.getByTestId('proposal-details-voting-information');
    expect(votingInformationSection).toBeInTheDocument();

    const requesterInput = within(votingInformationSection).getByTestId(
      'proposal-details-requester-party-id'
    );
    expect(requesterInput).toBeInTheDocument();
    // Resolve SV display name (e.g. Digital-Asset-2) to full party ID for display + copy.
    expect(
      within(votingInformationSection).getByTestId('proposal-details-requester-party-id-value')
        .textContent
    ).toMatch(/::/);

    const votingClosesIso = within(votingInformationSection).getByTestId(
      'proposal-details-voting-closes-value'
    );
    expect(votingClosesIso).toBeInTheDocument();

    const voteTakesEffectDuration = within(votingInformationSection).getByTestId(
      'proposal-details-vote-takes-effect-duration'
    );
    expect(voteTakesEffectDuration).toBeInTheDocument();

    const voteTakesEffectIso = within(votingInformationSection).queryByTestId(
      'proposal-details-vote-takes-effect-value'
    );

    if (voteTakesEffectDuration.textContent?.trim() === 'Threshold') {
      expect(voteTakesEffectIso).not.toBeInTheDocument();
    } else {
      expect(voteTakesEffectIso).toBeInTheDocument();
    }

    const status = screen.getByTestId('proposal-details-status-value');
    expect(status).toBeInTheDocument();

    const votesSection = screen.getByTestId('proposal-details-votes-list');
    expect(votesSection).toBeInTheDocument();

    const votes = within(votesSection).getAllByTestId('proposal-details-vote');
    expect(votes.length).toBeGreaterThan(0);

    const editButton = screen.queryByTestId('your-vote-edit-button');
    if (editButton) {
      await user.click(editButton);
    }

    expect(screen.getByTestId('your-vote-form')).toBeInTheDocument();
    expect(screen.getByTestId('your-vote-url-input')).toBeInTheDocument();
    expect(screen.getByTestId('your-vote-reason-input')).toBeInTheDocument();
    expect(screen.getByTestId('your-vote-accept')).toBeInTheDocument();
    expect(screen.getByTestId('your-vote-reject')).toBeInTheDocument();
  });

  describe('Proposal Search', () => {
    test('renders search field and filters action required by full contract ID', async () => {
      const user = userEvent.setup();
      await login(user);
      await navigateToGovernancePage(user);

      expect(screen.getByTestId('proposal-search')).toBeInTheDocument();
      expect(screen.getAllByTestId('action-required-card')).toHaveLength(4);

      fireEvent.change(screen.getByTestId('proposal-search-input'), {
        target: { value: activeProposalCid },
      });

      await waitFor(() => {
        expect(screen.getByTestId('proposal-search-clear')).toBeInTheDocument();
        expect(screen.getAllByTestId('action-required-card')).toHaveLength(1);
      });
    });

    test('filters vote history to matching full contract ID', async () => {
      const user = userEvent.setup();
      await login(user);
      await navigateToGovernancePage(user);

      expect(screen.getAllByTestId('vote-history-row')).toHaveLength(5);

      fireEvent.change(screen.getByTestId('proposal-search-input'), {
        target: { value: closedVoteCid },
      });

      await waitFor(() => {
        expect(screen.getAllByTestId('vote-history-row')).toHaveLength(1);
      });
    });

    test('invalid contract ID shows validation and does not filter', async () => {
      const user = userEvent.setup();
      await login(user);
      await navigateToGovernancePage(user);

      const input = screen.getByTestId('proposal-search-input');
      fireEvent.change(input, { target: { value: 'not-a-valid-contract-id' } });

      expect(screen.getByText(CONTRACT_ID_VALIDATION_MESSAGE)).toBeInTheDocument();
      expect(screen.getAllByTestId('action-required-card')).toHaveLength(4);
      fireEvent.keyDown(input, { key: 'Enter' });
      expect(screen.getByTestId('governance-page-header')).toBeInTheDocument();
      expect(screen.queryByTestId('proposal-details-title')).not.toBeInTheDocument();
    });

    test('contract ID search navigates to proposal details on enter', async () => {
      const user = userEvent.setup();
      await login(user);
      await navigateToGovernancePage(user);

      const input = screen.getByTestId('proposal-search-input');
      fireEvent.change(input, { target: { value: activeProposalCid } });
      fireEvent.keyDown(input, { key: 'Enter' });

      expect(await screen.findByTestId('proposal-details-title')).toBeInTheDocument();
    });
  });
});
