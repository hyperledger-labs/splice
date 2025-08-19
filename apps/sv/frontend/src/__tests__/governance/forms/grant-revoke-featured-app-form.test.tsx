// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import userEvent from '@testing-library/user-event';
import { SvConfigProvider } from '../../../utils';
import App from '../../../App';
import { svPartyId } from '../../mocks/constants';
import { Wrapper } from '../../helpers';
import { dateTimeFormatISO } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import dayjs from 'dayjs';
import { GrantRevokeFeaturedAppForm } from '../../../components/forms/GrantRevokeFeaturedAppForm';

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

describe('Grant Featured App Form', () => {
  test('should render all Form components', () => {
    render(
      <Wrapper>
        <GrantRevokeFeaturedAppForm
          onSubmit={() => Promise.resolve()}
          selectedAction="SRARC_GrantFeaturedAppRight"
        />
      </Wrapper>
    );

    expect(screen.getByTestId('grant-featured-app-form')).toBeDefined();
    expect(screen.getByText('Action')).toBeDefined();

    const actionInput = screen.getByTestId('grant-featured-app-action');
    expect(actionInput).toBeDefined();
    expect(actionInput.getAttribute('value')).toBe('Feature Application');

    const summaryInput = screen.getByTestId('grant-featured-app-summary');
    expect(summaryInput).toBeDefined();
    expect(summaryInput.getAttribute('value')).toBeNull();

    const urlInput = screen.getByTestId('grant-featured-app-url');
    expect(urlInput).toBeDefined();
    expect(urlInput.getAttribute('value')).toBe('');

    const idInput = screen.getByTestId('grant-featured-app-idValue');
    expect(idInput).toBeDefined();
    expect(idInput.getAttribute('value')).toBe('');

    const providerInput = screen.getByTestId('grant-featured-app-idValue-title');
    expect(providerInput).toBeDefined();
    expect(providerInput.textContent).toBe('Provider');
  });

  test('should render errors when submit button is clicked on new form', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <GrantRevokeFeaturedAppForm
          onSubmit={() => Promise.resolve()}
          selectedAction="SRARC_GrantFeaturedAppRight"
        />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('grant-featured-app-action');
    const submitButton = screen.getByTestId('submit-button');
    expect(submitButton).toBeDefined();

    await user.click(submitButton);
    expect(submitButton.getAttribute('disabled')).toBeDefined();
    expect(async () => await user.click(submitButton)).rejects.toThrowError(
      /Unable to perform pointer interaction/
    );

    screen.getByText('Summary is required');
    screen.getByText('Invalid URL');
    expect(screen.getByTestId('grant-featured-app-idValue-error').textContent).toBe('Required');

    // completing the form should reenable the submit button
    const summaryInput = screen.getByTestId('grant-featured-app-summary');
    expect(summaryInput).toBeDefined();
    user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('grant-featured-app-url');
    expect(urlInput).toBeDefined();
    user.type(urlInput, 'https://example.com');

    const providerInput = screen.getByTestId('grant-featured-app-idValue');
    expect(providerInput).toBeDefined();
    user.type(providerInput, 'abcde12345');

    await user.click(actionInput); // using this to trigger the onBlur event which triggers the validation

    expect(submitButton.getAttribute('disabled')).toBe('');
  });

  test('expiry date must be in the future', async () => {
    const user = userEvent.setup();
    render(
      <Wrapper>
        <GrantRevokeFeaturedAppForm
          onSubmit={() => Promise.resolve()}
          selectedAction="SRARC_GrantFeaturedAppRight"
        />
      </Wrapper>
    );

    const expiryDateInput = screen.getByTestId('grant-featured-app-expiry-date-field');
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
        <GrantRevokeFeaturedAppForm
          onSubmit={() => Promise.resolve()}
          selectedAction="SRARC_GrantFeaturedAppRight"
        />
      </Wrapper>
    );

    const expiryDateInput = screen.getByTestId('grant-featured-app-expiry-date-field');
    const effectiveDateInput = screen.getByTestId('grant-featured-app-effective-date-field');

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
});

describe('Revoke Featured App Form', () => {
  test('should render all Form components', () => {
    render(
      <Wrapper>
        <GrantRevokeFeaturedAppForm
          onSubmit={() => Promise.resolve()}
          selectedAction="SRARC_RevokeFeaturedAppRight"
        />
      </Wrapper>
    );

    expect(screen.getByTestId('revoke-featured-app-form')).toBeDefined();
    expect(screen.getByText('Action')).toBeDefined();

    const actionInput = screen.getByTestId('revoke-featured-app-action');
    expect(actionInput).toBeDefined();
    expect(actionInput.getAttribute('value')).toBe('Unfeature Application');

    const summaryInput = screen.getByTestId('revoke-featured-app-summary');
    expect(summaryInput).toBeDefined();
    expect(summaryInput.getAttribute('value')).toBeNull();

    const urlInput = screen.getByTestId('revoke-featured-app-url');
    expect(urlInput).toBeDefined();
    expect(urlInput.getAttribute('value')).toBe('');

    const idInput = screen.getByTestId('revoke-featured-app-idValue');
    expect(idInput).toBeDefined();
    expect(idInput.getAttribute('value')).toBe('');

    const providerInput = screen.getByTestId('revoke-featured-app-idValue-title');
    expect(providerInput).toBeDefined();
    expect(providerInput.textContent).toBe('Featured Application Right Contract Id');
  });

  test('should render errors when submit button is clicked on new form', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <GrantRevokeFeaturedAppForm
          onSubmit={() => Promise.resolve()}
          selectedAction="SRARC_RevokeFeaturedAppRight"
        />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('revoke-featured-app-action');
    const submitButton = screen.getByTestId('submit-button');
    expect(submitButton).toBeDefined();

    await user.click(submitButton);
    expect(submitButton.getAttribute('disabled')).toBeDefined();
    expect(async () => await user.click(submitButton)).rejects.toThrowError(
      /Unable to perform pointer interaction/
    );

    screen.getByText('Summary is required');
    screen.getByText('Invalid URL');
    expect(screen.getByTestId('revoke-featured-app-idValue-error').textContent).toBe('Required');

    // completing the form should reenable the submit button
    const summaryInput = screen.getByTestId('revoke-featured-app-summary');
    expect(summaryInput).toBeDefined();
    user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('revoke-featured-app-url');
    expect(urlInput).toBeDefined();
    user.type(urlInput, 'https://example.com');

    const providerInput = screen.getByTestId('revoke-featured-app-idValue');
    expect(providerInput).toBeDefined();
    user.type(providerInput, 'abcde12345');

    await user.click(actionInput); // using this to trigger the onBlur event which triggers the validation

    expect(submitButton.getAttribute('disabled')).toBe('');
  });

  test('expiry date must be in the future', async () => {
    const user = userEvent.setup();
    render(
      <Wrapper>
        <GrantRevokeFeaturedAppForm
          onSubmit={() => Promise.resolve()}
          selectedAction="SRARC_RevokeFeaturedAppRight"
        />
      </Wrapper>
    );

    const expiryDateInput = screen.getByTestId('revoke-featured-app-expiry-date-field');
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
        <GrantRevokeFeaturedAppForm
          onSubmit={() => Promise.resolve()}
          selectedAction="SRARC_RevokeFeaturedAppRight"
        />
      </Wrapper>
    );

    const expiryDateInput = screen.getByTestId('revoke-featured-app-expiry-date-field');
    const effectiveDateInput = screen.getByTestId('revoke-featured-app-effective-date-field');

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
});
