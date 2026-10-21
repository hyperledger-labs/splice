// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, test, vi } from 'vitest';
import userEvent from '@testing-library/user-event';
import { SvConfigProvider } from '../../../utils';
import App from '../../../App';
import { svPartyId } from '../../mocks/constants';
import { Wrapper } from '../../helpers';
import { dateTimeFormatISO } from '@canton-network/splice-common-frontend-utils';
import dayjs from 'dayjs';
import { UnpermissionValidatorForm } from '../../../components/forms/UnpermissionValidatorForm';
import { server, svUrl } from '../../setup/setup';
import { http, HttpResponse } from 'msw';
import {
  CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE,
  PROPOSAL_REVIEW_TITLE,
  PROPOSAL_SUMMARY_SUBTITLE,
  SUPPORTING_URL_PLACEHOLDER,
} from '../../../utils/constants';

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

    expect(await screen.findAllByDisplayValue(svPartyId)).not.toHaveLength(0);
  });
});

describe('Unpermission Validator Form', () => {
  test('should render all Unpermission Validator Form components', () => {
    render(
      <Wrapper>
        <UnpermissionValidatorForm />
      </Wrapper>
    );

    expect(screen.getByTestId('unpermission-validator-form')).toBeInTheDocument();
    expect(screen.getByText(CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE)).toBeInTheDocument();

    const actionInput = screen.getByTestId('unpermission-validator-action');
    expect(actionInput).toBeInTheDocument();
    expect(actionInput.textContent).toBe('Unpermission Validator');

    const participantInput = screen.getByTestId('unpermission-validator-participant-id');
    expect(participantInput).toBeInTheDocument();
    expect(participantInput.getAttribute('value')).toBe('');

    const revokedCheckbox = screen.getByRole('checkbox');
    expect(revokedCheckbox).toBeInTheDocument();
    expect(revokedCheckbox).not.toBeChecked();

    const loginAfterInput = screen.getByTestId('unpermission-validator-login-after-field');
    expect(loginAfterInput).toBeInTheDocument();

    const summaryInput = screen.getByTestId('unpermission-validator-summary');
    expect(summaryInput).toBeInTheDocument();
    expect(summaryInput.getAttribute('value')).toBeNull();

    const summarySubtitle = screen.getByTestId('unpermission-validator-summary-subtitle');
    expect(summarySubtitle).toBeInTheDocument();
    expect(summarySubtitle.textContent).toBe(PROPOSAL_SUMMARY_SUBTITLE);

    const urlInput = screen.getByTestId('unpermission-validator-url');
    expect(urlInput).toBeInTheDocument();
    expect(urlInput.getAttribute('value')).toBe('');
    expect(urlInput).toHaveAttribute('placeholder', SUPPORTING_URL_PLACEHOLDER);

    expect(screen.getByText('Review Proposal')).toBeInTheDocument();
  });

  test('should render errors when submit button is clicked on new form', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <UnpermissionValidatorForm />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('unpermission-validator-action');
    const submitButton = screen.getByTestId('submit-button');
    expect(submitButton).toBeInTheDocument();
    expect(screen.getByText('Review Proposal')).toBeInTheDocument();

    await user.click(submitButton);
    expect(submitButton.getAttribute('disabled')).not.toBeNull();
    await expect(async () => await user.click(submitButton)).rejects.toThrowError(
      /Unable to perform pointer interaction/
    );

    expect(screen.getByText('Required')).toBeInTheDocument(); // Participant ID error
    expect(screen.getByText('Summary is required')).toBeInTheDocument();
    expect(screen.getByText('Invalid URL')).toBeInTheDocument();

    // completing the form should reenable the submit button
    const participantInput = screen.getByTestId('unpermission-validator-participant-id');
    await user.type(participantInput, 'PAR::alice::1234567890');

    const summaryInput = screen.getByTestId('unpermission-validator-summary');
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('unpermission-validator-url');
    await user.type(urlInput, 'https://example.com');

    await user.click(actionInput); // using this to trigger the onBlur event which triggers the validation

    expect(submitButton.getAttribute('disabled')).toBe(null);
  });

  test('participant ID must be valid format', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <UnpermissionValidatorForm />
      </Wrapper>
    );

    const participantInput = screen.getByTestId('unpermission-validator-participant-id');
    const actionInput = screen.getByTestId('unpermission-validator-action');

    await user.type(participantInput, 'invalid-participant-id');
    await user.click(actionInput); // trigger validation on blur

    await waitFor(() => {
      expect(
        screen.getByText(
          'Invalid ParticipantId format. Expected format: PAR::identifier::fingerprint'
        )
      ).toBeInTheDocument();
    });

    await user.clear(participantInput);
    await user.type(participantInput, 'PAR::alice::1234567890');
    await user.click(actionInput); // trigger validation on blur

    await waitFor(() => {
      expect(
        screen.queryByText(
          'Invalid ParticipantId format. Expected format: PAR::identifier::fingerprint'
        )
      ).not.toBeInTheDocument();
    });
  });

  test('toggling permanent revocation hides login after field', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <UnpermissionValidatorForm />
      </Wrapper>
    );

    const revokedCheckbox = screen.getByRole('checkbox');

    // Login after should be visible initially
    expect(screen.getByTestId('unpermission-validator-login-after-field')).toBeInTheDocument();

    // Click permanent revocation
    await user.click(revokedCheckbox);

    // Login after should disappear
    await waitFor(() => {
      expect(
        screen.queryByTestId('unpermission-validator-login-after-field')
      ).not.toBeInTheDocument();
    });

    // Uncheck and it should reappear
    await user.click(revokedCheckbox);
    await waitFor(() => {
      expect(screen.getByTestId('unpermission-validator-login-after-field')).toBeInTheDocument();
    });
  });

  test('expiry date must be in the future', async () => {
    render(
      <Wrapper>
        <UnpermissionValidatorForm />
      </Wrapper>
    );

    const expiryDateInput = screen.getByTestId('unpermission-validator-expiry-date-field');
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
        <UnpermissionValidatorForm />
      </Wrapper>
    );

    const expiryDateInput = screen.getByTestId('unpermission-validator-expiry-date-field');
    const effectiveDateInput = screen.getByTestId('unpermission-validator-effective-date-field');

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
        <UnpermissionValidatorForm />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('unpermission-validator-action');

    const participantInput = screen.getByTestId('unpermission-validator-participant-id');
    await user.type(participantInput, 'PAR::alice::1234567890');

    const summaryInput = screen.getByTestId('unpermission-validator-summary');
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('unpermission-validator-url');
    await user.type(urlInput, 'https://example.com');

    expect(screen.getByText('Review Proposal')).toBeInTheDocument();
    const submitButton = screen.getByTestId('submit-button');
    await user.click(actionInput); // using this to trigger the onBlur event which triggers the validation

    await waitFor(async () => {
      expect(submitButton.getAttribute('disabled')).toBeNull();
    });

    await user.click(submitButton);

    expect(screen.getByText(PROPOSAL_REVIEW_TITLE)).toBeInTheDocument();

    // Verify Review Field values
    expect(screen.getByText('PAR::alice::1234567890')).toBeInTheDocument();
    expect(screen.getByText('Permanent Revocation')).toBeInTheDocument();
    expect(screen.getByText('Login After')).toBeInTheDocument();
  });

  test('should show error on form if submission fails', async () => {
    const createVoteRequestMock = vi.fn();
    server.use(
      http.post(`${svUrl}/v0/admin/sv/voterequest/create`, () => {
        createVoteRequestMock();
        return HttpResponse.json({ error: 'Service Unavailable' }, { status: 503 });
      })
    );

    const user = userEvent.setup();

    render(
      <Wrapper>
        <UnpermissionValidatorForm />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('unpermission-validator-action');

    const participantInput = screen.getByTestId('unpermission-validator-participant-id');
    await user.type(participantInput, 'PAR::alice::1234567890');

    const summaryInput = screen.getByTestId('unpermission-validator-summary');
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('unpermission-validator-url');
    await user.type(urlInput, 'https://example.com');

    const submitButton = screen.getByTestId('submit-button');
    await user.click(actionInput);

    await waitFor(async () => {
      expect(submitButton.getAttribute('disabled')).toBeNull();
    });

    await user.click(submitButton); //review proposal
    await user.click(submitButton); //submit proposal

    expect(screen.getByTestId('proposal-submission-error')).toBeInTheDocument();
    expect(screen.getByText(/Submission failed/)).toBeInTheDocument();
    expect(screen.getByText(/Service Unavailable/)).toBeInTheDocument();

    expect(createVoteRequestMock).toHaveBeenCalledOnce();
  });

  test('should redirect to governance page after successful submission', async () => {
    const createVoteRequestMock = vi.fn();
    let requestBody = '';
    server.use(
      http.post(`${svUrl}/v0/admin/sv/voterequest/create`, async ({ request }) => {
        createVoteRequestMock();
        requestBody = await request.text();
        return HttpResponse.json({});
      })
    );

    const user = userEvent.setup();

    render(
      <Wrapper>
        <UnpermissionValidatorForm />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('unpermission-validator-action');

    const participantInput = screen.getByTestId('unpermission-validator-participant-id');
    await user.type(participantInput, 'PAR::alice::1234567890');

    const summaryInput = screen.getByTestId('unpermission-validator-summary');
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('unpermission-validator-url');
    await user.type(urlInput, 'https://example.com');

    const revokedCheckbox = screen.getByRole('checkbox');
    await user.click(revokedCheckbox);

    const submitButton = screen.getByTestId('submit-button');
    await user.click(actionInput); // using this to trigger the onBlur event which triggers the validation

    await waitFor(async () => {
      expect(submitButton.getAttribute('disabled')).toBeNull();
    });

    await user.click(submitButton); //review proposal
    await user.click(submitButton); //submit proposal

    await screen.findByText('Successfully submitted the proposal');

    expect(createVoteRequestMock).toHaveBeenCalledOnce();

    // Verify the correct API payload was sent
    expect(requestBody).toContain('"participantId":"PAR::alice::1234567890"');
    expect(requestBody).toContain('"revoked":true');
    expect(requestBody).toContain('"loginAfter":null');
  });
});
