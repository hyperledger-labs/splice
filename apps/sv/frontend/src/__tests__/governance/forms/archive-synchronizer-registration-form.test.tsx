// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, test, vi } from 'vitest';
import userEvent from '@testing-library/user-event';
import { ArchiveSynchronizerRegistrationForm } from '../../../components/forms/ArchiveSynchronizerRegistrationForm';
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
const operator = activeSynchronizerRegistrationPayload.operator;
const registrationCid = activeSynchronizerRegistration.contract_id;

const duplicateOperator = 'operator::1220f00dfeed';
const duplicateRegistration = {
  ...activeSynchronizerRegistration,
  contract_id: registrationCid.replace(/^00a1/, '00b2'),
  payload: { ...activeSynchronizerRegistrationPayload, operator: duplicateOperator },
};
const duplicateCid = duplicateRegistration.contract_id;

const openVoteOn = (tag: string, registeredSynchronizerCid: string) => ({
  data: [
    {
      payload: {
        action: {
          tag: 'ARC_DsoRules',
          value: { dsoAction: { tag, value: { registeredSynchronizerCid } } },
        },
      },
    },
  ],
});

beforeEach(() => {
  vi.clearAllMocks();
  mockListRegistrations.mockResolvedValue({ registrations: [activeSynchronizerRegistration] });
  mockVoteRequests.mockReturnValue({ data: [] });
});

const renderForm = () =>
  render(
    <Wrapper>
      <ArchiveSynchronizerRegistrationForm />
    </Wrapper>
  );

// Blurring runs the lookup without waiting out the typing debounce.
const enter = async (user: ReturnType<typeof userEvent.setup>, testId: string, value: string) => {
  await user.type(screen.getByTestId(testId), value);
  await user.click(screen.getByTestId('archive-synchronizer-registration-action'));
};

const pickRegistration = (contractId: string) => {
  const dropdown = screen.getByTestId('archive-synchronizer-registration-contract-id-dropdown');
  fireEvent.change(dropdown, { target: { value: contractId } });
  fireEvent.blur(dropdown);
};

const fillAndReview = async (user: ReturnType<typeof userEvent.setup>) => {
  await user.type(
    screen.getByTestId('archive-synchronizer-registration-summary'),
    'Offboard a synchronizer'
  );
  await user.type(
    screen.getByTestId('archive-synchronizer-registration-url'),
    'https://example.com'
  );
  const submitButton = screen.getByTestId('submit-button');
  await waitFor(() => expect(submitButton.getAttribute('disabled')).toBeNull());
  await user.click(submitButton);
  await screen.findByTestId('proposal-review');
};

