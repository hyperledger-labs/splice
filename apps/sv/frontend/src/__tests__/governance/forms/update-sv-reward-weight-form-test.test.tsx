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
import { dateTimeFormatISO } from '@canton-network/splice-common-frontend-utils';
import dayjs from 'dayjs';
import { server, svUrl } from '../../setup/setup';
import { http, HttpResponse } from 'msw';
import {
  CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE,
  PROPOSAL_SUMMARY_SUBTITLE,
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

describe('Update Super Validator Reward Weight Form', () => {
  test('should render all Update Super Validator Reward Weight Form components', () => {
    render(
      <Wrapper>
        <UpdateSvRewardWeightForm />
      </Wrapper>
    );

    expect(screen.getByTestId('update-sv-reward-weight-form')).toBeInTheDocument();
    expect(screen.getByText(CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE)).toBeInTheDocument();

    const actionInput = screen.getByTestId('update-sv-reward-weight-action');
    expect(actionInput).toBeInTheDocument();
    expect(actionInput.textContent).toBe('Update Super Validator Reward Weight');

    const summaryInput = screen.getByTestId('update-sv-reward-weight-summary');
    expect(summaryInput).toBeInTheDocument();
    expect(summaryInput.getAttribute('value')).toBeNull();

    const summarySubtitle = screen.getByTestId('update-sv-reward-weight-summary-subtitle');
    expect(summarySubtitle).toBeInTheDocument();
    expect(summarySubtitle.textContent).toBe(PROPOSAL_SUMMARY_SUBTITLE);

    const urlInput = screen.getByTestId('update-sv-reward-weight-url');
    expect(urlInput).toBeInTheDocument();
    expect(urlInput.getAttribute('value')).toBe('');

    const memberInput = screen.getByTestId('update-sv-reward-weight-member-dropdown');
    expect(memberInput).toBeInTheDocument();
    expect(memberInput.getAttribute('value')).toBe('');

    const weightInput = screen.getByTestId('update-sv-reward-weight-weight');
    expect(weightInput).toBeInTheDocument();
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
    expect(submitButton).toBeInTheDocument();

    await user.click(submitButton);
    expect(submitButton.getAttribute('disabled')).not.toBeNull();
    await expect(async () => await user.click(submitButton)).rejects.toThrowError(
      /Unable to perform pointer interaction/
    );

    screen.getByText('Summary is required');
    screen.getByText('Invalid URL');
    screen.getByText('Weight is required');
    screen.getByText('SV is required');

    // completing the form should reenable the submit button
    const summaryInput = screen.getByTestId('update-sv-reward-weight-summary');
    expect(summaryInput).toBeInTheDocument();
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('update-sv-reward-weight-url');
    expect(urlInput).toBeInTheDocument();
    await user.type(urlInput, 'https://example.com');

    const memberDropdown = screen.getByTestId('update-sv-reward-weight-member-dropdown');
    expect(memberDropdown).toBeInTheDocument();

    const selectInput = screen.getByRole('combobox');
    fireEvent.mouseDown(selectInput);

    const memberToSelect = await screen.findByText('Digital-Asset-Eng-2');
    await user.click(memberToSelect);

    const weightInput = screen.getByTestId('update-sv-reward-weight-weight');
    expect(weightInput).toBeInTheDocument();
    await user.type(weightInput, '0_1000');

    await user.click(actionInput); // using this to trigger the onBlur event which triggers the validation

    expect(submitButton.getAttribute('disabled')).toBeNull();
  });

  test('expiry date must be in the future', async () => {
    render(
      <Wrapper>
        <UpdateSvRewardWeightForm />
      </Wrapper>
    );

    const expiryDateInput = screen.getByTestId('update-sv-reward-weight-expiry-date-field');
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
        <UpdateSvRewardWeightForm />
      </Wrapper>
    );

    const expiryDateInput = screen.getByTestId('update-sv-reward-weight-expiry-date-field');
    const effectiveDateInput = screen.getByTestId('update-sv-reward-weight-effective-date-field');

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
        <UpdateSvRewardWeightForm />
      </Wrapper>
    );

    const memberDropdown = screen.getByTestId('update-sv-reward-weight-member-dropdown');
    expect(memberDropdown).toBeInTheDocument();

    const selectInput = screen.getByRole('combobox');
    fireEvent.mouseDown(selectInput);

    const svOptions = await screen.findAllByRole('option');
    expect(svOptions.length).toBeGreaterThan(1);
    expect(svOptions.map(option => option.textContent)).toEqual(
      expect.arrayContaining(['Digital-Asset-2', 'Digital-Asset-Eng-2'])
    );
  });

  test('Current weight of selected SV is shown', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <UpdateSvRewardWeightForm />
      </Wrapper>
    );

    const memberDropdown = screen.getByTestId('update-sv-reward-weight-member-dropdown');
    expect(memberDropdown).toBeInTheDocument();

    const selectInput = screen.getByRole('combobox');

    const validateCurrentWeightFor = async (sv: string, weight: string) => {
      fireEvent.mouseDown(selectInput);
      const memberToSelect = await screen.findByText(sv);
      await user.click(memberToSelect);
      expect(await screen.findByText(`Current Weight: ${weight}`)).toBeInTheDocument();
    };

    await validateCurrentWeightFor('Digital-Asset-2', '0_0010');
    await validateCurrentWeightFor('Digital-Asset-Eng-2', '1_2345');
  });

  test('Weight is reset when sv changes', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <UpdateSvRewardWeightForm />
      </Wrapper>
    );

    const weightInput = screen.getByTestId('update-sv-reward-weight-weight');
    expect(weightInput).toBeInTheDocument();
    expect(weightInput.getAttribute('value')).toBe('');

    // set the weight before changing sv
    await user.type(weightInput, '1_0999');
    expect(weightInput.getAttribute('value')).toBe('1_0999');

    const memberDropdown = screen.getByTestId('update-sv-reward-weight-member-dropdown');
    expect(memberDropdown).toBeInTheDocument();
    const selectInput = screen.getByRole('combobox');
    fireEvent.mouseDown(selectInput);

    const memberToSelect = await screen.findByText('Digital-Asset-Eng-2');
    await user.click(memberToSelect);

    expect(weightInput.getAttribute('value')).toBe('');
  });

  test('Weight must be in basis points notation', async () => {
    const user = userEvent.setup();
    render(
      <Wrapper>
        <UpdateSvRewardWeightForm />
      </Wrapper>
    );

    const errorMessage =
      'Weight must be expressed in basis points using fixed point notation, XX...X_XXXX';

    const weightInput = screen.getByTestId('update-sv-reward-weight-weight');
    expect(weightInput).toBeInTheDocument();
    await user.type(weightInput, '123abc');

    await waitFor(() => {
      expect(screen.getByText(errorMessage)).toBeInTheDocument();
    });

    await user.clear(weightInput);
    await user.type(weightInput, '1001');
    await user.click(screen.getByTestId('update-sv-reward-weight-action'));

    await waitFor(() => {
      expect(screen.getByText(errorMessage)).toBeInTheDocument();
    });

    await user.clear(weightInput);
    await user.type(weightInput, '0_1001');
    await user.click(screen.getByTestId('update-sv-reward-weight-action'));

    await waitFor(() => {
      expect(screen.queryByText(errorMessage)).toBeNull();
    });
  });

  test('should show error on form if submission fails', async () => {
    server.use(
      http.post(`${svUrl}/v0/admin/sv/voterequest/create`, () => {
        return HttpResponse.json({ error: 'Service Unavailable' }, { status: 503 });
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
    expect(summaryInput).toBeInTheDocument();
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('update-sv-reward-weight-url');
    expect(urlInput).toBeInTheDocument();
    await user.type(urlInput, 'https://example.com');

    const memberDropdown = screen.getByTestId('update-sv-reward-weight-member-dropdown');
    expect(memberDropdown).toBeInTheDocument();

    const selectInput = screen.getByRole('combobox');
    fireEvent.mouseDown(selectInput);

    const memberToSelect = await screen.findByText('Digital-Asset-Eng-2');
    await user.click(memberToSelect);

    const weightInput = screen.getByTestId('update-sv-reward-weight-weight');
    expect(weightInput).toBeInTheDocument();
    await user.type(weightInput, '0_1000');

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

  test('show the correct weights for selected sv in summary page', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <UpdateSvRewardWeightForm />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('update-sv-reward-weight-action');
    const submitButton = screen.getByTestId('submit-button');

    const summaryInput = screen.getByTestId('update-sv-reward-weight-summary');
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('update-sv-reward-weight-url');
    expect(urlInput).toBeInTheDocument();
    await user.type(urlInput, 'https://example.com');

    const memberDropdown = screen.getByTestId('update-sv-reward-weight-member-dropdown');
    expect(memberDropdown).toBeInTheDocument();

    const selectInput = screen.getByRole('combobox');
    fireEvent.mouseDown(selectInput);

    const memberToSelect = await screen.findByText('Digital-Asset-Eng-2');
    await user.click(memberToSelect);

    const weightInput = screen.getByTestId('update-sv-reward-weight-weight');
    expect(weightInput.getAttribute('value')).toBe('');
    await user.type(weightInput, '0_1000');

    await user.click(actionInput); // using this to trigger the onBlur event which triggers the validation

    await waitFor(async () => {
      expect(submitButton.getAttribute('disabled')).toBeNull();
    });
    await user.click(submitButton); //review proposal

    await waitFor(() => {
      expect(screen.getByTestId('config-change-current-value').textContent).toBe('1_2345');
      expect(screen.getByTestId('config-change-new-value').textContent).toBe('0_1000');
    });
  });

  test('should redirect to governance page after successful submission', async () => {
    server.use(
      http.post(`${svUrl}/v0/admin/sv/voterequest/create`, () => {
        return HttpResponse.json({});
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
    expect(summaryInput).toBeInTheDocument();
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('update-sv-reward-weight-url');
    expect(urlInput).toBeInTheDocument();
    await user.type(urlInput, 'https://example.com');

    const memberDropdown = screen.getByTestId('update-sv-reward-weight-member-dropdown');
    expect(memberDropdown).toBeInTheDocument();

    const selectInput = screen.getByRole('combobox');
    fireEvent.mouseDown(selectInput);

    const memberToSelect = await screen.findByText('Digital-Asset-Eng-2');
    await user.click(memberToSelect);

    const weightInput = screen.getByTestId('update-sv-reward-weight-weight');
    expect(weightInput).toBeInTheDocument();
    await user.type(weightInput, '0_1000');

    await user.click(actionInput); // using this to trigger the onBlur event which triggers the validation

    await waitFor(async () => {
      expect(submitButton.getAttribute('disabled')).toBeNull();
    });
    await user.click(submitButton); //review proposal
    await user.click(submitButton); //submit proposal

    await screen.findByText('Successfully submitted the proposal');
  });

  test('should send reward weight to backend without underscore', async () => {
    let requestBody = '';
    server.use(
      http.post(`${svUrl}/v0/admin/sv/voterequest/create`, async ({ request }) => {
        requestBody = await request.text();
        return HttpResponse.json({});
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
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('update-sv-reward-weight-url');
    await user.type(urlInput, 'https://example.com');

    const selectInput = screen.getByRole('combobox');
    fireEvent.mouseDown(selectInput);

    const memberToSelect = await screen.findByText('Digital-Asset-Eng-2');
    await user.click(memberToSelect);

    const weightInput = screen.getByTestId('update-sv-reward-weight-weight');
    await user.type(weightInput, '0_1000');

    await user.click(actionInput); // triggers onBlur validation
    await waitFor(async () => {
      expect(submitButton).not.toBeDisabled();
    });

    await user.click(submitButton); // review proposal
    await user.click(submitButton); // submit proposal

    await waitFor(() => {
      expect(requestBody).toContain('"newRewardWeight":"1000"');
    });
  });
});
