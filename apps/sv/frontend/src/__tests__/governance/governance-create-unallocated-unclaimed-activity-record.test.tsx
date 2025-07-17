// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import dayjs from 'dayjs';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, test, vi } from 'vitest';

import CreateUnallocatedUnclaimedActivityRecord from '../../components/votes/actions/CreateUnallocatedUnclaimedActivityRecord';
import { SvConfigProvider } from '../../utils';

describe('UnallocatedUnclaimedActivityRecord Amount Validation', () => {
  test('validates amount input', async () => {
    const user = userEvent.setup();

    const dummyChooseAction = () => {};
    const mockEffectivity = dayjs();

    render(
      <MemoryRouter>
        <SvConfigProvider>
          <CreateUnallocatedUnclaimedActivityRecord
            chooseAction={dummyChooseAction}
            effectivity={mockEffectivity}
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
  test('converts local datetime to UTC before calling chooseAction', async () => {
    const mockChooseAction = vi.fn();
    // Set effectivity date far enough in the past to avoid race condition with expiresAt default
    // This prevents the component's useEffect from overriding our manually typed test value
    const mockEffectivity = dayjs('2025-07-18T00:00:00');

    render(
      <MemoryRouter>
        <SvConfigProvider>
          <CreateUnallocatedUnclaimedActivityRecord
            chooseAction={mockChooseAction}
            effectivity={mockEffectivity}
          />
        </SvConfigProvider>
      </MemoryRouter>
    );

    const dateInput = screen.getByTestId('datetime-picker-unallocated-expires-at');
    const amountInput = screen.getByTestId('create-amount');
    const beneficiaryInput = screen.getByTestId('create-beneficiary');
    const reasonInput = screen.getByTestId('create-reason');

    // Simulate filling the form
    fireEvent.change(dateInput, { target: { value: '2025-07-20 10:00' } });
    fireEvent.change(amountInput, { target: { value: '100' } });
    fireEvent.change(beneficiaryInput, { target: { value: 'alice' } });
    fireEvent.change(reasonInput, { target: { value: 'testing' } });

    // Wait for chooseAction to be called
    await waitFor(() => {
      expect(mockChooseAction).toHaveBeenCalled();
    });

    const lastCall = mockChooseAction.mock.calls.at(-1)?.[0];
    const actualUTC = lastCall?.value?.dsoAction?.value?.expiresAt;
    const expectedUTC = '2025-07-20T08:00:00.000Z';

    expect(actualUTC).toBe(expectedUTC);
  });
});
