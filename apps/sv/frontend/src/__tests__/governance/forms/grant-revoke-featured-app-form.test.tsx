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
import { GrantRevokeFeaturedAppForm } from '../../../components/forms/GrantRevokeFeaturedAppForm';
import { server, svUrl } from '../../setup/setup';
import { rest } from 'msw';
import { PROPOSAL_SUMMARY_SUBTITLE, PROPOSAL_SUMMARY_TITLE } from '../../../utils/constants';

describe('SV user can', () => {
  test('login and see the SV party ID', async () => {
    const user = userEvent.setup();
    render(
      <SvConfigProvider>
        <App />
      </SvConfigProvider>
    );

    expect(await screen.findByText('Log In')).toBeInTheDocument();

    const input = screen.getByRole('textbox');
    await user.type(input, 'sv1');

    const button = screen.getByRole('button', { name: 'Log In' });
    await user.click(button);

    expect(await screen.findAllByDisplayValue(svPartyId)).not.toBe([]);
  });
});

describe('Grant Featured App Form', () => {
  test('should render all Form components', () => {
    render(
      <Wrapper>
        <GrantRevokeFeaturedAppForm selectedAction="SRARC_GrantFeaturedAppRight" />
      </Wrapper>
    );

    expect(screen.getByTestId('grant-featured-app-form')).toBeInTheDocument();
    expect(screen.getByText('Action')).toBeInTheDocument();

    const actionInput = screen.getByTestId('grant-featured-app-action');
    expect(actionInput).toBeInTheDocument();
    expect(actionInput.getAttribute('value')).toBe('Feature Application');

    const summaryInput = screen.getByTestId('grant-featured-app-summary');
    expect(summaryInput).toBeInTheDocument();
    expect(summaryInput.getAttribute('value')).toBeNull();

    const summarySubtitle = screen.getByTestId('grant-featured-app-summary-subtitle');
    expect(summarySubtitle).toBeInTheDocument();
    expect(summarySubtitle.textContent).toBe(PROPOSAL_SUMMARY_SUBTITLE);

    const urlInput = screen.getByTestId('grant-featured-app-url');
    expect(urlInput).toBeInTheDocument();
    expect(urlInput.getAttribute('value')).toBe('');

    const idInput = screen.getByTestId('grant-featured-app-idValue');
    expect(idInput).toBeInTheDocument();
    expect(idInput.getAttribute('value')).toBe('');

    const providerInput = screen.getByTestId('grant-featured-app-idValue-title');
    expect(providerInput).toBeInTheDocument();
    expect(providerInput.textContent).toBe('Provider');

    expect(screen.getByText('Review Proposal')).toBeInTheDocument();
  });

  test('should render errors when submit button is clicked on new form', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <GrantRevokeFeaturedAppForm selectedAction="SRARC_GrantFeaturedAppRight" />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('grant-featured-app-action');
    const submitButton = screen.getByTestId('submit-button');
    expect(submitButton).toBeInTheDocument();

    await user.click(submitButton);
    expect(submitButton).toBeDisabled();
    await expect(async () => await user.click(submitButton)).rejects.toThrowError(
      /Unable to perform pointer interaction/
    );

    screen.getByText('Summary is required');
    screen.getByText('Invalid URL');
    expect(screen.getByTestId('grant-featured-app-idValue-error').textContent).toBe('Required');

    // completing the form should reenable the submit button
    const summaryInput = screen.getByTestId('grant-featured-app-summary');
    expect(summaryInput).toBeInTheDocument();
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('grant-featured-app-url');
    expect(urlInput).toBeInTheDocument();
    await user.type(urlInput, 'https://example.com');

    const providerInput = screen.getByTestId('grant-featured-app-idValue');
    expect(providerInput).toBeInTheDocument();
    await user.type(providerInput, 'a-party-id::1014912492');

    await user.click(actionInput); // using this to trigger the onBlur event which triggers the validation

    expect(submitButton).not.toBeDisabled();
  });

  test('expiry date must be in the future', async () => {
    render(
      <Wrapper>
        <GrantRevokeFeaturedAppForm selectedAction="SRARC_GrantFeaturedAppRight" />
      </Wrapper>
    );

    const expiryDateInput = screen.getByTestId('grant-featured-app-expiry-date-field');
    expect(expiryDateInput).toBeInTheDocument();

    const thePast = dayjs().subtract(1, 'day').format(dateTimeFormatISO);
    const theFuture = dayjs().add(1, 'day').format(dateTimeFormatISO);

    fireEvent.change(expiryDateInput, { target: { value: thePast } });

    await waitFor(() => {
      expect(screen.queryByText('Expiration must be in the future')).toBeInTheDocument();
    });

    fireEvent.change(expiryDateInput, { target: { value: theFuture } });

    await waitFor(() => {
      expect(screen.queryByText('Expiration must be in the future')).not.toBeInTheDocument();
    });
  });

  test('effective date must be after expiry date', async () => {
    render(
      <Wrapper>
        <GrantRevokeFeaturedAppForm selectedAction="SRARC_GrantFeaturedAppRight" />
      </Wrapper>
    );

    const expiryDateInput = screen.getByTestId('grant-featured-app-expiry-date-field');
    const effectiveDateInput = screen.getByTestId('grant-featured-app-effective-date-field');

    const expiryDate = dayjs().add(1, 'week');
    const effectiveDate = expiryDate.subtract(1, 'day');

    fireEvent.change(expiryDateInput, { target: { value: expiryDate.format(dateTimeFormatISO) } });
    fireEvent.change(effectiveDateInput, {
      target: { value: effectiveDate.format(dateTimeFormatISO) },
    });

    await waitFor(() => {
      expect(
        screen.queryByText('Effective Date must be after expiration date')
      ).toBeInTheDocument();
    });

    const validEffectiveDate = expiryDate.add(1, 'day').format(dateTimeFormatISO);

    fireEvent.change(effectiveDateInput, { target: { value: validEffectiveDate } });

    await waitFor(() => {
      expect(
        screen.queryByText('Effective Date must be after expiration date')
      ).not.toBeInTheDocument();
    });
  });

  test('should show proposal review page after form completion', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <GrantRevokeFeaturedAppForm selectedAction="SRARC_GrantFeaturedAppRight" />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('grant-featured-app-action');

    const summaryInput = screen.getByTestId('grant-featured-app-summary');
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('grant-featured-app-url');
    await user.type(urlInput, 'https://example.com');

    const providerInput = screen.getByTestId('grant-featured-app-idValue');
    await user.type(providerInput, 'a-party-id::1014912492');

    expect(screen.getByText('Review Proposal')).toBeInTheDocument();
    const submitButton = screen.getByTestId('submit-button');
    await user.click(actionInput); // using this to trigger the onBlur event which triggers the validation

    await waitFor(() => {
      expect(screen.queryByText('Validating provider...')).not.toBeInTheDocument();
    });

    expect(screen.queryByText('Provider party not found on ledger')).not.toBeInTheDocument();

    await waitFor(async () => {
      expect(submitButton).not.toBeDisabled();
    });

    await user.click(submitButton);

    expect(screen.getByText(PROPOSAL_SUMMARY_TITLE)).toBeInTheDocument();
  });
});

