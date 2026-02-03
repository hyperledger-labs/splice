// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { dateTimeFormatISO } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import dayjs from 'dayjs';
import { rest } from 'msw';
import { describe, expect, test } from 'vitest';
import App from '../../../App';
import { CreateUnallocatedUnclaimedActivityRecordForm } from '../../../components/forms/CreateUnallocatedUnclaimedActivityRecordForm';
import { SvConfigProvider } from '../../../utils';
import { PROPOSAL_SUMMARY_SUBTITLE, PROPOSAL_SUMMARY_TITLE } from '../../../utils/constants';
import { Wrapper } from '../../helpers';
import { svPartyId } from '../../mocks/constants';
import { server, svUrl } from '../../setup/setup';

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

describe('Create Unallocated Unclaimed Activity Record Form', () => {
  test('should render all Create Unallocated Unclaimed Activity Record Form components', () => {
    render(
      <Wrapper>
        <CreateUnallocatedUnclaimedActivityRecordForm />
      </Wrapper>
    );

    expect(
      screen.getByTestId('create-unallocated-unclaimed-activity-record-form')
    ).toBeInTheDocument();
    expect(screen.getByText('Action')).toBeInTheDocument();

    const actionInput = screen.getByTestId('create-unallocated-unclaimed-activity-record-action');
    expect(actionInput).toBeInTheDocument();
    expect(actionInput.getAttribute('value')).toBe('Create Unclaimed Activity Record');

    const summaryInput = screen.getByTestId('create-unallocated-unclaimed-activity-record-summary');
    expect(summaryInput).toBeInTheDocument();
    expect(summaryInput.getAttribute('value')).toBeNull();

    const summarySubtitle = screen.getByTestId(
      'create-unallocated-unclaimed-activity-record-summary-subtitle'
    );
    expect(summarySubtitle).toBeInTheDocument();
    expect(summarySubtitle.textContent).toBe(PROPOSAL_SUMMARY_SUBTITLE);

    const urlInput = screen.getByTestId('create-unallocated-unclaimed-activity-record-url');
    expect(urlInput).toBeInTheDocument();
    expect(urlInput.getAttribute('value')).toBe('');

    const beneficiaryInput = screen.getByTestId(
      'create-unallocated-unclaimed-activity-record-beneficiary'
    );
    expect(beneficiaryInput).toBeInTheDocument();
    expect(beneficiaryInput.getAttribute('value')).toBe('');

    const amountInput = screen.getByTestId('create-unallocated-unclaimed-activity-record-amount');
    expect(amountInput).toBeInTheDocument();
    expect(amountInput.getAttribute('value')).toBe('');

    const mintBeforeInput = screen.getByTestId(
      'create-unallocated-unclaimed-activity-record-mint-before-field'
    );
    expect(mintBeforeInput).toBeInTheDocument();

    expect(screen.getByText('Review Proposal')).toBeInTheDocument();
  });

  test('should render errors when submit button is clicked on new form', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <CreateUnallocatedUnclaimedActivityRecordForm />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('create-unallocated-unclaimed-activity-record-action');
    const submitButton = screen.getByTestId('submit-button');
    expect(submitButton).toBeInTheDocument();

    await user.click(submitButton);
    expect(submitButton.getAttribute('disabled')).toBeDefined();
    await expect(async () => await user.click(submitButton)).rejects.toThrowError(
      /Unable to perform pointer interaction/
    );

    screen.getByText('Summary is required');
    screen.getByText('Invalid URL');

    expect(
      screen.getByTestId('create-unallocated-unclaimed-activity-record-beneficiary-error')
        .textContent
    ).toBe('Beneficiary is required');

    expect(
      screen.getByTestId('create-unallocated-unclaimed-activity-record-amount-error').textContent
    ).toBe('Amount is required');

    // completing the form should reenable the submit button
    const summaryInput = screen.getByTestId('create-unallocated-unclaimed-activity-record-summary');
    expect(summaryInput).toBeInTheDocument();
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('create-unallocated-unclaimed-activity-record-url');
    expect(urlInput).toBeInTheDocument();
    await user.type(urlInput, 'https://example.com');

    const beneficiaryInput = screen.getByTestId(
      'create-unallocated-unclaimed-activity-record-beneficiary'
    );
    expect(beneficiaryInput).toBeInTheDocument();
    await user.type(beneficiaryInput, 'beneficiary123');

    const amountInput = screen.getByTestId('create-unallocated-unclaimed-activity-record-amount');
    expect(amountInput).toBeInTheDocument();
    await user.type(amountInput, '100');

    await user.click(actionInput); // using this to trigger the onBlur event which triggers the validation

    expect(submitButton.getAttribute('disabled')).toBeNull();
  });

  test('expiry date must be in the future', async () => {
    render(
      <Wrapper>
        <CreateUnallocatedUnclaimedActivityRecordForm />
      </Wrapper>
    );

    const expiryDateInput = screen.getByTestId(
      'create-unallocated-unclaimed-activity-record-expiry-date-field'
    );
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

  test('mint before date must be in the future', async () => {
    render(
      <Wrapper>
        <CreateUnallocatedUnclaimedActivityRecordForm />
      </Wrapper>
    );

    const mintBeforeInput = screen.getByTestId(
      'create-unallocated-unclaimed-activity-record-mint-before-field'
    );
    expect(mintBeforeInput).toBeInTheDocument();

    const thePast = dayjs().subtract(1, 'day').format(dateTimeFormatISO);
    const theFuture = dayjs().add(1, 'day').format(dateTimeFormatISO);

    fireEvent.change(mintBeforeInput, { target: { value: thePast } });

    await waitFor(() => {
      expect(screen.queryByText('Date must be in the future')).toBeInTheDocument();
    });

    fireEvent.change(mintBeforeInput, { target: { value: theFuture } });

    await waitFor(() => {
      expect(screen.queryByText('Date must be in the future')).not.toBeInTheDocument();
    });
  });

  test('mint before date must be after effective date', async () => {
    render(
      <Wrapper>
        <CreateUnallocatedUnclaimedActivityRecordForm />
      </Wrapper>
    );

    const expiryDateInput = screen.getByTestId(
      'create-unallocated-unclaimed-activity-record-expiry-date-field'
    );
    const effectiveDateInput = screen.getByTestId(
      'create-unallocated-unclaimed-activity-record-effective-date-field'
    );
    const mintBeforeInput = screen.getByTestId(
      'create-unallocated-unclaimed-activity-record-mint-before-field'
    );

    // set valid dates
    const expiryDate = dayjs().add(10, 'days').format(dateTimeFormatISO);
    fireEvent.change(expiryDateInput, { target: { value: expiryDate } });

    const effectiveDate = dayjs().add(14, 'days').format(dateTimeFormatISO);
    fireEvent.change(effectiveDateInput, { target: { value: effectiveDate } });

    const validMintBeforeDate = dayjs().add(16, 'days').format(dateTimeFormatISO);
    fireEvent.change(mintBeforeInput, { target: { value: validMintBeforeDate } });

    await waitFor(() => {
      expect(
        screen.queryByText('Mint Before date must be after Effective Date')
      ).not.toBeInTheDocument();
    });

    // Set an invalid mint before date (before the effective date)
    const invalidMintBeforeDate = dayjs(effectiveDate).subtract(1, 'day').format(dateTimeFormatISO);
    fireEvent.change(mintBeforeInput, { target: { value: invalidMintBeforeDate } });

    await waitFor(() => {
      expect(
        screen.queryByText('Mint Before date must be after Effective Date')
      ).toBeInTheDocument();
    });
  });

  test('effective date must be after expiry date', async () => {
    render(
      <Wrapper>
        <CreateUnallocatedUnclaimedActivityRecordForm />
      </Wrapper>
    );

    const expiryDateInput = screen.getByTestId(
      'create-unallocated-unclaimed-activity-record-expiry-date-field'
    );
    const effectiveDateInput = screen.getByTestId(
      'create-unallocated-unclaimed-activity-record-effective-date-field'
    );

    const expiryDate = dayjs().add(1, 'week');
    const effectiveDate = expiryDate.subtract(1, 'day');

    fireEvent.change(expiryDateInput, {
      target: { value: expiryDate.format(dateTimeFormatISO) },
    });

    fireEvent.change(effectiveDateInput, {
      target: { value: effectiveDate.format(dateTimeFormatISO) },
    });

    await waitFor(() => {
      expect(
        screen.queryByText('Effective Date must be after expiration date')
      ).toBeInTheDocument();
    });

    const validEffectiveDate = expiryDate.add(1, 'day').format(dateTimeFormatISO);

    fireEvent.change(effectiveDateInput, {
      target: { value: validEffectiveDate },
    });

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
        <CreateUnallocatedUnclaimedActivityRecordForm />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('create-unallocated-unclaimed-activity-record-action');

    const summaryInput = screen.getByTestId('create-unallocated-unclaimed-activity-record-summary');
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('create-unallocated-unclaimed-activity-record-url');
    await user.type(urlInput, 'https://example.com');

    const beneficiaryInput = screen.getByTestId(
      'create-unallocated-unclaimed-activity-record-beneficiary'
    );
    await user.type(beneficiaryInput, 'beneficiary123');

    const amountInput = screen.getByTestId('create-unallocated-unclaimed-activity-record-amount');
    await user.type(amountInput, '100');

    const mintBeforeInput = screen.getByTestId(
      'create-unallocated-unclaimed-activity-record-mint-before-field'
    );
    const futureDate = dayjs().add(14, 'days').format(dateTimeFormatISO);
    fireEvent.change(mintBeforeInput, { target: { value: futureDate } });

    expect(screen.getByText('Review Proposal')).toBeInTheDocument();
    const submitButton = screen.getByTestId('submit-button');
    await user.click(actionInput); // using this to trigger the onBlur event which triggers the validation

    await waitFor(async () => {
      expect(submitButton.getAttribute('disabled')).toBeNull();
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
        <CreateUnallocatedUnclaimedActivityRecordForm />
      </Wrapper>
    );

    const submitButton = screen.getByTestId('submit-button');

    const summaryInput = screen.getByTestId('create-unallocated-unclaimed-activity-record-summary');
    expect(summaryInput).toBeInTheDocument();
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('create-unallocated-unclaimed-activity-record-url');
    expect(urlInput).toBeInTheDocument();
    await user.type(urlInput, 'https://example.com');

    const beneficiaryInput = screen.getByTestId(
      'create-unallocated-unclaimed-activity-record-beneficiary'
    );
    expect(beneficiaryInput).toBeInTheDocument();
    await user.type(beneficiaryInput, 'beneficiary123');

    const amountInput = screen.getByTestId('create-unallocated-unclaimed-activity-record-amount');
    expect(amountInput).toBeInTheDocument();
    await user.type(amountInput, '100');

    const mintBeforeInput = screen.getByTestId(
      'create-unallocated-unclaimed-activity-record-mint-before-field'
    );
    const futureDate = dayjs().add(14, 'days').format(dateTimeFormatISO);
    fireEvent.change(mintBeforeInput, { target: { value: futureDate } });

    await waitFor(async () => {
      expect(submitButton.getAttribute('disabled')).toBeNull();
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
        <CreateUnallocatedUnclaimedActivityRecordForm />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('create-unallocated-unclaimed-activity-record-action');
    const submitButton = screen.getByTestId('submit-button');

    const summaryInput = screen.getByTestId('create-unallocated-unclaimed-activity-record-summary');
    expect(summaryInput).toBeInTheDocument();
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('create-unallocated-unclaimed-activity-record-url');
    expect(urlInput).toBeInTheDocument();
    await user.type(urlInput, 'https://example.com');

    const beneficiaryInput = screen.getByTestId(
      'create-unallocated-unclaimed-activity-record-beneficiary'
    );
    expect(beneficiaryInput).toBeInTheDocument();
    await user.type(beneficiaryInput, 'beneficiary123');

    const amountInput = screen.getByTestId('create-unallocated-unclaimed-activity-record-amount');
    expect(amountInput).toBeInTheDocument();
    await user.type(amountInput, '100');

    const mintBeforeInput = screen.getByTestId(
      'create-unallocated-unclaimed-activity-record-mint-before-field'
    );
    const futureDate = dayjs().add(14, 'days').format(dateTimeFormatISO);
    fireEvent.change(mintBeforeInput, { target: { value: futureDate } });

    await user.click(actionInput); // using this to trigger the onBlur event which triggers the validation

    await waitFor(async () => {
      expect(submitButton.getAttribute('disabled')).toBeNull();
    });

    await user.click(submitButton); //review proposal
    await user.click(submitButton); //submit proposal

    await screen.findByText('Successfully submitted the proposal');
  });
});
