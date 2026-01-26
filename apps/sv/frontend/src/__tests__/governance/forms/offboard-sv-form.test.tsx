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

describe('Offboard SV Form', () => {
  test('should render all Offboard SV Form components', () => {
    render(
      <Wrapper>
        <OffboardSvForm />
      </Wrapper>
    );

    expect(screen.getByTestId('offboard-sv-form')).toBeInTheDocument();
    expect(screen.getByText('Action')).toBeInTheDocument();

    const actionInput = screen.getByTestId('offboard-sv-action');
    expect(actionInput).toBeInTheDocument();
    expect(actionInput.getAttribute('value')).toBe('Offboard Member');

    const summaryInput = screen.getByTestId('offboard-sv-summary');
    expect(summaryInput).toBeInTheDocument();
    expect(summaryInput.getAttribute('value')).toBeNull();

    const summarySubtitle = screen.getByTestId('offboard-sv-summary-subtitle');
    expect(summarySubtitle).toBeInTheDocument();
    expect(summarySubtitle.textContent).toBe(PROPOSAL_SUMMARY_SUBTITLE);

    const urlInput = screen.getByTestId('offboard-sv-url');
    expect(urlInput).toBeInTheDocument();
    expect(urlInput.getAttribute('value')).toBe('');

    const memberInput = screen.getByTestId('offboard-sv-member-dropdown');
    expect(memberInput).toBeInTheDocument();
    expect(memberInput.getAttribute('value')).toBe('');

    expect(screen.getByText('Review Proposal')).toBeInTheDocument();
  });

  test('should render errors when submit button is clicked on new form', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <OffboardSvForm />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('offboard-sv-action');
    const submitButton = screen.getByTestId('submit-button');
    expect(submitButton).toBeInTheDocument();
    expect(screen.getByText('Review Proposal')).toBeInTheDocument();

    await user.click(submitButton);
    expect(submitButton.getAttribute('disabled')).toBeDefined();
    await expect(async () => await user.click(submitButton)).rejects.toThrowError(
      /Unable to perform pointer interaction/
    );

    expect(screen.getByText('Summary is required')).toBeInTheDocument();
    expect(screen.getByText('Invalid URL')).toBeInTheDocument();
    expect(screen.getByText('SV is required')).toBeInTheDocument();

    // completing the form should reenable the submit button
    const summaryInput = screen.getByTestId('offboard-sv-summary');
    expect(summaryInput).toBeInTheDocument();
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('offboard-sv-url');
    expect(urlInput).toBeInTheDocument();
    await user.type(urlInput, 'https://example.com');

    const memberDropdown = screen.getByTestId('offboard-sv-member-dropdown');
    expect(memberDropdown).toBeInTheDocument();

    const selectInput = screen.getByRole('combobox');
    fireEvent.mouseDown(selectInput);

    await waitFor(async () => {
      const memberToSelect = screen.getByText('Digital-Asset-Eng-2');
      expect(memberToSelect).toBeInTheDocument();
      await user.click(memberToSelect);
    });

    await user.click(actionInput); // using this to trigger the onBlur event which triggers the validation

    expect(submitButton.getAttribute('disabled')).toBe(null);
  });

  test('expiry date must be in the future', async () => {
    render(
      <Wrapper>
        <OffboardSvForm />
      </Wrapper>
    );

    const expiryDateInput = screen.getByTestId('offboard-sv-expiry-date-field');
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
        <OffboardSvForm />
      </Wrapper>
    );

    const expiryDateInput = screen.getByTestId('offboard-sv-expiry-date-field');
    const effectiveDateInput = screen.getByTestId('offboard-sv-effective-date-field');

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

  test('sv dropdown contains all svs', async () => {
    render(
      <Wrapper>
        <OffboardSvForm />
      </Wrapper>
    );

    const memberDropdown = screen.getByTestId('offboard-sv-member-dropdown');
    expect(memberDropdown).toBeInTheDocument();

    const selectInput = screen.getByRole('combobox');
    fireEvent.mouseDown(selectInput);

    const svOptions = await screen.findAllByRole('option');
    expect(svOptions.length).toBeGreaterThan(1);
    expect(svOptions.map(option => option.textContent)).toEqual(
      expect.arrayContaining(['Digital-Asset-2', 'Digital-Asset-Eng-2'])
    );
  });

  test('should show proposal review page after form completion', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <OffboardSvForm />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('offboard-sv-action');

    const summaryInput = screen.getByTestId('offboard-sv-summary');
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('offboard-sv-url');
    await user.type(urlInput, 'https://example.com');

    const selectInput = screen.getByRole('combobox');
    fireEvent.mouseDown(selectInput);

    await waitFor(async () => {
      const memberToSelect = screen.getByText('Digital-Asset-Eng-2');
      await user.click(memberToSelect);
    });

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
        <OffboardSvForm />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('offboard-sv-action');

    const summaryInput = screen.getByTestId('offboard-sv-summary');
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('offboard-sv-url');
    await user.type(urlInput, 'https://example.com');

    const selectInput = screen.getByRole('combobox');
    fireEvent.mouseDown(selectInput);

    await waitFor(async () => {
      const memberToSelect = screen.getByText('Digital-Asset-Eng-2');
      await user.click(memberToSelect);
    });

    expect(screen.getByText('Review Proposal')).toBeInTheDocument();
    const submitButton = screen.getByTestId('submit-button');
    await user.click(actionInput); // using this to trigger the onBlur event which triggers the validation

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
        <OffboardSvForm />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('offboard-sv-action');

    const summaryInput = screen.getByTestId('offboard-sv-summary');
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('offboard-sv-url');
    await user.type(urlInput, 'https://example.com');

    const selectInput = screen.getByRole('combobox');
    fireEvent.mouseDown(selectInput);

    await waitFor(async () => {
      const memberToSelect = screen.getByText('Digital-Asset-Eng-2');
      await user.click(memberToSelect);
    });

    const submitButton = screen.getByTestId('submit-button');
    await user.click(actionInput); // using this to trigger the onBlur event which triggers the validation

    await waitFor(async () => {
      expect(submitButton.getAttribute('disabled')).toBeNull();
    });

    await user.click(submitButton); //review proposal
    await user.click(submitButton); //submit proposal

    await screen.findByText('Successfully submitted the proposal');
  });
});
