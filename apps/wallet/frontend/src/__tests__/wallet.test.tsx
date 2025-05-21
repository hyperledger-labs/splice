// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { rest } from 'msw';
import { LookupTransferPreapprovalByPartyResponse } from 'scan-openapi';
import { test, expect, describe } from 'vitest';
import { vi } from 'vitest';

import App from '../App';
import { WalletConfigProvider } from '../utils/config';
import {
  aliceEntry,
  alicePartyId,
  aliceTransferPreapproval,
  nameServiceEntries,
  userLogin,
} from './mocks/constants';
import { requestMocks } from './mocks/handlers/transfers-api';
import { server } from './setup/setup';

const dsoEntry = nameServiceEntries.find(e => e.name.startsWith('dso'))!;

const walletUrl = window.splice_config.services.validator.url;

function featureSupportHandler(tokenStandardSupported: boolean) {
  return rest.get(`${walletUrl}/v0/feature-support`, async (_, res, ctx) => {
    return res(ctx.json({ token_standard: tokenStandardSupported }));
  });
}

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

  test('create a transfer preapproval', async () => {
    const user = userEvent.setup();
    render(
      <WalletConfigProvider>
        <App />
      </WalletConfigProvider>
    );
    const preapproveTransfersBtn = await screen.findByRole('button', {
      name: /Pre-approve incoming direct transfers/,
    });
    expect(preapproveTransfersBtn).toBeEnabled();
    await user.click(preapproveTransfersBtn);
    // Click in confirmation dialog
    const proceedBtn = await screen.findByRole('button', { name: 'Proceed' });
    await user.click(proceedBtn);
    // Check that clicking the button calls the correct backend endpoint
    expect(requestMocks.createTransferPreapproval).toHaveBeenCalled();
    // Mock the request to fetch the created pre-approval
    server.use(
      rest.get(
        `${window.splice_config.services.validator.url}/v0/scan-proxy/transfer-preapprovals/by-party/:party`,
        (req, res, ctx) => {
          const { party } = req.params;
          if (party === alicePartyId) {
            return res(
              ctx.json<LookupTransferPreapprovalByPartyResponse>({
                transfer_preapproval: aliceTransferPreapproval,
              })
            );
          } else {
            return res(ctx.status(404), ctx.json({}));
          }
        }
      )
    );
    const disabledPreapproveTransfersBtn = await screen.findByRole('button', {
      name: /Pre-approve incoming direct transfers/,
    });
    await waitFor(() => expect(disabledPreapproveTransfersBtn).toBeDisabled());
  });

  test('not see dso in list of transfer-offer receivers', async () => {
    server.use(featureSupportHandler(true));
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

  describe('Token Standard', () => {
    transferTests(false);

    test('fall back to non-token standard transfers when the token standard is not supported', async () => {
      server.use(featureSupportHandler(false));

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

      const receiverInput = screen
        .getAllByRole('combobox')
        .find(e => e.id === 'create-offer-receiver')!;
      fireEvent.change(receiverInput, { target: { value: 'bob::nopreapproval' } });
      await vi.waitFor(() => expect(screen.getByRole('button', { name: 'Send' })).toBeEnabled());
      expect(screen.queryByRole('checkbox', { name: '' })).not.toBeInTheDocument();
      expect(
        screen.queryByRole('checkbox', { name: 'Use Token Standard Transfer' })
      ).not.toBeInTheDocument();
      expect(screen.getByRole('textbox', { name: 'description' })).toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'Send' }));

      await assertCorrectMockIsCalled(
        true,
        { amount: '1.0', receiver_party_id: 'bob::nopreapproval', description: '' },
        false
      );
    });
  });

  describe('Regular transfer offer', () => {
    transferTests(true);
  });
});

