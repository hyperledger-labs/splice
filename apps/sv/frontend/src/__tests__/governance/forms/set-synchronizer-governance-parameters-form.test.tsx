// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, test, vi } from 'vitest';
import userEvent from '@testing-library/user-event';
import { SetSynchronizerGovernanceParametersForm } from '../../../components/forms/SetSynchronizerGovernanceParametersForm';
import { Wrapper } from '../../helpers';
import {
  activeSynchronizerRegistration,
  activeSynchronizerRegistrationPayload,
} from '../../mocks/constants';

// The lookup needs an authenticated SV admin client, which the render wrapper does not set
// up, so the sources the form reads are mocked and the component's own logic tested.
const mockListRegistrations = vi.fn();
const mockVoteRequests = vi.fn();
vi.mock('../../../contexts/SvAdminServiceContext', async importOriginal => ({
  ...(await importOriginal<typeof import('../../../contexts/SvAdminServiceContext')>()),
  useSvAdminClient: () => ({ listSynchronizerRegistrations: mockListRegistrations }),
}));
vi.mock('../../../hooks/useListVoteRequests', () => ({
  useListDsoRulesVoteRequests: () => mockVoteRequests(),
}));

const synchronizerId = activeSynchronizerRegistrationPayload.synchronizerId;
const registrationCid = activeSynchronizerRegistration.contract_id;
const prefix = 'set-synchronizer-governance-parameters';

const duplicateRegistration = {
  ...activeSynchronizerRegistration,
  contract_id: registrationCid.replace(/^00a1/, '00b2'),
  payload: {
    ...activeSynchronizerRegistrationPayload,
    governanceParameters: { discountFactor: '0.6000000000' },
  },
};
const duplicateCid = duplicateRegistration.contract_id;

beforeEach(() => {
  vi.clearAllMocks();
  mockListRegistrations.mockResolvedValue({ registrations: [activeSynchronizerRegistration] });
  mockVoteRequests.mockReturnValue({ data: [] });
});

const renderForm = () =>
  render(
    <Wrapper>
      <SetSynchronizerGovernanceParametersForm />
    </Wrapper>
  );

// Blurring runs the lookup without waiting out the typing debounce.
const enterSynchronizerId = async (user: ReturnType<typeof userEvent.setup>, value: string) => {
  await user.type(screen.getByTestId(`${prefix}-synchronizer-id`), value);
  await user.click(screen.getByTestId(`${prefix}-action`));
};