describe('Offboard Dedicated Synchronizer Form', () => {
  test('renders the form, with no registration to pick before a synchronizer id', () => {
    renderForm();

    expect(screen.getByTestId('archive-synchronizer-registration-form')).toBeInTheDocument();
    expect(screen.getByTestId('archive-synchronizer-registration-action').textContent).toBe(
      'Offboard Dedicated Synchronizer'
    );
    expect(
      screen.getByTestId('archive-synchronizer-registration-synchronizer-id')
    ).toBeInTheDocument();
    expect(
      screen.getByTestId('archive-synchronizer-registration-contract-id-dropdown')
    ).toBeDisabled();
  });

  test('picks the only registration of a synchronizer id', async () => {
    const user = userEvent.setup();
    renderForm();

    await enter(user, 'archive-synchronizer-registration-synchronizer-id', synchronizerId);

    await screen.findByTestId('archive-synchronizer-registration-resolved');
    expect(mockListRegistrations).toHaveBeenCalledWith(synchronizerId);
    expect(
      screen.getByTestId('archive-synchronizer-registration-resolved-contract-id-field').textContent
    ).toBe(registrationCid);
    expect(
      screen.getByTestId('archive-synchronizer-registration-resolved-operator-party-id')
    ).toHaveTextContent(operator);
  });

  test('lets the proposer pick among duplicate registrations', async () => {
    mockListRegistrations.mockResolvedValue({
      registrations: [activeSynchronizerRegistration, duplicateRegistration],
    });
    const user = userEvent.setup();
    renderForm();

    await enter(user, 'archive-synchronizer-registration-synchronizer-id', synchronizerId);

    const dropdown = screen.getByTestId('archive-synchronizer-registration-contract-id-dropdown');
    await waitFor(() => expect(dropdown).not.toBeDisabled());
    expect(
      screen.queryByTestId('archive-synchronizer-registration-resolved')
    ).not.toBeInTheDocument();

    pickRegistration(duplicateCid);

    await screen.findByTestId('archive-synchronizer-registration-resolved');
    expect(
      screen.getByTestId('archive-synchronizer-registration-resolved-contract-id-field').textContent
    ).toBe(duplicateCid);
    expect(
      screen.getByTestId('archive-synchronizer-registration-resolved-operator-party-id')
    ).toHaveTextContent(duplicateOperator);
  });

  test('rejects a synchronizer id that is not registered', async () => {
    mockListRegistrations.mockResolvedValue({ registrations: [] });
    const user = userEvent.setup();
    renderForm();

    await enter(user, 'archive-synchronizer-registration-synchronizer-id', synchronizerId);

    expect(await screen.findByText(/No registration found/)).toBeInTheDocument();
    expect(
      screen.queryByTestId('archive-synchronizer-registration-resolved')
    ).not.toBeInTheDocument();
  });

  test('does not look up a malformed synchronizer id', async () => {
    const user = userEvent.setup();
    renderForm();

    await enter(user, 'archive-synchronizer-registration-synchronizer-id', 'not-an-id');

    expect(await screen.findByText(/Invalid synchronizer id/)).toBeInTheDocument();
    expect(mockListRegistrations).not.toHaveBeenCalled();
  });

  test('a failing lookup blocks the proposal, since there is no contract to target', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    mockListRegistrations.mockRejectedValue(new Error('the SV app is unreachable'));
    const user = userEvent.setup();
    renderForm();

    await enter(user, 'archive-synchronizer-registration-synchronizer-id', synchronizerId);

    expect(await screen.findByText(/Could not look up the registration/)).toBeInTheDocument();
  });

  test('rejects a registration another open proposal already targets', async () => {
    mockVoteRequests.mockReturnValue(
      openVoteOn('SRARC_SetSynchronizerGovernanceParameters', registrationCid)
    );
    const user = userEvent.setup();
    renderForm();

    await enter(user, 'archive-synchronizer-registration-synchronizer-id', synchronizerId);

    expect(await screen.findByText(/Another open proposal/)).toBeInTheDocument();
  });

  test('reviews the resolved registration', async () => {
    const user = userEvent.setup();
    renderForm();

    await enter(user, 'archive-synchronizer-registration-synchronizer-id', synchronizerId);
    await screen.findByTestId('archive-synchronizer-registration-resolved');
    await fillAndReview(user);

    expect(screen.getByTestId('registeredSynchronizerCid-field').textContent).toBe(registrationCid);
    expect(screen.getByTestId('synchronizerId-field').textContent).toBe(synchronizerId);
  });

  test('a proposal pins the duplicate registration that was picked', async () => {
    mockListRegistrations.mockResolvedValue({
      registrations: [activeSynchronizerRegistration, duplicateRegistration],
    });
    const user = userEvent.setup();
    renderForm();

    await enter(user, 'archive-synchronizer-registration-synchronizer-id', synchronizerId);
    await waitFor(() =>
      expect(
        screen.getByTestId('archive-synchronizer-registration-contract-id-dropdown')
      ).not.toBeDisabled()
    );
    pickRegistration(duplicateCid);
    await screen.findByTestId('archive-synchronizer-registration-resolved');
    await fillAndReview(user);

    expect(screen.getByTestId('registeredSynchronizerCid-field').textContent).toBe(duplicateCid);
  });
});
