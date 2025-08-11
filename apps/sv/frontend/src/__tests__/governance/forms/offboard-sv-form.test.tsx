// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import userEvent from '@testing-library/user-event';
import { SvConfigProvider } from '../../../utils';
import App from '../../../App';
import { svPartyId } from '../../mocks/constants';
import { Wrapper } from '../../helpers';
import { dateTimeFormatISO } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import dayjs from 'dayjs';
import { OffboardSvForm } from '../../../components/forms/OffboardSvForm';

describe('SV user can', () => {
  test('login and see the SV party ID', async () => {
    const user = userEvent.setup();
    render(
      <SvConfigProvider>
        <App />
      </SvConfigProvider>
    );

    expect(await screen.findByText('Log In')).toBeDefined();

    const input = screen.getByRole('textbox');
    await user.type(input, 'sv1');

    const button = screen.getByRole('button', { name: 'Log In' });
    user.click(button);

    expect(await screen.findAllByDisplayValue(svPartyId)).toBeDefined();
  });
});

describe('Offboard SV Form', () => {
  test('should render all Offboard SV Form components', () => {
    render(
      <Wrapper>
        <OffboardSvForm onSubmit={() => Promise.resolve()} />
      </Wrapper>
    );

    expect(screen.getByTestId('offboard-sv-form')).toBeDefined();
    expect(screen.getByText('Action')).toBeDefined();

    const actionInput = screen.getByTestId('offboard-sv-action');
    expect(actionInput).toBeDefined();
    expect(actionInput.getAttribute('value')).toBe('Offboard Member');

    const summaryInput = screen.getByTestId('offboard-sv-summary');
    expect(summaryInput).toBeDefined();
    expect(summaryInput.getAttribute('value')).toBeNull();

    const urlInput = screen.getByTestId('offboard-sv-url');
    expect(urlInput).toBeDefined();
    expect(urlInput.getAttribute('value')).toBe('');

    const memberInput = screen.getByTestId('offboard-sv-member-dropdown');
    expect(memberInput).toBeDefined();
    expect(memberInput.getAttribute('value')).toBe('');
  });

  test('should render errors when submit button is clicked on new form', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <OffboardSvForm onSubmit={() => Promise.resolve()} />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('offboard-sv-action');
    const submitButton = screen.getByTestId('submit-button');
    expect(submitButton).toBeDefined();

    await user.click(submitButton);
    expect(submitButton.getAttribute('disabled')).toBeDefined();
    expect(async () => await user.click(submitButton)).rejects.toThrowError(
      /Unable to perform pointer interaction/
    );

    screen.getByText('Summary is required');
    screen.getByText('Invalid URL');
    screen.getByText('SV is required');

    // completing the form should reenable the submit button
    const summaryInput = screen.getByTestId('offboard-sv-summary');
    expect(summaryInput).toBeDefined();
    user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('offboard-sv-url');
    expect(urlInput).toBeDefined();
    user.type(urlInput, 'https://example.com');

    const memberDropdown = screen.getByTestId('offboard-sv-member-dropdown');
    expect(memberDropdown).toBeDefined();

    const selectInput = screen.getByRole('combobox');
    fireEvent.mouseDown(selectInput);

    await waitFor(async () => {
      const memberToSelect = screen.getByText('Digital-Asset-Eng-2');
      expect(memberToSelect).toBeDefined();
      await user.click(memberToSelect);
    });

    await user.click(actionInput); // using this to trigger the onBlur event which triggers the validation

    expect(submitButton.getAttribute('disabled')).toBe('');
  });

  test('expiry date must be in the future', async () => {
    const user = userEvent.setup();
    render(
      <Wrapper>
        <OffboardSvForm onSubmit={() => Promise.resolve()} />
      </Wrapper>
    );

    const expiryDateInput = screen.getByTestId('offboard-sv-expiry-date-field');
    expect(expiryDateInput).toBeDefined();

    const thePast = dayjs().subtract(1, 'day').format(dateTimeFormatISO);
    const theFuture = dayjs().add(1, 'day').format(dateTimeFormatISO);

    await user.clear(expiryDateInput);
    await user.type(expiryDateInput, thePast);

    waitFor(() => {
      expect(screen.queryByText('Expiration must be in the future')).toBeDefined();
    });

    await user.clear(expiryDateInput);
    await user.type(expiryDateInput, theFuture);

    expect(screen.queryByText('Expiration must be in the future')).toBeNull();
  });

  test('effective date must be after expiry date', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <OffboardSvForm onSubmit={() => Promise.resolve()} />
      </Wrapper>
    );

    const expiryDateInput = screen.getByTestId('offboard-sv-expiry-date-field');
    const effectiveDateInput = screen.getByTestId('offboard-sv-effective-date-field');

    const expiryDate = dayjs().add(1, 'week');
    const effectiveDate = expiryDate.subtract(1, 'day');

    await user.clear(expiryDateInput);
    await user.type(expiryDateInput, expiryDate.format(dateTimeFormatISO));

    await user.clear(effectiveDateInput);
    await user.type(effectiveDateInput, effectiveDate.format(dateTimeFormatISO));

    waitFor(() => {
      expect(screen.queryByText('Effective Date must be after expiration date')).toBeDefined();
    });

    const validEffectiveDate = expiryDate.add(1, 'day').format(dateTimeFormatISO);

    await user.clear(effectiveDateInput);
    await user.type(effectiveDateInput, validEffectiveDate);

    expect(screen.queryByText('Effective Date must be after expiration date')).toBeNull();
  });

  test('sv dropdown contains all svs', async () => {
    render(
      <Wrapper>
        <OffboardSvForm onSubmit={() => Promise.resolve()} />
      </Wrapper>
    );

    const memberDropdown = screen.getByTestId('offboard-sv-member-dropdown');
    expect(memberDropdown).toBeDefined();

    const selectInput = screen.getByRole('combobox');
    fireEvent.mouseDown(selectInput);

    const svOptions = await screen.findAllByRole('option');
    expect(svOptions.length).toBeGreaterThan(1);
    expect(svOptions.map(option => option.textContent)).toEqual(
      expect.arrayContaining(['Digital-Asset-2', 'Digital-Asset-Eng-2'])
    );
  });
});