describe('Set Dedicated Synchronizer Parameters Form', () => {
  test('renders the form without parameter fields until a registration is found', () => {
    renderForm();

    expect(screen.getByTestId(`${prefix}-form`)).toBeInTheDocument();
    expect(screen.getByTestId(`${prefix}-action`).textContent).toBe(
      'Set Dedicated Synchronizer Parameters'
    );
    expect(screen.getByTestId(`${prefix}-synchronizer-id`)).toBeInTheDocument();
    expect(screen.queryByTestId(`${prefix}-discountFactor`)).not.toBeInTheDocument();
  });

  test('shows the registration and prefills its current parameters', async () => {
    const user = userEvent.setup();
    renderForm();

    await enterSynchronizerId(user, synchronizerId);

    await screen.findByTestId(`${prefix}-resolved`);
    expect(mockListRegistrations).toHaveBeenCalledWith(synchronizerId);
    expect(screen.getByTestId(`${prefix}-resolved-contract-id-field`).textContent).toBe(
      registrationCid
    );
    expect(screen.getByTestId(`${prefix}-resolved-current-discountFactor-field`).textContent).toBe(
      '0.8000000000'
    );
    await waitFor(() =>
      expect(screen.getByTestId(`${prefix}-discountFactor`).getAttribute('value')).toBe(
        '0.8000000000'
      )
    );
  });

  test('picks the only registration without a duplicates warning', async () => {
    const user = userEvent.setup();
    renderForm();

    await enterSynchronizerId(user, synchronizerId);

    await screen.findByTestId(`${prefix}-resolved`);
    expect(screen.getByTestId(`${prefix}-contract-id`)).toHaveTextContent(registrationCid);
    expect(screen.queryByTestId(`${prefix}-duplicates-warning`)).not.toBeInTheDocument();
  });

  test('warns about duplicate registrations and lets the proposer pick one', async () => {
    mockListRegistrations.mockResolvedValue({
      registrations: [activeSynchronizerRegistration, duplicateRegistration],
    });
    const user = userEvent.setup();
    renderForm();

    await enterSynchronizerId(user, synchronizerId);

    expect(await screen.findByTestId(`${prefix}-duplicates-warning`)).toHaveTextContent(
      /2 registrations/
    );
    expect(screen.queryByTestId(`${prefix}-resolved`)).not.toBeInTheDocument();

    const dropdown = screen.getByTestId(`${prefix}-contract-id-dropdown`);
    fireEvent.change(dropdown, { target: { value: duplicateCid } });
    fireEvent.blur(dropdown);

    await screen.findByTestId(`${prefix}-resolved`);
    expect(screen.getByTestId(`${prefix}-resolved-contract-id-field`).textContent).toBe(
      duplicateCid
    );
    await waitFor(() =>
      expect(screen.getByTestId(`${prefix}-discountFactor`).getAttribute('value')).toBe(
        '0.6000000000'
      )
    );
  });

  test('rejects a synchronizer id that is not registered', async () => {
    mockListRegistrations.mockResolvedValue({ registrations: [] });
    const user = userEvent.setup();
    renderForm();

    await enterSynchronizerId(user, synchronizerId);

    expect(await screen.findByText(/No registration found/)).toBeInTheDocument();
    expect(screen.queryByTestId(`${prefix}-discountFactor`)).not.toBeInTheDocument();
  });

  test('does not look up a malformed synchronizer id', async () => {
    const user = userEvent.setup();
    renderForm();

    await enterSynchronizerId(user, 'not-an-id');

    expect(await screen.findByText(/Invalid synchronizer id/)).toBeInTheDocument();
    expect(mockListRegistrations).not.toHaveBeenCalled();
  });

  test('a failing lookup blocks the proposal, since there is no contract to target', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    mockListRegistrations.mockRejectedValue(new Error('scan is unreachable'));
    const user = userEvent.setup();
    renderForm();

    await enterSynchronizerId(user, synchronizerId);

    expect(await screen.findByText(/Could not look up the registration/)).toBeInTheDocument();
  });

  test('rejects a registration another open proposal already targets', async () => {
    mockVoteRequests.mockReturnValue({
      data: [
        {
          payload: {
            action: {
              tag: 'ARC_DsoRules',
              value: {
                dsoAction: {
                  tag: 'SRARC_ArchiveSynchronizerRegistration',
                  value: { registeredSynchronizerCid: registrationCid },
                },
              },
            },
          },
        },
      ],
    });
    const user = userEvent.setup();
    renderForm();

    await enterSynchronizerId(user, synchronizerId);

    expect(await screen.findByText(/Another open proposal/)).toBeInTheDocument();
  });

  test('bounds the discount factor to (0, 1]', async () => {
    const user = userEvent.setup();
    renderForm();
    await enterSynchronizerId(user, synchronizerId);
    const discountInput = await screen.findByTestId(`${prefix}-discountFactor`);

    await user.clear(discountInput);
    await user.type(discountInput, '1.5');
    await user.click(screen.getByTestId(`${prefix}-action`));
    screen.getByText('Must be greater than 0 and at most 1');

    await user.clear(discountInput);
    await user.type(discountInput, '0.5');
    await user.click(screen.getByTestId(`${prefix}-action`));
    expect(screen.queryByText('Must be greater than 0 and at most 1')).not.toBeInTheDocument();
  });

  test('requires at least one parameter to change', async () => {
    const user = userEvent.setup();
    renderForm();
    await enterSynchronizerId(user, synchronizerId);
    const discountInput = await screen.findByTestId(`${prefix}-discountFactor`);

    // The same value written differently is still no change.
    await user.clear(discountInput);
    await user.type(discountInput, '0.8000000000');
    expect(await screen.findByText(/are the current ones/)).toBeInTheDocument();

    await user.clear(discountInput);
    await user.type(discountInput, '0.5');
    await waitFor(() => expect(screen.queryByText(/are the current ones/)).not.toBeInTheDocument());
  });

  test('reviews the current and proposed parameters side by side', async () => {
    const user = userEvent.setup();
    renderForm();

    await user.type(screen.getByTestId(`${prefix}-summary`), 'Lower the discount');
    await user.type(screen.getByTestId(`${prefix}-url`), 'https://example.com');
    await enterSynchronizerId(user, synchronizerId);
    const discountInput = await screen.findByTestId(`${prefix}-discountFactor`);
    await user.clear(discountInput);
    await user.type(discountInput, '0.5');

    const submitButton = screen.getByTestId('submit-button');
    await waitFor(() => expect(submitButton.getAttribute('disabled')).toBeNull());
    await user.click(submitButton);

    await screen.findByTestId('proposal-review');
    expect(screen.getByTestId('registeredSynchronizerCid-field').textContent).toBe(registrationCid);
    const changes = screen.getByTestId('governanceParameters-field');
    expect(within(changes).getByTestId('config-change-current-value').textContent).toBe(
      '0.8000000000'
    );
    expect(within(changes).getByTestId('config-change-new-value').textContent).toBe('0.5');
  });

  test('keeps an edited parameter when the registration is looked up again', async () => {
    const user = userEvent.setup();
    renderForm();
    await enterSynchronizerId(user, synchronizerId);
    const discountInput = await screen.findByTestId(`${prefix}-discountFactor`);
    await waitFor(() => expect(discountInput.getAttribute('value')).toBe('0.8000000000'));
    // Typing the id also scheduled a debounced lookup, which the blur does not cancel. Let it
    // land, so the count below is only the lookup the second blur runs.
    await waitFor(() => expect(mockListRegistrations).toHaveBeenCalledTimes(2));
    await user.clear(discountInput);
    await user.type(discountInput, '0.5');

    // Blurring the synchronizer id runs its lookup again, as submitting does.
    await user.click(screen.getByTestId(`${prefix}-synchronizer-id`));
    await user.click(screen.getByTestId(`${prefix}-action`));
    await waitFor(() => expect(mockListRegistrations).toHaveBeenCalledTimes(3));

    expect(screen.getByTestId(`${prefix}-discountFactor`).getAttribute('value')).toBe('0.5');
  });

  test('a slow lookup on review does not undo an edited parameter', async () => {
    const user = userEvent.setup();
    renderForm();

    await user.type(screen.getByTestId(`${prefix}-summary`), 'Lower the discount');
    await user.type(screen.getByTestId(`${prefix}-url`), 'https://example.com');
    await enterSynchronizerId(user, synchronizerId);
    const discountInput = await screen.findByTestId(`${prefix}-discountFactor`);
    await waitFor(() => expect(discountInput.getAttribute('value')).toBe('0.8000000000'));
    await user.clear(discountInput);
    await user.type(discountInput, '0.5');

    // Submitting re-runs the lookup; against a real backend it answers after a delay.
    mockListRegistrations.mockImplementation(
      () =>
        new Promise(resolve =>
          setTimeout(() => resolve({ registrations: [activeSynchronizerRegistration] }), 50)
        )
    );
    const submitButton = screen.getByTestId('submit-button');
    await waitFor(() => expect(submitButton.getAttribute('disabled')).toBeNull());
    await user.click(submitButton);

    await screen.findByTestId('proposal-review');
    expect(screen.queryByText(/are the current ones/)).not.toBeInTheDocument();
    const changes = screen.getByTestId('governanceParameters-field');
    expect(within(changes).getByTestId('config-change-new-value').textContent).toBe('0.5');
  });
});
