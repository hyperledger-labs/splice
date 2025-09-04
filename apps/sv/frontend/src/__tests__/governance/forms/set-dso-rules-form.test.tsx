// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import userEvent from '@testing-library/user-event';
import { SvConfigProvider } from '../../../utils';
import App from '../../../App';
import { svPartyId } from '../../mocks/constants';
import { Wrapper } from '../../helpers';
import { SetDsoConfigRulesForm } from '../../../components/forms/SetDsoConfigRulesForm';
import dayjs from 'dayjs';
import { dateTimeFormatISO } from '@lfdecentralizedtrust/splice-common-frontend-utils';

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
    await user.click(button);

    expect(await screen.findAllByDisplayValue(svPartyId)).toBeDefined();
  });
});

describe('Set DSO Config Rules Form', () => {
  test('should render all Set DSO Config Rules Form components', () => {
    render(
      <Wrapper>
        <SetDsoConfigRulesForm onSubmit={() => Promise.resolve()} />
      </Wrapper>
    );

    expect(screen.getByTestId('set-dso-config-rules-form')).toBeDefined();
    expect(screen.getByText('Action')).toBeDefined();

    const actionInput = screen.getByTestId('set-dso-config-rules-action');
    expect(actionInput).toBeDefined();
    expect(actionInput.getAttribute('value')).toBe('Set Dso Rules Configuration');

    const summaryInput = screen.getByTestId('set-dso-config-rules-summary');
    expect(summaryInput).toBeDefined();
    expect(summaryInput.getAttribute('value')).toBeNull();

    const urlInput = screen.getByTestId('set-dso-config-rules-url');
    expect(urlInput).toBeDefined();
    expect(urlInput.getAttribute('value')).toBe('');

    const configLabels = screen.getAllByTestId('config-label', { exact: false });
    expect(configLabels.length).toBeGreaterThan(15);

    const configFields = screen.getAllByTestId('config-field', { exact: false });
    expect(configFields.length).toBeGreaterThan(15);

    expect(() => screen.getAllByTestId('config-current-value', { exact: false })).toThrowError(
      /Unable to find an element/
    );
  });

  test('should render errors when submit button is clicked on new form', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <SetDsoConfigRulesForm onSubmit={() => Promise.resolve()} />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('set-dso-config-rules-action');
    const submitButton = screen.getByTestId('submit-button');
    expect(submitButton).toBeDefined();

    await user.click(submitButton);
    expect(submitButton.getAttribute('disabled')).toBeDefined();
    expect(async () => await user.click(submitButton)).rejects.toThrowError(
      /Unable to perform pointer interaction/
    );

    expect(screen.getByText('Summary is required')).toBeDefined();
    expect(screen.getByText('Invalid URL')).toBeDefined();

    // completing the form should reenable the submit button
    const summaryInput = screen.getByTestId('set-dso-config-rules-summary');
    expect(summaryInput).toBeDefined();
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('set-dso-config-rules-url');
    expect(urlInput).toBeDefined();
    await user.type(urlInput, 'https://example.com');

    await user.click(actionInput); // using this to trigger the onBlur event which triggers the validation

    expect(submitButton.getAttribute('disabled')).toBeNull();
  });

  test('expiry date must be in the future', async () => {
    const user = userEvent.setup();
    render(
      <Wrapper>
        <SetDsoConfigRulesForm onSubmit={() => Promise.resolve()} />
      </Wrapper>
    );

    const expiryDateInput = screen.getByTestId('set-dso-config-rules-expiry-date-field');
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

    waitFor(() => {
      expect(screen.queryByText('Expiration must be in the future')).toBeNull();
    });
  });

  test('effective date must be after expiry date', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <SetDsoConfigRulesForm onSubmit={() => Promise.resolve()} />
      </Wrapper>
    );

    const expiryDateInput = screen.getByTestId('set-dso-config-rules-expiry-date-field');
    const effectiveDateInput = screen.getByTestId('set-dso-config-rules-effective-date-field');

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

    waitFor(() => {
      expect(screen.queryByText('Effective Date must be after expiration date')).toBeNull();
    });
  });

  test('changing config fields should render the current value', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <SetDsoConfigRulesForm onSubmit={() => Promise.resolve()} />
      </Wrapper>
    );

    expect(() => screen.getAllByTestId('config-current-value', { exact: false })).toThrowError(
      /Unable to find an element/
    );

    const c1Input = screen.getByTestId('config-field-numUnclaimedRewardsThreshold');
    expect(c1Input).toBeDefined();
    await user.type(c1Input, '99');

    const c2Input = screen.getByTestId('config-field-voteCooldownTime');
    expect(c2Input).toBeDefined();
    await user.type(c2Input, '9999');

    const changes = screen.getAllByTestId('config-current-value', { exact: false });
    expect(changes.length).toBe(2);
  });

  test('should show proposal review page after form completion', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <SetDsoConfigRulesForm onSubmit={() => Promise.resolve()} />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('set-dso-config-rules-action');

    const summaryInput = screen.getByTestId('set-dso-config-rules-summary');
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('set-dso-config-rules-url');
    await user.type(urlInput, 'https://example.com');

    const c1Input = screen.getByTestId('config-field-numUnclaimedRewardsThreshold');
    await user.type(c1Input, '99');

    const c2Input = screen.getByTestId('config-field-voteCooldownTime');
    await user.type(c2Input, '9999');

    expect(screen.getByText('Review Proposal')).toBeDefined();
    await user.click(actionInput); // using this to trigger the onBlur event which triggers the validation

    const submitButton = screen.getByTestId('submit-button');
    await waitFor(async () => {
      expect(submitButton.getAttribute('disabled')).toBeNull();
    });

    await user.click(submitButton);

    expect(screen.getByText('Proposal Summary')).toBeDefined();
  });
});
