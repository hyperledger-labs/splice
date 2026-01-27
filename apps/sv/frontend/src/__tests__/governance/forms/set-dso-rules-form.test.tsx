// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import {
  dateTimeFormatISO,
  nextScheduledSynchronizerUpgradeFormat,
} from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import dayjs from 'dayjs';
import { rest } from 'msw';
import { describe, expect, test } from 'vitest';
import App from '../../../App';
import { SetDsoConfigRulesForm } from '../../../components/forms/SetDsoConfigRulesForm';
import { SvConfigProvider } from '../../../utils';
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

describe('Set DSO Config Rules Form', () => {
  test('should render all Set DSO Config Rules Form components', () => {
    render(
      <Wrapper>
        <SetDsoConfigRulesForm />
      </Wrapper>
    );

    expect(screen.getByTestId('set-dso-config-rules-form')).toBeInTheDocument();
    expect(screen.getByText('Action')).toBeInTheDocument();

    const actionInput = screen.getByTestId('set-dso-config-rules-action');
    expect(actionInput).toBeInTheDocument();
    expect(actionInput.getAttribute('value')).toBe('Set Dso Rules Configuration');

    const summaryInput = screen.getByTestId('set-dso-config-rules-summary');
    expect(summaryInput).toBeInTheDocument();
    expect(summaryInput.getAttribute('value')).toBeNull();

    const urlInput = screen.getByTestId('set-dso-config-rules-url');
    expect(urlInput).toBeInTheDocument();
    expect(urlInput.getAttribute('value')).toBe('');

    const configLabels = screen.getAllByTestId(/config-label-/);
    expect(configLabels.length).toBeGreaterThan(15);

    const configFields = screen.getAllByTestId(/config-field-/);
    expect(configFields.length).toBeGreaterThan(15);

    expect(() => screen.getAllByTestId('config-current-value', { exact: false })).toThrowError(
      /Unable to find an element/
    );

    expect(screen.getByTestId('json-diffs-details')).toBeInTheDocument();
  });

  test('should render errors when submit button is clicked on new form', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <SetDsoConfigRulesForm />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('set-dso-config-rules-action');
    const submitButton = screen.getByTestId('submit-button');
    expect(submitButton).toBeInTheDocument();

    await user.click(submitButton);
    expect(submitButton.getAttribute('disabled')).toBeDefined();
    expect(async () => await user.click(submitButton)).rejects.toThrowError(
      /Unable to perform pointer interaction/
    );

    expect(screen.getByText('Summary is required')).toBeInTheDocument();
    expect(screen.getByText('Invalid URL')).toBeInTheDocument();

    // completing the form should reenable the submit button
    const summaryInput = screen.getByTestId('set-dso-config-rules-summary');
    expect(summaryInput).toBeInTheDocument();
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('set-dso-config-rules-url');
    expect(urlInput).toBeInTheDocument();
    await user.type(urlInput, 'https://example.com');

    await user.click(actionInput); // using this to trigger the onBlur event which triggers the validation

    expect(submitButton.getAttribute('disabled')).toBeNull();
  });

  test('expiry date must be in the future', async () => {
    render(
      <Wrapper>
        <SetDsoConfigRulesForm />
      </Wrapper>
    );

    const expiryDateInput = screen.getByTestId('set-dso-config-rules-expiry-date-field');
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
        <SetDsoConfigRulesForm />
      </Wrapper>
    );

    const expiryDateInput = screen.getByTestId('set-dso-config-rules-expiry-date-field');
    const effectiveDateInput = screen.getByTestId('set-dso-config-rules-effective-date-field');

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
        <SetDsoConfigRulesForm />
      </Wrapper>
    );

    expect(() => screen.getAllByTestId('config-current-value', { exact: false })).toThrowError(
      /Unable to find an element/
    );

    const c1Input = screen.getByTestId('config-field-numUnclaimedRewardsThreshold');
    expect(c1Input).toBeInTheDocument();
    await user.type(c1Input, '99');

    const c2Input = screen.getByTestId('config-field-voteCooldownTime');
    expect(c2Input).toBeInTheDocument();
    await user.type(c2Input, '9999');

    const changes = screen.getAllByTestId(/config-current-value-/);
    expect(changes.length).toBe(2);
  });

  test('should show proposal review page after form completion', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <SetDsoConfigRulesForm />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('set-dso-config-rules-action');

    const summaryInput = screen.getByTestId('set-dso-config-rules-summary');
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('set-dso-config-rules-url');
    await user.type(urlInput, 'https://example.com');

    const c1Input = screen.getByTestId('config-field-numUnclaimedRewardsThreshold');
    await user.type(c1Input, '99');

    const c2Input = screen.getByTestId('config-field-voteCooldownTime');
    await user.type(c2Input, '9999');

    expect(screen.getByText('Review Proposal')).toBeInTheDocument();
    await user.click(actionInput); // using this to trigger the onBlur event which triggers the validation

    const submitButton = screen.getByTestId('submit-button');
    await waitFor(async () => {
      expect(submitButton.getAttribute('disabled')).toBeNull();
    });

    await user.click(submitButton);

    expect(screen.getByText('Proposal Summary')).toBeInTheDocument();
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
        <SetDsoConfigRulesForm />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('set-dso-config-rules-action');

    const summaryInput = screen.getByTestId('set-dso-config-rules-summary');
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('set-dso-config-rules-url');
    await user.type(urlInput, 'https://example.com');

    const c1Input = screen.getByTestId('config-field-numUnclaimedRewardsThreshold');
    await user.type(c1Input, '99');

    const c2Input = screen.getByTestId('config-field-voteCooldownTime');
    await user.type(c2Input, '9999');

    await user.click(actionInput); // using this to trigger the onBlur event which triggers the validation

    const submitButton = screen.getByTestId('submit-button');

    await waitFor(async () => {
      expect(submitButton.getAttribute('disabled')).toBeNull();
    });

    await user.click(submitButton); // review proposal
    await user.click(submitButton); // submit proposal

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
        <SetDsoConfigRulesForm />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('set-dso-config-rules-action');

    const summaryInput = screen.getByTestId('set-dso-config-rules-summary');
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('set-dso-config-rules-url');
    await user.type(urlInput, 'https://example.com');

    const c1Input = screen.getByTestId('config-field-numUnclaimedRewardsThreshold');
    await user.type(c1Input, '99');

    const c2Input = screen.getByTestId('config-field-voteCooldownTime');
    await user.type(c2Input, '9999');

    await user.click(actionInput); // using this to trigger the onBlur event which triggers the validation

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
        <SetDsoConfigRulesForm />
      </Wrapper>
    );

    const summaryInput = screen.getByTestId('set-dso-config-rules-summary');
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('set-dso-config-rules-url');
    await user.type(urlInput, 'https://example.com');

    const c1Input = screen.getByTestId('config-field-numUnclaimedRewardsThreshold');
    await user.type(c1Input, '99');

    const c2Input = screen.getByTestId('config-field-voteCooldownTime');
    await user.type(c2Input, '9999');

    const jsonDiffs = screen.getByText('JSON Diffs');
    expect(jsonDiffs).toBeInTheDocument();

    await user.click(jsonDiffs);
    expect(await screen.findByTestId('config-diffs-display')).toBeInTheDocument();

    const reviewButton = screen.getByTestId('submit-button');
    await waitFor(async () => {
      expect(reviewButton.getAttribute('disabled')).toBeNull();
    });

    expect(jsonDiffs).toBeInTheDocument();
    await user.click(jsonDiffs);
    expect(screen.queryByTestId('config-diffs-display')).toBeInTheDocument();
  });
});

