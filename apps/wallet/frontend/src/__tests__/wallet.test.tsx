// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { test, expect, describe } from 'vitest';

import App from '../App';
import { WalletConfigProvider } from '../utils/config';
import { aliceEntry, nameServiceEntries, userLogin } from './mocks/constants';

const dsoEntry = nameServiceEntries.find(e => e.name.startsWith('dso'))!;

test('login screen shows up', async () => {
  render(
    <WalletConfigProvider>
      <App />
    </WalletConfigProvider>
  );
  expect(() => screen.findByText('Log In')).toBeDefined();
});

describe('Wallet user can', () => {
  test('login and see the user party ID', async () => {
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
    user.click(button);

    expect(await screen.findByText(aliceEntry.name)).toBeDefined();
  });

  test('not see dso in list of transfer-offer receivers', async () => {
    const user = userEvent.setup();
    render(
      <WalletConfigProvider>
        <App />
      </WalletConfigProvider>
    );
    expect(await screen.findByText('Transfer')).toBeDefined();

    const transferOffersLink = screen.getByRole('link', { name: 'Transfer' });
    await user.click(transferOffersLink);
    expect(screen.getByRole('heading', { name: 'Transfers' })).toBeDefined();

    // the listbox is not visible, so we have to click the input
    const receiverInput = screen
      .getAllByRole('combobox')
      .find(e => e.id === 'create-offer-receiver')!;
    await user.click(receiverInput);
    expect(screen.getByRole('listbox')).toBeDefined();

    const receiversListbox = screen.getByRole('listbox');
    const entries = within(receiversListbox)
      .getAllByRole('option')
      .map(e => e.textContent);

    expect(entries.length).toBeGreaterThan(1);
    expect(entries.find(e => e === dsoEntry.name)).toBeUndefined();
  });
});
