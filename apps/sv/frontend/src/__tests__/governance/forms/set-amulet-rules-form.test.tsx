// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { SvConfigProvider } from '../../../utils';
import App from '../../../App';
import { svPartyId } from '../../mocks/constants';
import { Wrapper } from '../../helpers';
import { SetAmuletConfigRulesForm } from '../../../components/forms/SetAmuletConfigRulesForm';
import dayjs from 'dayjs';
import { dateTimeFormatISO } from '@canton-network/splice-common-frontend-utils';
import { server, svUrl } from '../../setup/setup';
import {
  CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE,
  PROPOSAL_REVIEW_TITLE,
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
    user.click(button);

    expect(await screen.findAllByDisplayValue(svPartyId)).not.toHaveLength(0);
  });
});

describe('Set Amulet Config Rules Form', () => {
  test('should render all Set Amulet Config Rules Form components', async () => {
    render(
      <Wrapper>
        <SetAmuletConfigRulesForm />
      </Wrapper>
    );

    expect(screen.getByTestId('set-amulet-config-rules-form')).toBeInTheDocument();
    expect(screen.getByText(CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE)).toBeInTheDocument();

    const actionInput = screen.getByTestId('set-amulet-config-rules-action');
    expect(actionInput).toBeInTheDocument();
    expect(actionInput.textContent).toBe('Set Amulet Rules Configuration');

    const summaryInput = screen.getByTestId('set-amulet-config-rules-summary');
    expect(summaryInput).toBeInTheDocument();
    expect(summaryInput.getAttribute('value')).toBeNull();

    const summarySubtitle = screen.getByTestId('set-amulet-config-rules-summary-subtitle');
    expect(summarySubtitle).toBeInTheDocument();
    expect(summarySubtitle.textContent).toBe(PROPOSAL_SUMMARY_SUBTITLE);

    const urlInput = screen.getByTestId('set-amulet-config-rules-url');
    expect(urlInput).toBeInTheDocument();
    expect(urlInput.getAttribute('value')).toBe('');

    // Amulet Rules has a lot of fields to process so this can get flakey if not given enough time
    await waitFor(() => {
      const configLabels = screen.getAllByTestId('config-label', { exact: false });
      expect(configLabels.length).toBeGreaterThan(65);

      const configFields = screen.getAllByTestId('config-field', { exact: false });
      expect(configFields.length).toBeGreaterThan(65);

      // no changes have been made so we should not see any current values
      expect(() => screen.getAllByTestId('config-current-value', { exact: false })).toThrowError(
        /Unable to find an element/
      );
    });

    const jsonDiffsToggle = screen.getByTestId('json-diff-toggle');
    expect(screen.getByText('JSON')).toBeInTheDocument();
    expect(jsonDiffsToggle).toHaveTextContent('Show JSON');
    expect(jsonDiffsToggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.getByTestId('json-diffs-details')).not.toBeVisible();
  });

  test('should render errors when submit button is clicked on new form', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <SetAmuletConfigRulesForm />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('set-amulet-config-rules-action');
    const submitButton = screen.getByTestId('submit-button');
    expect(submitButton).toBeInTheDocument();

    await user.click(submitButton);
    expect(submitButton.getAttribute('disabled')).not.toBeNull();
    await expect(async () => await user.click(submitButton)).rejects.toThrowError(
      /Unable to perform pointer interaction/
    );

    expect(screen.getByText('Summary is required')).toBeInTheDocument();
    expect(screen.getByText('Invalid URL')).toBeInTheDocument();

    // completing the form should reenable the submit button
    const summaryInput = screen.getByTestId('set-amulet-config-rules-summary');
    expect(summaryInput).toBeInTheDocument();
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('set-amulet-config-rules-url');
    expect(urlInput).toBeInTheDocument();
    await user.type(urlInput, 'https://example.com');

    await user.click(actionInput); // using this to trigger the onBlur event which triggers the validation

    await waitFor(() => expect(submitButton.getAttribute('disabled')).toBeNull());
  });

  test('expiry date must be in the future', async () => {
    render(
      <Wrapper>
        <SetAmuletConfigRulesForm />
      </Wrapper>
    );

    const expiryDateInput = screen.getByTestId('set-amulet-config-rules-expiry-date-field');
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
        <SetAmuletConfigRulesForm />
      </Wrapper>
    );

    const expiryDateInput = screen.getByTestId('set-amulet-config-rules-expiry-date-field');
    const effectiveDateInput = screen.getByTestId('set-amulet-config-rules-effective-date-field');

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

  test('should show error when submitting without configuration changes', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <SetAmuletConfigRulesForm />
      </Wrapper>
    );

    const summaryInput = screen.getByTestId('set-amulet-config-rules-summary');
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('set-amulet-config-rules-url');
    await user.type(urlInput, 'https://example.com');

    const actionInput = screen.getByTestId('set-amulet-config-rules-action');
    await user.click(actionInput);

    const submitButton = screen.getByTestId('submit-button');
    await waitFor(() => {
      expect(submitButton.getAttribute('disabled')).toBeNull();
    });

    await user.click(submitButton);

    await waitFor(() => {
      expect(
        screen.getByText('Cannot submit a proposal with no configuration changes')
      ).toBeInTheDocument();
    });
  });

  test('changing config fields should render the current value', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <SetAmuletConfigRulesForm />
      </Wrapper>
    );

    expect(() => screen.getAllByTestId('config-current-value', { exact: false })).toThrowError(
      /Unable to find an element/
    );

    const c1Input = screen.getByTestId('config-field-transferPreapprovalFee');
    expect(c1Input).toBeInTheDocument();
    await user.type(c1Input, '99');

    const c2Input = screen.getByTestId('config-field-transferConfigTransferFeeInitialRate');
    expect(c2Input).toBeInTheDocument();
    await user.type(c2Input, '9.99');

    const changes = screen.getAllByTestId('config-current-value', { exact: false });
    expect(changes.length).toBe(2);
  });

  test('reward config minting scheme renders as a dropdown', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <SetAmuletConfigRulesForm />
      </Wrapper>
    );

    // Minting scheme should render as a Select, not a TextField
    const mintingField = screen.getByTestId('config-field-rewardConfigMintingVersion');
    expect(mintingField).toBeInTheDocument();
    const selectInput = mintingField.querySelector('[role="combobox"]') as HTMLElement;
    expect(selectInput).toBeInTheDocument();

    // Open dropdown and verify options
    await user.click(selectInput);
    expect(screen.getByText('Featured App Markers (pre CIP-104)')).toBeInTheDocument();
    expect(screen.getByText('Traffic-Based App Rewards (CIP-104)')).toBeInTheDocument();

    // Select an option
    await user.click(screen.getByText('Traffic-Based App Rewards (CIP-104)'));

    // Verify current value is shown after change
    const currentValue = screen.getByTestId('config-current-value-rewardConfigMintingVersion');
    expect(currentValue).toBeInTheDocument();
  });

  test('reward config dry-run scheme includes None option', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <SetAmuletConfigRulesForm />
      </Wrapper>
    );

    const dryRunField = screen.getByTestId('config-field-rewardConfigDryRunVersion');
    expect(dryRunField).toBeInTheDocument();
    const selectInput = dryRunField.querySelector('[role="combobox"]') as HTMLElement;
    expect(selectInput).toBeInTheDocument();

    // Open dropdown and verify None option exists
    await user.click(selectInput);
    expect(screen.getByText('None (disabled)')).toBeInTheDocument();
  });

  test('should show proposal review page after form completion', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <SetAmuletConfigRulesForm />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('set-amulet-config-rules-action');

    const summaryInput = screen.getByTestId('set-amulet-config-rules-summary');
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('set-amulet-config-rules-url');
    await user.type(urlInput, 'https://example.com');

    const c1Input = screen.getByTestId('config-field-transferPreapprovalFee');
    await user.type(c1Input, '99');

    const c2Input = screen.getByTestId('config-field-transferConfigTransferFeeInitialRate');
    await user.type(c2Input, '9.99');

    expect(screen.getByText('Review Proposal')).toBeInTheDocument();
    await user.click(actionInput); // using this to trigger the onBlur event which triggers the validation

    const submitButton = screen.getByTestId('submit-button');
    await waitFor(async () => {
      expect(submitButton.getAttribute('disabled')).toBeNull();
    });

    await user.click(submitButton);

    expect(screen.getByText(PROPOSAL_REVIEW_TITLE)).toBeInTheDocument();
    expect(screen.queryByText('JSON')).not.toBeInTheDocument();
    expect(screen.getByTestId('json-diff-toggle')).toHaveTextContent('Show JSON');
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
        <SetAmuletConfigRulesForm />
      </Wrapper>
    );

    const summaryInput = screen.getByTestId('set-amulet-config-rules-summary');
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('set-amulet-config-rules-url');
    await user.type(urlInput, 'https://example.com');

    const c1Input = screen.getByTestId('config-field-transferPreapprovalFee');
    await user.type(c1Input, '99');

    const c2Input = screen.getByTestId('config-field-transferConfigTransferFeeInitialRate');
    await user.type(c2Input, '9.99');

    const submitButton = screen.getByTestId('submit-button');
    await waitFor(async () => {
      expect(submitButton.getAttribute('disabled')).toBeNull();
    });

    await user.click(submitButton); // Review proposal
    await user.click(submitButton); // Submit proposal

    expect(screen.getByTestId('proposal-submission-error')).toBeInTheDocument();
    expect(screen.getByText(/Submission failed/)).toBeInTheDocument();
    expect(screen.getByText(/Service Unavailable/)).toBeInTheDocument();
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
        <SetAmuletConfigRulesForm />
      </Wrapper>
    );

    const summaryInput = screen.getByTestId('set-amulet-config-rules-summary');
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('set-amulet-config-rules-url');
    await user.type(urlInput, 'https://example.com');

    const c1Input = screen.getByTestId('config-field-transferPreapprovalFee');
    await user.type(c1Input, '99');

    const c2Input = screen.getByTestId('config-field-transferConfigTransferFeeInitialRate');
    await user.type(c2Input, '9.99');

    const submitButton = screen.getByTestId('submit-button');
    await waitFor(async () => {
      expect(submitButton.getAttribute('disabled')).toBeNull();
    });

    await user.click(submitButton); //review proposal
    await user.click(submitButton); //submit proposal

    await screen.findByText('Successfully submitted the proposal');
  });

  test('should render diffs if changes to config values were made', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <SetAmuletConfigRulesForm />
      </Wrapper>
    );

    const summaryInput = screen.getByTestId('set-amulet-config-rules-summary');
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('set-amulet-config-rules-url');
    await user.type(urlInput, 'https://example.com');

    const c1Input = screen.getByTestId('config-field-transferPreapprovalFee');
    await user.type(c1Input, '99');

    const c2Input = screen.getByTestId('config-field-transferConfigTransferFeeInitialRate');
    await user.type(c2Input, '9.99');

    const jsonDiffsToggle = screen.getByTestId('json-diff-toggle');
    expect(jsonDiffsToggle).toHaveTextContent('Show JSON');
    expect(jsonDiffsToggle).toHaveAttribute('aria-expanded', 'false');

    await user.click(jsonDiffsToggle);
    expect(await screen.findByTestId('config-diffs-display')).toBeVisible();
    expect(jsonDiffsToggle).toHaveTextContent('Hide JSON');
    expect(jsonDiffsToggle).toHaveAttribute('aria-expanded', 'true');

    const reviewButton = screen.getByTestId('submit-button');
    await waitFor(async () => {
      expect(reviewButton.getAttribute('disabled')).toBeNull();
    });

    expect(jsonDiffsToggle).toBeInTheDocument();
    await user.click(jsonDiffsToggle);
    expect(await screen.findByTestId('config-diffs-display')).toBeInTheDocument();
  });

  test('should have decentralized synchronizer fields disabled', async () => {
    render(
      <Wrapper>
        <SetAmuletConfigRulesForm />
      </Wrapper>
    );

    await waitFor(() => {
      const activeSyncInput = screen.getByTestId(
        'config-field-decentralizedSynchronizerActiveSynchronizer'
      );
      expect(activeSyncInput).toBeDisabled();

      const requiredSyncInput = screen.getByTestId(
        'config-field-decentralizedSynchronizerRequiredSynchronizers1'
      );
      expect(requiredSyncInput).toBeDisabled();
    });
  });
});