describe('Next Scheduled Synchronizer Upgrade', () => {
  test('render default time for next scheduled upgrade', async () => {
    render(
      <Wrapper>
        <SetDsoConfigRulesForm />
      </Wrapper>
    );

    const effectiveDateInput = screen.getByTestId('set-dso-config-rules-effective-date-field');
    expect(effectiveDateInput).toBeInTheDocument();

    const tenDaysFromNow = dayjs().add(10, 'day').format(dateTimeFormatISO);

    fireEvent.change(effectiveDateInput, { target: { value: tenDaysFromNow } });

    const defaultTimeDisplay = screen.getByTestId('next-scheduled-upgrade-time-default');
    expect(defaultTimeDisplay).toBeInTheDocument();

    // The default should be effective date + 1 hour in UTC format
    await waitFor(() => {
      const expectedDefaultTime = dayjs(tenDaysFromNow)
        .utc()
        .add(1, 'hour')
        .format(nextScheduledSynchronizerUpgradeFormat);

      expect(defaultTimeDisplay.textContent).toContain(`Default: ${expectedDefaultTime}`);
    });
  });

  test('no validation for time or migration id if neither is provided', async () => {
    render(
      <Wrapper>
        <SetDsoConfigRulesForm />
      </Wrapper>
    );

    expect(
      screen.queryByText(
        'Upgrade Time and Migration ID are required for a Scheduled Synchronizer Upgrade'
      )
    ).toBeNull();
  });

  test('show error if only one of time or migrationId is provided', async () => {
    const user = userEvent.setup();
    render(
      <Wrapper>
        <SetDsoConfigRulesForm />
      </Wrapper>
    );
    const errorMessage =
      'Upgrade Time and Migration ID are required for a Scheduled Synchronizer Upgrade';

    expect(screen.queryByText(errorMessage)).toBeNull();

    // migrationId field only - should show error
    const migrationIdInput = screen.getByTestId(
      'config-field-nextScheduledSynchronizerUpgradeMigrationId'
    );
    expect(migrationIdInput).toBeInTheDocument();

    await user.type(migrationIdInput, '12345');

    await waitFor(() => {
      expect(screen.queryByText(errorMessage)).toBeInTheDocument();
    });

    // Error should be gone when migration ID is cleared
    await user.clear(migrationIdInput);
    await user.click(screen.getByTestId('set-dso-config-rules-action'));

    await waitFor(() => {
      expect(screen.queryByText(errorMessage)).toBeNull();
    });

    const timeInput = screen.getByTestId('config-field-nextScheduledSynchronizerUpgradeTime');
    expect(timeInput).toBeInTheDocument();

    const futureTime = dayjs().add(2, 'hour').format(dateTimeFormatISO);
    await user.type(timeInput, futureTime);

    await waitFor(() => {
      expect(screen.queryByText(errorMessage)).toBeInTheDocument();
    });

    // Error should be gone when upgrade time is cleared
    await user.clear(timeInput);

    await waitFor(() => {
      expect(screen.queryByText(errorMessage)).toBeNull();
    });
  });

  test('show error on form if supplied time is not after effective date', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <SetDsoConfigRulesForm />
      </Wrapper>
    );

    const errorMessage = 'Upgrade Time must be at least 1 hour after the Effective Date';

    const effectiveDateInput = screen.getByTestId('set-dso-config-rules-effective-date-field');
    const effectiveDate = dayjs().add(10, 'day').format(dateTimeFormatISO);

    fireEvent.change(effectiveDateInput, { target: { value: effectiveDate } });

    expect(screen.queryByText(errorMessage)).toBeNull();

    const timeInput = screen.getByTestId('config-field-nextScheduledSynchronizerUpgradeTime');
    const migrationIdInput = screen.getByTestId(
      'config-field-nextScheduledSynchronizerUpgradeMigrationId'
    );

    // Set time to be only 30 minutes after effective date (should fail validation)
    const invalidTime = dayjs(effectiveDate)
      .utc()
      .add(30, 'minute')
      .format(nextScheduledSynchronizerUpgradeFormat);
    await user.type(timeInput, invalidTime);
    await user.type(migrationIdInput, '12345');

    await waitFor(() => {
      expect(screen.queryByText(errorMessage)).toBeInTheDocument();
    });

    // Set upgrade time to be more than 1 hour after effective date - error should disappear
    const validTime = dayjs(effectiveDate)
      .utc()
      .add(2, 'hours')
      .format(nextScheduledSynchronizerUpgradeFormat);
    await user.clear(timeInput);
    await user.type(timeInput, validTime);

    await waitFor(() => {
      expect(screen.queryByText(errorMessage)).toBeNull();
    });
  });
});
