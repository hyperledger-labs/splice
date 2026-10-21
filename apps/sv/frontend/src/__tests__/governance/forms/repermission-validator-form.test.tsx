// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, test, vi } from 'vitest';
import userEvent from '@testing-library/user-event';
import { Wrapper } from '../../helpers';
import { RepermissionValidatorForm } from '../../../components/forms/RepermissionValidatorForm';
import { server, svUrl } from '../../setup/setup';
import { http, HttpResponse } from 'msw';
import {
  CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE,
  PROPOSAL_SUMMARY_SUBTITLE,
  SUPPORTING_URL_PLACEHOLDER,
  PROPOSAL_REVIEW_TITLE,
} from '../../../utils/constants';
import App from '../../../App';
import { SvConfigProvider } from '../../../utils';
import { svPartyId } from '../../mocks/constants';
import { fireEvent } from '@testing-library/react';
import dayjs from 'dayjs';
import { dateTimeFormatISO } from '@canton-network/splice-common-frontend-utils';

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

describe('Repermission Validator Form', () => {
  test('should render all Repermission Validator Form components', () => {
    render(
      <Wrapper>
        <RepermissionValidatorForm />
      </Wrapper>
    );

    expect(screen.getByTestId('repermission-validator-form')).toBeInTheDocument();
    expect(screen.getByText(CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE)).toBeInTheDocument();

    const actionInput = screen.getByTestId('repermission-validator-action');
    expect(actionInput).toBeInTheDocument();
    expect(actionInput.textContent).toBe('Repermission Validator');

    const participantInput = screen.getByTestId('repermission-validator-participant-id');
    expect(participantInput).toBeInTheDocument();
    expect(participantInput.getAttribute('value')).toBe('');

    const summaryInput = screen.getByTestId('repermission-validator-summary');
    expect(summaryInput).toBeInTheDocument();
    expect(summaryInput.getAttribute('value')).toBeNull();

    const summarySubtitle = screen.getByTestId('repermission-validator-summary-subtitle');
    expect(summarySubtitle).toBeInTheDocument();
    expect(summarySubtitle.textContent).toBe(PROPOSAL_SUMMARY_SUBTITLE);

    const urlInput = screen.getByTestId('repermission-validator-url');
    expect(urlInput).toBeInTheDocument();
    expect(urlInput.getAttribute('value')).toBe('');
    expect(urlInput).toHaveAttribute('placeholder', SUPPORTING_URL_PLACEHOLDER);

    expect(screen.getByText('Review Proposal')).toBeInTheDocument();
  });

  test('participant ID must be valid format', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <RepermissionValidatorForm />
      </Wrapper>
    );

    const participantInput = screen.getByTestId('repermission-validator-participant-id');
    const actionInput = screen.getByTestId('repermission-validator-action');

    await user.type(participantInput, 'invalid-participant-id');
    await user.click(actionInput);

    await waitFor(() => {
      expect(
        screen.getByText(
          'Invalid ParticipantId format. Expected format: PAR::identifier::fingerprint'
        )
      ).toBeInTheDocument();
    });

    server.use(
      http.get(`${svUrl}/v0/admin/validator/unpermissions/:participant_id`, ({ params }) => {
        const participantId = decodeURIComponent(params.participant_id as string);
        expect(participantId).toBe('PAR::alice::1234567890');

        return HttpResponse.json({
          unpermissions: [{ contract_id: '00abcd1234' }],
        });
      })
    );

    await user.clear(participantInput);
    await user.type(participantInput, 'PAR::alice::1234567890');
    await user.click(actionInput);

    await waitFor(() => {
      expect(
        screen.queryByText(
          'Invalid ParticipantId format. Expected format: PAR::identifier::fingerprint'
        )
      ).not.toBeInTheDocument();
    });
  });

  test('should reject if 0 unpermission contracts found', async () => {
    const user = userEvent.setup();

    server.use(
      http.get(`${svUrl}/v0/admin/validator/unpermissions/:participant_id`, () => {
        return HttpResponse.json({
          unpermissions: [],
        });
      })
    );

    render(
      <Wrapper>
        <RepermissionValidatorForm />
      </Wrapper>
    );

    const participantInput = screen.getByTestId('repermission-validator-participant-id');

    await user.type(participantInput, 'PAR::alice::1234567890');
    fireEvent.blur(participantInput);

    await waitFor(
      () => {
        expect(
          screen.getByText('No unpermission contracts found for this participant.')
        ).toBeInTheDocument();
      },
      { timeout: 3000 }
    );
  });

  test('should reject if multiple unpermission contracts found', async () => {
    const user = userEvent.setup();

    server.use(
      http.get(`${svUrl}/v0/admin/validator/unpermissions/:participant_id`, () => {
        return HttpResponse.json({
          unpermissions: [{ contract_id: '00abcd123400' }, { contract_id: '00efgh567800' }],
        });
      })
    );

    render(
      <Wrapper>
        <RepermissionValidatorForm />
      </Wrapper>
    );

    const participantInput = screen.getByTestId('repermission-validator-participant-id');

    await user.type(participantInput, 'PAR::alice::1234567890');
    fireEvent.blur(participantInput);

    await waitFor(
      () => {
        expect(
          screen.getByText(
            'Multiple unpermission contracts found. Please wait for SV automation to merge them.'
          )
        ).toBeInTheDocument();
      },
      { timeout: 3000 }
    );
  });

  test('expiry date must be in the future', async () => {
    render(
      <Wrapper>
        <RepermissionValidatorForm />
      </Wrapper>
    );

    const expiryDateInput = screen.getByTestId('repermission-validator-expiry-date-field');
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
        <RepermissionValidatorForm />
      </Wrapper>
    );

    const expiryDateInput = screen.getByTestId('repermission-validator-expiry-date-field');
    const effectiveDateInput = screen.getByTestId('repermission-validator-effective-date-field');

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

    server.use(
      http.get(`${svUrl}/v0/admin/validator/unpermissions/:participant_id`, () => {
        return HttpResponse.json({
          unpermissions: [{ contract_id: '00abcd1234' }],
        });
      })
    );

    render(
      <Wrapper>
        <RepermissionValidatorForm />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('repermission-validator-action');

    const participantInput = screen.getByTestId('repermission-validator-participant-id');
    await user.type(participantInput, 'PAR::alice::1234567890');

    const summaryInput = screen.getByTestId('repermission-validator-summary');
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('repermission-validator-url');
    await user.type(urlInput, 'https://example.com');

    const submitButton = screen.getByTestId('submit-button');

    await user.click(actionInput);

    await waitFor(async () => {
      expect(submitButton.getAttribute('disabled')).toBeNull();
    });

    await user.click(submitButton);

    expect(screen.getByText(PROPOSAL_REVIEW_TITLE)).toBeInTheDocument();

    expect(screen.getByText('PAR::alice::1234567890')).toBeInTheDocument();
    expect(screen.getByText('Summary of the proposal')).toBeInTheDocument();
  });

  test('should show error on form if submission fails', async () => {
    const createVoteRequestMock = vi.fn();

    server.use(
      http.get(`${svUrl}/v0/admin/validator/unpermissions/:participant_id`, () => {
        return HttpResponse.json({
          unpermissions: [{ contract_id: '00abcd1234' }],
        });
      }),
      http.post(`${svUrl}/v0/admin/sv/voterequest/create`, () => {
        createVoteRequestMock();
        return HttpResponse.json({ error: 'Service Unavailable' }, { status: 503 });
      })
    );

    const user = userEvent.setup();

    render(
      <Wrapper>
        <RepermissionValidatorForm />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('repermission-validator-action');

    const participantInput = screen.getByTestId('repermission-validator-participant-id');
    await user.type(participantInput, 'PAR::alice::1234567890');

    const summaryInput = screen.getByTestId('repermission-validator-summary');
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('repermission-validator-url');
    await user.type(urlInput, 'https://example.com');

    const submitButton = screen.getByTestId('submit-button');

    await user.click(actionInput);

    await waitFor(async () => {
      expect(submitButton.getAttribute('disabled')).toBeNull();
    });

    await user.click(submitButton);
    await user.click(submitButton);

    expect(screen.getByTestId('proposal-submission-error')).toBeInTheDocument();
    expect(screen.getByText(/Submission failed/)).toBeInTheDocument();
    expect(screen.getByText(/Service Unavailable/)).toBeInTheDocument();

    expect(createVoteRequestMock).toHaveBeenCalledOnce();
  });

  test('should redirect to governance page after successful submission', async () => {
    const createVoteRequestMock = vi.fn();
    let requestBody = '';

    server.use(
      http.get(`${svUrl}/v0/admin/validator/unpermissions/:participant_id`, () => {
        return HttpResponse.json({
          unpermissions: [{ contract_id: '00abcd1234' }],
        });
      }),
      http.post(`${svUrl}/v0/admin/sv/voterequest/create`, async ({ request }) => {
        createVoteRequestMock();
        requestBody = await request.text();
        return HttpResponse.json({});
      })
    );

    const user = userEvent.setup();

    render(
      <Wrapper>
        <RepermissionValidatorForm />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('repermission-validator-action');

    const participantInput = screen.getByTestId('repermission-validator-participant-id');
    await user.type(participantInput, 'PAR::alice::1234567890');

    const summaryInput = screen.getByTestId('repermission-validator-summary');
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('repermission-validator-url');
    await user.type(urlInput, 'https://example.com');

    const submitButton = screen.getByTestId('submit-button');

    await user.click(actionInput);

    await waitFor(async () => {
      expect(submitButton.getAttribute('disabled')).toBeNull();
    });

    await user.click(submitButton);
    await user.click(submitButton);

    await screen.findByText('Successfully submitted the proposal');

    expect(createVoteRequestMock).toHaveBeenCalledOnce();
    expect(requestBody).toContain('"validatorUnpermissionCid":"00abcd1234"');
  });
});
