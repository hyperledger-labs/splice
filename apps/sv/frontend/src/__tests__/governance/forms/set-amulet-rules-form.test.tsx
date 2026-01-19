// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import userEvent from '@testing-library/user-event';
import { rest } from 'msw';
import { SvConfigProvider } from '../../../utils';
import App from '../../../App';
import { svPartyId } from '../../mocks/constants';
import { Wrapper } from '../../helpers';
import { SetAmuletConfigRulesForm } from '../../../components/forms/SetAmuletConfigRulesForm';
import dayjs from 'dayjs';
import { dateTimeFormatISO } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { server, svUrl } from '../../setup/setup';
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
    user.click(button);

    expect(await screen.findAllByDisplayValue(svPartyId)).not.toBe([]);
  });
});

describe('Set Amulet Config Rules Form', () => {
  test('should render all Set Amulet Config Rules Form components', () => {
    render(
      <Wrapper>
        <SetAmuletConfigRulesForm />
      </Wrapper>
    );

    expect(screen.getByTestId('set-amulet-config-rules-form')).toBeInTheDocument();
    expect(screen.getByText('Action')).toBeInTheDocument();

    const actionInput = screen.getByTestId('set-amulet-config-rules-action');
    expect(actionInput).toBeInTheDocument();
    expect(actionInput.getAttribute('value')).toBe('Set Amulet Rules Configuration');

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
    waitFor(
      () => {
        const configLabels = screen.getAllByTestId('config-label', { exact: false });
        expect(configLabels.length).toBeGreaterThan(65);

        const configFields = screen.getAllByTestId('config-field', { exact: false });
        expect(configFields.length).toBeGreaterThan(65);

        // no changes have been made so we should not see any current values
        expect(() => screen.getAllByTestId('config-current-value', { exact: false })).toThrowError(
          /Unable to find an element/
        );
      },
      { timeout: 1000 }
    );

    expect(screen.getByTestId('json-diffs-details')).toBeInTheDocument();
  });

  test(
    'should render errors when submit button is clicked on new form',
    async () => {
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
      expect(submitButton.getAttribute('disabled')).toBeDefined();
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

      expect(submitButton.getAttribute('disabled')).toBeNull();
    },
    { timeout: 10000 }
  );

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

    expect(screen.getByText(PROPOSAL_SUMMARY_TITLE)).toBeInTheDocument();
  });

  test('should show error on form if submission fails', { timeout: 10000 }, async () => {
    server.use(
      rest.post(`${svUrl}/v0/admin/sv/voterequest/create`, (_, res, ctx) => {
        return res(ctx.status(503), ctx.json({ error: 'Service Unavailable' }));
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
      rest.post(`${svUrl}/v0/admin/sv/voterequest/create`, (_, res, ctx) => {
        return res(ctx.json({}));
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

    const reviewButton = screen.getByTestId('submit-button');
    await waitFor(async () => {
      expect(reviewButton.getAttribute('disabled')).toBeNull();
    });
    await user.click(reviewButton);

    await screen.findByText('Proposal Summary');
    const submitButton = screen.getByTestId('submit-button');
    await user.click(submitButton);

    // Verify the success toast appears
    // Note: Governance page content (Action Required, Inflight Votes, etc.) can't be tested
    // without full routing and the Wrapper doesn't include routes for the governance page
    await waitFor(() => {
      expect(screen.queryByText('Successfully submitted the proposal')).toBeInTheDocument();
    });
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

    const jsonDiffs = screen.getByText('JSON Diffs');
    expect(jsonDiffs).toBeInTheDocument();

    await user.click(jsonDiffs);
    expect(screen.queryByTestId('config-diffs-display')).toBeInTheDocument();

    const reviewButton = screen.getByTestId('submit-button');
    await waitFor(async () => {
      expect(reviewButton.getAttribute('disabled')).toBeNull();
    });

    expect(jsonDiffs).toBeInTheDocument();
    await user.click(jsonDiffs);
    expect(screen.queryByTestId('config-diffs-display')).toBeInTheDocument();
  });
});
