// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { UpdateSvRewardWeightForm } from '../../../components/forms/UpdateSvRewardWeightForm';
import userEvent from '@testing-library/user-event';
import { SvConfigProvider } from '../../../utils';
import App from '../../../App';
import { svPartyId } from '../../mocks/constants';
import { Wrapper } from '../../helpers';
import { dateTimeFormatISO } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import dayjs from 'dayjs';
import { server, svUrl } from '../../setup/setup';
import { rest } from 'msw';

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

describe('Update SV Reward Weight Form', () => {
  test('should render all Update SV Reward Weight Form components', () => {
    render(
      <Wrapper>
        <UpdateSvRewardWeightForm />
      </Wrapper>
    );

    expect(screen.getByTestId('update-sv-reward-weight-form')).toBeDefined();
    expect(screen.getByText('Action')).toBeDefined();

    const actionInput = screen.getByTestId('update-sv-reward-weight-action');
    expect(actionInput).toBeDefined();
    expect(actionInput.getAttribute('value')).toBe('Update SV Reward Weight');

    const summaryInput = screen.getByTestId('update-sv-reward-weight-summary');
    expect(summaryInput).toBeDefined();
    expect(summaryInput.getAttribute('value')).toBeNull();

    const urlInput = screen.getByTestId('update-sv-reward-weight-url');
    expect(urlInput).toBeDefined();
    expect(urlInput.getAttribute('value')).toBe('');

    const memberInput = screen.getByTestId('update-sv-reward-weight-member-dropdown');
    expect(memberInput).toBeDefined();
    expect(memberInput.getAttribute('value')).toBe('');

    const weightInput = screen.getByTestId('update-sv-reward-weight-weight');
    expect(weightInput).toBeDefined();
    expect(weightInput.getAttribute('value')).toBe('');
  });

  test('should render errors when submit button is clicked on new form', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <UpdateSvRewardWeightForm />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('update-sv-reward-weight-action');
    const submitButton = screen.getByTestId('submit-button');
    expect(submitButton).toBeDefined();

    await user.click(submitButton);
    expect(submitButton.getAttribute('disabled')).toBeDefined();
    expect(async () => await user.click(submitButton)).rejects.toThrowError(
      /Unable to perform pointer interaction/
    );

    screen.getByText('Summary is required');
    screen.getByText('Invalid URL');
    screen.getByText('Weight is required');
    screen.getByText('SV is required');

    // completing the form should reenable the submit button
    const summaryInput = screen.getByTestId('update-sv-reward-weight-summary');
    expect(summaryInput).toBeDefined();
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('update-sv-reward-weight-url');
    expect(urlInput).toBeDefined();
    await user.type(urlInput, 'https://example.com');

    const memberDropdown = screen.getByTestId('update-sv-reward-weight-member-dropdown');
    expect(memberDropdown).toBeDefined();

    const selectInput = screen.getByRole('combobox');
    fireEvent.mouseDown(selectInput);

    await waitFor(async () => {
      const memberToSelect = screen.getByText('Digital-Asset-Eng-2');
      expect(memberToSelect).toBeDefined();
      await user.click(memberToSelect);
    });

    const weightInput = screen.getByTestId('update-sv-reward-weight-weight');
    expect(weightInput).toBeDefined();
    await user.type(weightInput, '1000');

    await user.click(actionInput); // using this to trigger the onBlur event which triggers the validation

    expect(submitButton.getAttribute('disabled')).toBe('');
  });

  test('expiry date must be in the future', async () => {
    const user = userEvent.setup();
    render(
      <Wrapper>
        <UpdateSvRewardWeightForm />
      </Wrapper>
    );

    const expiryDateInput = screen.getByTestId('update-sv-reward-weight-expiry-date-field');
    expect(expiryDateInput).toBeDefined();

    const thePast = dayjs().subtract(1, 'day').format(dateTimeFormatISO);
    const theFuture = dayjs().add(1, 'day').format(dateTimeFormatISO);

    await user.type(expiryDateInput, thePast);

    waitFor(() => {
      expect(screen.queryByText('Expiration must be in the future')).toBeDefined();
    });

    await user.type(expiryDateInput, theFuture);

    waitFor(() => {
      expect(screen.queryByText('Expiration must be in the future')).toBeNull();
    });
  });

  test('effective date must be after expiry date', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <UpdateSvRewardWeightForm />
      </Wrapper>
    );

    const expiryDateInput = screen.getByTestId('update-sv-reward-weight-expiry-date-field');
    const effectiveDateInput = screen.getByTestId('update-sv-reward-weight-effective-date-field');

    const expiryDate = dayjs().add(1, 'week');
    const effectiveDate = expiryDate.subtract(1, 'day');

    await user.type(expiryDateInput, expiryDate.format(dateTimeFormatISO));
    await user.type(effectiveDateInput, effectiveDate.format(dateTimeFormatISO));

    await waitFor(() => {
      expect(screen.queryByText('Effective Date must be after expiration date')).toBeDefined();
    });

    const validEffectiveDate = expiryDate.add(1, 'day').format(dateTimeFormatISO);

    await user.type(effectiveDateInput, validEffectiveDate);

    await waitFor(() => {
      expect(screen.queryByText('Effective Date must be after expiration date')).toBeNull();
    });
  });

  test('sv dropdown contains all svs', async () => {
    render(
      <Wrapper>
        <UpdateSvRewardWeightForm />
      </Wrapper>
    );

    const memberDropdown = screen.getByTestId('update-sv-reward-weight-member-dropdown');
    expect(memberDropdown).toBeDefined();

    const selectInput = screen.getByRole('combobox');
    fireEvent.mouseDown(selectInput);

    const svOptions = await screen.findAllByRole('option');
    expect(svOptions.length).toBeGreaterThan(1);
    expect(svOptions.map(option => option.textContent)).toEqual(
      expect.arrayContaining(['Digital-Asset-2', 'Digital-Asset-Eng-2'])
    );
  });

  test('Weight must be a valid number', async () => {
    const user = userEvent.setup();
    render(
      <Wrapper>
        <UpdateSvRewardWeightForm />
      </Wrapper>
    );

    const weightInput = screen.getByTestId('update-sv-reward-weight-weight');
    expect(weightInput).toBeDefined();
    await user.type(weightInput, '123abc');

    await waitFor(() => {
      expect(screen.getByText('Weight must be a valid number')).toBeDefined();
    });

    await user.clear(weightInput);
    await user.type(weightInput, '1001');
    await user.click(screen.getByTestId('update-sv-reward-weight-action'));

    await waitFor(() => {
      expect(screen.queryByText('Weight must be a valid number')).toBeNull();
    });
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
        <UpdateSvRewardWeightForm />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('update-sv-reward-weight-action');
    const submitButton = screen.getByTestId('submit-button');

    const summaryInput = screen.getByTestId('update-sv-reward-weight-summary');
    expect(summaryInput).toBeDefined();
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('update-sv-reward-weight-url');
    expect(urlInput).toBeDefined();
    await user.type(urlInput, 'https://example.com');

    const memberDropdown = screen.getByTestId('update-sv-reward-weight-member-dropdown');
    expect(memberDropdown).toBeDefined();

    const selectInput = screen.getByRole('combobox');
    fireEvent.mouseDown(selectInput);

    await waitFor(async () => {
      const memberToSelect = screen.getByText('Digital-Asset-Eng-2');
      expect(memberToSelect).toBeDefined();
      await user.click(memberToSelect);
    });

    const weightInput = screen.getByTestId('update-sv-reward-weight-weight');
    expect(weightInput).toBeDefined();
    await user.type(weightInput, '1000');

    await user.click(actionInput); // using this to trigger the onBlur event which triggers the validation

    await waitFor(async () => {
      expect(submitButton.getAttribute('disabled')).toBeNull();
    });

    await user.click(submitButton); //review proposal
    await user.click(submitButton); //submit proposal

    expect(screen.getByTestId('proposal-submission-error')).toBeDefined();
    expect(screen.getByText(/Submission failed/)).toBeDefined();
    expect(screen.getByText(/Service Unavailable/)).toBeDefined();
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
        <UpdateSvRewardWeightForm />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('update-sv-reward-weight-action');
    const submitButton = screen.getByTestId('submit-button');

    const summaryInput = screen.getByTestId('update-sv-reward-weight-summary');
    expect(summaryInput).toBeDefined();
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('update-sv-reward-weight-url');
    expect(urlInput).toBeDefined();
    await user.type(urlInput, 'https://example.com');

    const memberDropdown = screen.getByTestId('update-sv-reward-weight-member-dropdown');
    expect(memberDropdown).toBeDefined();

    const selectInput = screen.getByRole('combobox');
    fireEvent.mouseDown(selectInput);

    await waitFor(async () => {
      const memberToSelect = screen.getByText('Digital-Asset-Eng-2');
      expect(memberToSelect).toBeDefined();
      await user.click(memberToSelect);
    });

    const weightInput = screen.getByTestId('update-sv-reward-weight-weight');
    expect(weightInput).toBeDefined();
    await user.type(weightInput, '1000');

    await user.click(actionInput); // using this to trigger the onBlur event which triggers the validation

    await waitFor(async () => {
      expect(submitButton.getAttribute('disabled')).toBeNull();
    });
    await user.click(submitButton); //review proposal
    await user.click(submitButton); //submit proposal

    waitFor(() => {
      expect(screen.getByText('Action Required')).toBeDefined();
      expect(screen.getByText('Inflight Votes')).toBeDefined();
      expect(screen.getByText('Vote History')).toBeDefined();
      expect(screen.getByText('Successfully submitted the proposal')).toBeDefined();
    });
  });
});
