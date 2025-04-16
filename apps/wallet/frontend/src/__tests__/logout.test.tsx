// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { rest } from 'msw';
import { describe, expect, test } from 'vitest';
import { UserStatusResponse } from 'wallet-openapi';

import App from '../App';
import { WalletConfigProvider } from '../utils/config';
import { aliceEntry, alicePartyId, userLogin } from './mocks/constants';
import { server } from './setup/setup';

const walletUrl = window.splice_config.services.validator.url;

function userStatusHandler(user_onboarded: boolean) {
  return rest.get(`${walletUrl}/v0/wallet/user-status`, (_, res, ctx) => {
    return res(
      ctx.json<UserStatusResponse>({
        party_id: alicePartyId,
        user_onboarded: user_onboarded,
        user_wallet_installed: true,
        has_featured_app_right: false,
      })
    );
  });
}

describe('Logout button appears', () => {
  test('when the wallet user is not onboarded', async () => {
    server.use(userStatusHandler(false));
    const user = userEvent.setup();
    render(
      <WalletConfigProvider>
        <App />
      </WalletConfigProvider>
    );

    expect(await screen.findByText('Log In')).toBeDefined();
    const input = screen.getByRole('textbox');
    await user.type(input, userLogin);
    const button = screen.getByRole('button', { name: 'Log In' });
    await user.click(button);

    expect(await screen.findByTestId('wallet-onboarding-welcome-title')).toBeDefined();

    expect(await screen.findByText('Logout')).toBeDefined();
    const logoutButton = screen.getByRole('button', { name: 'Logout' });
    await user.click(logoutButton);
  });

  test('when the wallet user is onboarded', async () => {
    const user = userEvent.setup();
    render(
      <WalletConfigProvider>
        <App />
      </WalletConfigProvider>
    );

    expect(await screen.findByText('Log In')).toBeDefined();
    const input = screen.getByRole('textbox');
    await user.type(input, userLogin);
    const button = screen.getByRole('button', { name: 'Log In' });
    await user.click(button);

    expect(await screen.findByText(aliceEntry.name)).toBeDefined();

    expect(await screen.findByText('Logout')).toBeDefined();
    const logoutButton = screen.getByRole('button', { name: 'Logout' });
    await user.click(logoutButton);
  });

  test('when the api is not responding', async () => {
    server.use(
      rest.get(`${walletUrl}/v0/wallet/user-status`, (_, res, ctx) => {
        return res(ctx.status(404));
      })
    );
    const user = userEvent.setup();
    render(
      <WalletConfigProvider>
        <App />
      </WalletConfigProvider>
    );

    expect(await screen.findByText('Log In')).toBeDefined();
    const input = screen.getByRole('textbox');
    await user.type(input, userLogin);
    const button = screen.getByRole('button', { name: 'Log In' });
    await user.click(button);

    expect(await screen.findAllByTestId('loading-spinner')).toBeDefined();

    expect(await screen.findByText('Logout')).toBeDefined();
    const logoutButton = screen.getByRole('button', { name: 'Logout' });
    await user.click(logoutButton);
  });
});