function transferTests(disableTokenStandard: boolean) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  async function toggleTokenStandard(user: any): Promise<void> {
    if (disableTokenStandard) {
      await user.click(screen.getByRole('checkbox', { name: 'Use Token Standard Transfer' }));
    }
  }

  test('transfer offer is used when receiver has no transfer preapproval', async () => {
    server.use(featureSupportHandler(true));
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

    const receiverInput = screen
      .getAllByRole('combobox')
      .find(e => e.id === 'create-offer-receiver')!;
    fireEvent.change(receiverInput, { target: { value: 'bob::nopreapproval' } });
    await vi.waitFor(() => expect(screen.getByRole('button', { name: 'Send' })).toBeEnabled());
    expect(screen.queryByRole('checkbox', { name: '' })).not.toBeInTheDocument();
    await toggleTokenStandard(user);
    const description = 'Test';
    const descriptionInput = screen.getByRole('textbox', { name: 'description' });
    await user.type(descriptionInput, description);
    await user.click(screen.getByRole('button', { name: 'Send' }));

    await assertCorrectMockIsCalled(
      disableTokenStandard,
      { amount: '1.0', receiver_party_id: 'bob::nopreapproval', description },
      false
    );
  });

  test('transfer preapproval is used when receiver has a transfer preapproval', async () => {
    server.use(featureSupportHandler(true));
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

    const receiverInput = screen
      .getAllByRole('combobox')
      .find(e => e.id === 'create-offer-receiver')!;
    fireEvent.change(receiverInput, { target: { value: 'bob::preapproval' } });
    await vi.waitFor(() => expect(screen.getByRole('button', { name: 'Send' })).toBeEnabled());
    // Checkbox is there, we don't change it though as the default uses the preapproval
    expect(screen.getByRole('checkbox', { name: '' })).toBeInTheDocument();
    await toggleTokenStandard(user);
    const description = 'Pre';
    const descriptionInput = screen.getByRole('textbox', { name: 'description' });
    await user.type(descriptionInput, description);
    await user.click(screen.getByRole('button', { name: 'Send' }));

    await assertCorrectMockIsCalled(
      disableTokenStandard,
      { amount: '1.0', receiver_party_id: 'bob::preapproval', description },
      true
    );
  });

  test('transfer offer is used when receiver has a transfer preapproval but checkbox is unchecked', async () => {
    server.use(featureSupportHandler(true));
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

    const receiverInput = screen
      .getAllByRole('combobox')
      .find(e => e.id === 'create-offer-receiver')!;
    fireEvent.change(receiverInput, { target: { value: 'bob::preapproval' } });
    await vi.waitFor(() => expect(screen.getByRole('button', { name: 'Send' })).toBeEnabled());
    expect(screen.getByRole('checkbox', { name: '' })).toBeInTheDocument();
    await toggleTokenStandard(user);
    await user.click(screen.getByRole('checkbox', { name: '' }));
    const description = 'Pre2';
    const descriptionInput = screen.getByRole('textbox', { name: 'description' });
    await user.type(descriptionInput, description);
    await user.click(screen.getByRole('button', { name: 'Send' }));

    await assertCorrectMockIsCalled(
      disableTokenStandard,
      { amount: '1.0', receiver_party_id: 'bob::preapproval', description },
      false
    );
  });

  test('deduplication id is passed', async () => {
    server.use(featureSupportHandler(true));
    const user = userEvent.setup();
    render(
      <WalletConfigProvider>
        <App />
      </WalletConfigProvider>
    );
    expect(await screen.findByText('Transfer')).toBeDefined();

    let transferOffersLink = screen.getByRole('link', { name: 'Transfer' });
    await user.click(transferOffersLink);
    expect(screen.getByRole('heading', { name: 'Transfers' })).toBeDefined();

    let receiverInput = screen
      .getAllByRole('combobox')
      .find(e => e.id === 'create-offer-receiver')!;
    fireEvent.change(receiverInput, { target: { value: 'bob::preapproval' } });
    await vi.waitFor(() => expect(screen.getByRole('button', { name: 'Send' })).toBeEnabled());
    const mock = disableTokenStandard
      ? requestMocks.transferPreapprovalSend
      : requestMocks.createTransferViaTokenStandard;
    mock.mockImplementationOnce(() => {
      throw new Error('Request failed');
    });
    await toggleTokenStandard(user);
    await user.click(screen.getByRole('button', { name: 'Send' }));

    expect(mock).toHaveBeenCalledTimes(1);
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    function getDeduplicationIdFromCall(call: any) {
      return call.deduplication_id || call.tracking_id;
    }
    const firstDeduplicationId = getDeduplicationIdFromCall(mock.mock.lastCall![0]);
    await user.click(screen.getByRole('button', { name: 'Send' }));
    expect(mock).toHaveBeenCalledTimes(2);
    const secondDeduplicationId = getDeduplicationIdFromCall(mock.mock.lastCall![0]);
    expect(firstDeduplicationId).toBe(secondDeduplicationId);

    render(
      <WalletConfigProvider>
        <App />
      </WalletConfigProvider>
    );
    expect(await screen.findByText('Transfer')).toBeDefined();

    transferOffersLink = screen.getByRole('link', { name: 'Transfer' });
    await user.click(transferOffersLink);
    expect(screen.getByRole('heading', { name: 'Transfers' })).toBeDefined();

    receiverInput = screen.getAllByRole('combobox').find(e => e.id === 'create-offer-receiver')!;
    fireEvent.change(receiverInput, { target: { value: 'bob::preapproval' } });
    await vi.waitFor(() => expect(screen.getByRole('button', { name: 'Send' })).toBeEnabled());
    mock.mockImplementationOnce(() => {
      throw new Error('Request failed');
    });
    await toggleTokenStandard(user);
    await user.click(screen.getByRole('button', { name: 'Send' }));

    expect(mock).toHaveBeenCalledTimes(3);
    const thirdDeduplicationId = getDeduplicationIdFromCall(mock.mock.lastCall![0]);
    expect(thirdDeduplicationId).not.toBe(firstDeduplicationId);
  }, 10000);
}

async function assertCorrectMockIsCalled(
  usesRegularTransferOffer: boolean,
  expected: { amount: string; receiver_party_id: string; description: string },
  isPreapproval: boolean
) {
  if (!usesRegularTransferOffer) {
    expect(requestMocks.createTransferViaTokenStandard).toHaveBeenCalledWith(
      expect.objectContaining(expected)
    );
    expect(requestMocks.transferPreapprovalSend).not.toHaveBeenCalled();
    expect(requestMocks.createTransferOffer).not.toHaveBeenCalled();
  } else if (isPreapproval) {
    expect(requestMocks.transferPreapprovalSend).toHaveBeenCalledWith(
      expect.objectContaining(expected)
    );
    expect(requestMocks.createTransferOffer).not.toHaveBeenCalled();
    expect(requestMocks.createTransferViaTokenStandard).not.toHaveBeenCalled();
  } else {
    expect(requestMocks.createTransferOffer).toHaveBeenCalledWith(
      expect.objectContaining(expected)
    );
    expect(requestMocks.transferPreapprovalSend).not.toHaveBeenCalled();
    expect(requestMocks.createTransferViaTokenStandard).not.toHaveBeenCalled();
  }
}
