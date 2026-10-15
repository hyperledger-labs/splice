// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { mockAllIsIntersecting } from 'react-intersection-observer/test-utils';
import { test, expect, describe } from 'vitest';

import App from '../App';
import { SvConfigProvider } from '../utils';
import { onboardingInfo } from '../components/ValidatorOnboardingSecrets';
import { svPartyId } from './mocks/constants';
import { navigateToGovernancePage } from './helpers';
import { getUTCWithOffset } from '@canton-network/splice-common-frontend-utils';

const AppWithConfig = () => {
  return (
    <SvConfigProvider>
      <App />
    </SvConfigProvider>
  );
};

describe('SV user can', () => {
  test('login and see the SV party ID', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    expect(await screen.findByText('Log In')).toBeDefined();

    const input = screen.getByRole('textbox');
    await user.type(input, 'sv1');

    const button = screen.getByRole('button', { name: 'Log In' });
    user.click(button);

    expect(await screen.findAllByDisplayValue(svPartyId)).toBeDefined();
  });

  test('can see the network name banner', async () => {
    userEvent.setup();
    render(<AppWithConfig />);

    await screen.findByText('You are on ScratchNet');
  });

  test('browse to the validator onboarding tab', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    expect(await screen.findByText('Validators')).toBeDefined();
    await user.click(screen.getByText('Validators'));

    expect(await screen.findByText('Validator Onboarding Secrets')).toBeDefined();
  });

  test('create a new validator secret with party hint', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    expect(await screen.findByText('Validators')).toBeDefined();
    await user.click(screen.getByText('Validators'));

    const partyHintInput = screen.getByTestId('create-party-hint');
    await user.type(partyHintInput, 'wrong-input');

    expect(screen.getByTestId('create-validator-onboarding-secret').hasAttribute('disabled')).toBe(
      true
    );

    await user.clear(partyHintInput);
    await user.type(partyHintInput, 'correct-input-123');

    expect(screen.getByTestId('create-validator-onboarding-secret').hasAttribute('disabled')).toBe(
      false
    );
  });

  test('validator onboarding info has correct format', () => {
    const validatorOnboardingInfo = onboardingInfo(
      {
        partyHint: 'splice-client-2',
        secret: 'exampleSecret',
        expiresAt: '2020-01-01 13:57',
      },
      'testnet'
    );

    expect(validatorOnboardingInfo).toBe(
      `
splice-client-2
Network: testnet
SPONSOR_SV_URL
http://localhost:3000

Secret
exampleSecret

Expiration
2020-01-01 13:57 (${getUTCWithOffset()})
    `.trim()
    );
  });

  test('browse to the governance tab', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    await navigateToGovernancePage(user);

    expect(await screen.findByTestId('governance-page-header-title')).toBeInTheDocument();
  });
});

describe('An AddFutureAmuletConfigSchedule request', () => {
  test('is displayed in the vote history when its effective date is in the past', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    await navigateToGovernancePage(user);

    // Deprecated from dsoGovernance 0.1.15 (should still be displayed in the vote history)
    const actionNames = await screen.findAllByTestId('vote-history-row-action-name');
    expect(actionNames.some(e => e.textContent?.includes('Add Future'))).toBe(true);
  });

  test('validator licenses and secrets are displayed and paginable', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    expect(await screen.findByText('Validators')).toBeDefined();
    await user.click(screen.getByText('Validators'));

    expect(await screen.findByText('Validator Licenses')).toBeDefined();

    expect(await screen.findByDisplayValue('validator::1')).toBeDefined();
    expect(screen.queryByText('validator::15')).toBeNull();

    mockAllIsIntersecting(true);
    expect(await screen.findByDisplayValue('validator::15')).toBeDefined();

    // secrets
    expect(screen.queryByText('encoded_secret')).not.toBeNull();
    expect(screen.queryByText('candidate_secret')).toBeNull();
  });
});