describe('Revoke Featured App Form', () => {
  test('should render all Form components', () => {
    render(
      <Wrapper>
        <GrantRevokeFeaturedAppForm selectedAction="SRARC_RevokeFeaturedAppRight" />
      </Wrapper>
    );

    expect(screen.getByTestId('revoke-featured-app-form')).toBeInTheDocument();
    expect(screen.getByText('Action')).toBeInTheDocument();

    const actionInput = screen.getByTestId('revoke-featured-app-action');
    expect(actionInput).toBeInTheDocument();
    expect(actionInput.getAttribute('value')).toBe('Unfeature Application');

    const summaryInput = screen.getByTestId('revoke-featured-app-summary');
    expect(summaryInput).toBeInTheDocument();
    expect(summaryInput.getAttribute('value')).toBeNull();

    const urlInput = screen.getByTestId('revoke-featured-app-url');
    expect(urlInput).toBeInTheDocument();
    expect(urlInput.getAttribute('value')).toBe('');

    const idInput = screen.getByTestId('revoke-featured-app-idValue');
    expect(idInput).toBeInTheDocument();
    expect(idInput.getAttribute('value')).toBe('');

    const providerInput = screen.getByTestId('revoke-featured-app-idValue-title');
    expect(providerInput).toBeInTheDocument();
    expect(providerInput.textContent).toBe('Featured Application Right Contract Id');
  });

  test('should render errors when submit button is clicked on new form', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <GrantRevokeFeaturedAppForm selectedAction="SRARC_RevokeFeaturedAppRight" />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('revoke-featured-app-action');
    const submitButton = screen.getByTestId('submit-button');
    expect(submitButton).toBeInTheDocument();

    await user.click(submitButton);
    expect(submitButton).toBeDisabled();
    expect(async () => await user.click(submitButton)).rejects.toThrowError(
      /Unable to perform pointer interaction/
    );

    screen.getByText('Summary is required');
    screen.getByText('Invalid URL');
    expect(screen.getByTestId('revoke-featured-app-idValue-error').textContent).toBe('Required');

    // completing the form should reenable the submit button
    const summaryInput = screen.getByTestId('revoke-featured-app-summary');
    expect(summaryInput).toBeInTheDocument();
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('revoke-featured-app-url');
    expect(urlInput).toBeInTheDocument();
    await user.type(urlInput, 'https://example.com');

    const providerInput = screen.getByTestId('revoke-featured-app-idValue');
    expect(providerInput).toBeInTheDocument();
    await user.type(providerInput, 'abcde12345');

    await user.click(actionInput); // using this to trigger the onBlur event which triggers the validation

    expect(submitButton).not.toBeDisabled();
  });

  test('expiry date must be in the future', async () => {
    render(
      <Wrapper>
        <GrantRevokeFeaturedAppForm selectedAction="SRARC_RevokeFeaturedAppRight" />
      </Wrapper>
    );

    const expiryDateInput = screen.getByTestId('revoke-featured-app-expiry-date-field');
    expect(expiryDateInput).toBeInTheDocument();

    const thePast = dayjs().subtract(1, 'day').format(dateTimeFormatISO);
    const theFuture = dayjs().add(1, 'day').format(dateTimeFormatISO);

    fireEvent.change(expiryDateInput, { target: { value: thePast } });

    await waitFor(() => {
      expect(screen.queryByText('Expiration must be in the future')).toBeInTheDocument();
    });

    fireEvent.change(expiryDateInput, { target: { value: theFuture } });

    expect(screen.queryByText('Expiration must be in the future')).not.toBeInTheDocument();
  });

  test('effective date must be after expiry date', async () => {
    render(
      <Wrapper>
        <GrantRevokeFeaturedAppForm selectedAction="SRARC_RevokeFeaturedAppRight" />
      </Wrapper>
    );

    const expiryDateInput = screen.getByTestId('revoke-featured-app-expiry-date-field');
    const effectiveDateInput = screen.getByTestId('revoke-featured-app-effective-date-field');

    const expiryDate = dayjs().add(1, 'week');
    const effectiveDate = expiryDate.subtract(1, 'day');

    fireEvent.change(expiryDateInput, { target: { value: expiryDate.format(dateTimeFormatISO) } });
    fireEvent.change(effectiveDateInput, {
      target: { value: effectiveDate.format(dateTimeFormatISO) },
    });

    await waitFor(() => {
      expect(
        screen.queryByText('Effective Date must be after expiration date')
      ).toBeInTheDocument();
    });

    const validEffectiveDate = expiryDate.add(1, 'day').format(dateTimeFormatISO);

    fireEvent.change(effectiveDateInput, { target: { value: validEffectiveDate } });

    await waitFor(() => {
      expect(
        screen.queryByText('Effective Date must be after expiration date')
      ).not.toBeInTheDocument();
    });
  });

  test('should show proposal review page after form completion', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <GrantRevokeFeaturedAppForm selectedAction="SRARC_RevokeFeaturedAppRight" />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('revoke-featured-app-action');

    const summaryInput = screen.getByTestId('revoke-featured-app-summary');
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('revoke-featured-app-url');
    await user.type(urlInput, 'https://example.com');

    const providerInput = screen.getByTestId('revoke-featured-app-idValue');
    await user.type(providerInput, 'abcde12345');

    expect(screen.getByText('Review Proposal')).toBeInTheDocument();
    await user.click(actionInput); // using this to trigger the onBlur event which triggers the validation

    const submitButton = screen.getByTestId('submit-button');
    await waitFor(async () => {
      expect(submitButton).not.toBeDisabled();
    });

    await user.click(submitButton);

    expect(screen.getByText(PROPOSAL_SUMMARY_TITLE)).toBeInTheDocument();
  });

  test('should show error on form if submission fails', async () => {
    server.use(
      rest.post(`${svUrl}/v0/admin/sv/voterequest/create`, (_, res, ctx) => {
        return res(ctx.status(503), ctx.json({ error: 'Service Unavailable' }));
      })
    );

    const user = userEvent.setup();

    render(
      <Wrapper>
        <GrantRevokeFeaturedAppForm selectedAction="SRARC_RevokeFeaturedAppRight" />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('revoke-featured-app-action');
    const submitButton = screen.getByTestId('submit-button');

    const summaryInput = screen.getByTestId('revoke-featured-app-summary');
    expect(summaryInput).toBeInTheDocument();
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('revoke-featured-app-url');
    expect(urlInput).toBeInTheDocument();
    await user.type(urlInput, 'https://example.com');

    const providerInput = screen.getByTestId('revoke-featured-app-idValue');
    expect(providerInput).toBeInTheDocument();
    await user.type(providerInput, 'abcde12345');

    await user.click(actionInput); // using this to trigger the onBlur event which triggers the validation

    await waitFor(async () => {
      expect(submitButton).not.toBeDisabled();
    });

    await user.click(submitButton); //review proposal
    await user.click(submitButton); //submit proposal

    expect(screen.getByTestId('proposal-submission-error')).toBeInTheDocument();
    expect(screen.getByText(/Submission failed/)).toBeInTheDocument();
    expect(screen.getByText(/Service Unavailable/)).toBeInTheDocument();
  });

  test('should redirect to governance page after successful submission', async () => {
    server.use(
      rest.post(`${svUrl}/v0/admin/sv/voterequest/create`, (_, res, ctx) => {
        return res(ctx.json({}));
      })
    );

    const user = userEvent.setup();

    render(
      <Wrapper>
        <GrantRevokeFeaturedAppForm selectedAction="SRARC_RevokeFeaturedAppRight" />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('revoke-featured-app-action');
    const submitButton = screen.getByTestId('submit-button');

    const summaryInput = screen.getByTestId('revoke-featured-app-summary');
    expect(summaryInput).toBeInTheDocument();
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('revoke-featured-app-url');
    expect(urlInput).toBeInTheDocument();
    await user.type(urlInput, 'https://example.com');

    const providerInput = screen.getByTestId('revoke-featured-app-idValue');
    expect(providerInput).toBeInTheDocument();
    await user.type(providerInput, 'abcde12345');

    await user.click(actionInput); // using this to trigger the onBlur event which triggers the validation

    await waitFor(async () => {
      expect(submitButton).not.toBeDisabled();
    });

    await user.click(submitButton); //review proposal
    await user.click(submitButton); //submit proposal

    await screen.findByText('Successfully submitted the proposal');
  });
});
