// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { render, screen } from '@testing-library/react';
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

    const title = screen.getByTestId('governance-page-title');
    expect(title).toBeDefined();
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

  test('should show the correct number of Inflight Vote Requests', async () => {
    const user = userEvent.setup();

    render(<GovernanceWithConfig />);

    await navigateToGovernancePage(user);

    expect(() => screen.getAllByTestId('inflight-vote-requests-row')).toThrowError(
      /Unable to find an element/
    );
  });

  test('should correctly display the number of completed Vote Requests (History)', async () => {
    const user = userEvent.setup();

    render(<GovernanceWithConfig />);

    await navigateToGovernancePage(user);

    const voteRequests = screen.getAllByTestId('vote-history-row');
    expect(voteRequests.length).toBe(5);

    expect(true).toBe(true);
  });
});
