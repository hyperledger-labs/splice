// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { render, screen, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { test, expect, describe } from 'vitest';

import App from '../App';
import { svPartyId } from './mocks/constants';

describe('SV user can', () => {
  test('login and see the SV party ID', async () => {
    const user = userEvent.setup();
    render(<App />);

    expect(await screen.findByText('Log In')).toBeDefined();

    const input = screen.getByRole('textbox');
    await user.type(input, 'sv1');

    const button = screen.getByRole('button', { name: 'Log In' });
    user.click(button);

    expect(await screen.findAllByDisplayValue(svPartyId)).toBeDefined();
  });

  test('browse to the governance tab', async () => {
    const user = userEvent.setup();
    render(<App />);

    expect(await screen.findByText('Governance')).toBeDefined();
    await user.click(screen.getByText('Governance'));

    expect(await screen.findByText('Vote Requests')).toBeDefined();
  });

  test('set next scheduled domain upgrade', async () => {
    const user = userEvent.setup();
    render(<App />);

    expect(await screen.findByText('Governance')).toBeDefined();
    await user.click(screen.getByText('Governance'));

    expect(await screen.findByText('Vote Requests')).toBeDefined();
    expect(await screen.findByText('Governance')).toBeDefined();
    // const dropdown = screen.getByTestId('display-actions').querySelector('select');
    const dropdown = screen.getByTestId('display-actions');
    expect(dropdown).toBeDefined();
    fireEvent.change(dropdown!, { target: { value: 'SRARC_SetConfig' } });

    expect(screen.queryByText('nextScheduledSynchronizerUpgrade.time')).toBeNull();
    expect(await screen.findByText('nextScheduledSynchronizerUpgrade')).toBeDefined();

    // const checkBox = screen.getByTestId('enable-next-scheduled-domain-upgrade');
    const checkBox = screen.getByTestId('enable-next-scheduled-domain-upgrade');
    await user.click(checkBox);

    expect(screen.queryByText('nextScheduledSynchronizerUpgrade')).toBeNull();
    expect(await screen.findByText('nextScheduledSynchronizerUpgrade.time')).toBeDefined();
  });
});

describe('An AddFutureAmuletConfigSchedule request', () => {
  test('defaults to the current amulet configuration', async () => {
    const user = userEvent.setup();
    render(<App />);

    expect(await screen.findByText('Governance')).toBeDefined();
    await user.click(screen.getByText('Governance'));

    expect(await screen.findByText('Vote Requests')).toBeDefined();
    expect(await screen.findByText('Governance')).toBeDefined();

    const dropdown = screen.getByTestId('display-actions');
    expect(dropdown).toBeDefined();
    fireEvent.change(dropdown!, { target: { value: 'CRARC_AddFutureAmuletConfigSchedule' } });

    expect(await screen.findByText('transferConfig.createFee.fee')).toBeDefined();
    expect(await screen.findByDisplayValue('4815162342')).toBeDefined();
  });

  test('is displayed in executed section when its effective date is in the past', async () => {
    const user = userEvent.setup();
    render(<App />);

    expect(await screen.findByText('Governance')).toBeDefined();
    await user.click(screen.getByText('Governance'));

    expect(await screen.findByText('Vote Requests')).toBeDefined();
    expect(await screen.findByText('Governance')).toBeDefined();

    expect(await screen.findByText('Executed')).toBeDefined();
    await user.click(screen.getByText('Executed'));

    expect(await screen.findByText('CRARC_AddFutureAmuletConfigSchedule')).toBeDefined();
  });
});
