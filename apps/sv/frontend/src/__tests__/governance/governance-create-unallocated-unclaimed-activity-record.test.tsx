// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, test, expect } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { SvConfigProvider } from '../../utils';
import dayjs from 'dayjs';
import CreateUnallocatedUnclaimedActivityRecord from '../../components/votes/actions/CreateUnallocatedUnclaimedActivityRecord';

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
