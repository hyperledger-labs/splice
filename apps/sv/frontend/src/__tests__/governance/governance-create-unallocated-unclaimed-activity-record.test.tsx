// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import { MemoryRouter } from 'react-router';
import { describe, expect, test, vi } from 'vitest';

import CreateUnallocatedUnclaimedActivityRecord from '../../components/votes/actions/CreateUnallocatedUnclaimedActivityRecord';
import { SvConfigProvider } from '../../utils';

dayjs.extend(utc);

describe('UnallocatedUnclaimedActivityRecord Amount Validation', () => {
  test('validates amount input', async () => {
    const user = userEvent.setup();

    const dummyChooseAction = () => {};
    const dummySetIsValidAmount = () => {};
    const mockEffectivity = dayjs();

    render(
      <MemoryRouter>
        <SvConfigProvider>
          <CreateUnallocatedUnclaimedActivityRecord
            chooseAction={dummyChooseAction}
            effectivity={mockEffectivity}
            setIsValidAmount={dummySetIsValidAmount}
            summary="Alice is doing great"
          />
        </SvConfigProvider>
      </MemoryRouter>
    );

    const amountInput = screen.getByTestId('create-amount');

    // Invalid: negative
    await user.clear(amountInput);
    await user.type(amountInput, '-50');
    expect(await screen.findByText('Amount must be a positive number')).toBeDefined();

    // Invalid: non-numeric
    await user.clear(amountInput);
    await user.type(amountInput, 'abc');
    expect(await screen.findByText('Amount must be a positive number')).toBeDefined();

    // Valid: numeric
    await user.clear(amountInput);
    await user.type(amountInput, '1000');
    expect(screen.queryByText('Amount must be a positive number')).toBeNull();
  });
});

describe('CreateUnallocatedUnclaimedActivityRecord - UTC Conversion', () => {
  test('converts default local datetime to UTC before calling chooseAction', async () => {
    const mockChooseAction = vi.fn();
    const dummySetIsValidAmount = () => {};
    const mockEffectivity = dayjs('2025-07-18T00:00:00');

    render(
      <MemoryRouter>
        <SvConfigProvider>
          <CreateUnallocatedUnclaimedActivityRecord
            chooseAction={mockChooseAction}
            effectivity={mockEffectivity}
            setIsValidAmount={dummySetIsValidAmount}
            summary="Alice is doing great"
          />
        </SvConfigProvider>
      </MemoryRouter>
    );

    const dateInput = screen.getByTestId('datetime-picker-unallocated-expires-at');
    const amountInput = screen.getByTestId('create-amount');
    const beneficiaryInput = screen.getByTestId('create-beneficiary');

    const defaultDate = dateInput.getAttribute('value');
    expect(defaultDate).toBeTruthy();

    // Fill out other required fields
    fireEvent.change(amountInput, { target: { value: '100' } });
    fireEvent.change(beneficiaryInput, { target: { value: 'alice' } });

    // Wait until form submission is triggered
    await waitFor(() => {
      expect(mockChooseAction).toHaveBeenCalled();
    });

    // Extract the expected UTC string from the default local time
    const expectedUTC = dayjs(defaultDate!).utc().toISOString();

    const lastCall = mockChooseAction.mock.calls.at(-1)?.[0];
    const actualUTC = lastCall?.value?.dsoAction?.value?.expiresAt;

    expect(actualUTC).toBe(expectedUTC);
  });
});
