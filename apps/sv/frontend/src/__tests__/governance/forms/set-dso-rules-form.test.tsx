// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import {
  dateTimeFormatISO,
  nextScheduledSynchronizerUpgradeFormat,
} from '@canton-network/splice-common-frontend-utils';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import dayjs from 'dayjs';
import { http, HttpResponse } from 'msw';
import { describe, expect, test } from 'vitest';
import { dsoInfo } from '@canton-network/splice-common-test-handlers';
import App from '../../../App';
import { SetDsoConfigRulesForm } from '../../../components/forms/SetDsoConfigRulesForm';
import {
  CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE,
  DATE_TIME_PLACEHOLDER,
  REASON_PLACEHOLDER,
  URL_PLACEHOLDER,
} from '../../../utils/constants';
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

    expect(await screen.findAllByDisplayValue(svPartyId)).not.toHaveLength(0);
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
    expect(screen.getByText(CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE)).toBeInTheDocument();

    const actionInput = screen.getByTestId('set-dso-config-rules-action');
    expect(actionInput).toBeInTheDocument();
    expect(actionInput.textContent).toBe(
      'Set Decentralized Synchronizer Operations (DSO) Rules Configuration'
    );

    const summaryInput = screen.getByTestId('set-dso-config-rules-summary');
    expect(summaryInput).toBeInTheDocument();
    expect(summaryInput.getAttribute('value')).not.toBeInTheDocument();
    expect(summaryInput).toHaveAttribute('placeholder', REASON_PLACEHOLDER);

    const urlInput = screen.getByTestId('set-dso-config-rules-url');
    expect(urlInput).toBeInTheDocument();
    expect(urlInput.getAttribute('value')).toBe('');
    expect(urlInput).toHaveAttribute('placeholder', URL_PLACEHOLDER);

    expect(screen.getByTestId('set-dso-config-rules-expiry-date-field')).toHaveAttribute(
      'placeholder',
      DATE_TIME_PLACEHOLDER
    );
    expect(screen.getByTestId('set-dso-config-rules-effective-date-field')).toHaveAttribute(
      'placeholder',
      DATE_TIME_PLACEHOLDER
    );

    const configLabels = screen.getAllByTestId(/config-label-/);
    expect(configLabels.length).toBeGreaterThan(15);

    const configFields = screen.getAllByTestId(/config-field-/);
    expect(configFields.length).toBeGreaterThan(15);

    expect(() => screen.getAllByTestId('config-current-value', { exact: false })).toThrowError(
      /Unable to find an element/
    );

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
        <SetDsoConfigRulesForm />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('set-dso-config-rules-action');
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
    const summaryInput = screen.getByTestId('set-dso-config-rules-summary');
    expect(summaryInput).toBeInTheDocument();
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('set-dso-config-rules-url');
    expect(urlInput).toBeInTheDocument();
    await user.type(urlInput, 'https://example.com');

    await user.click(actionInput); // using this to trigger the onBlur event which triggers the validation

    await waitFor(() => expect(submitButton.getAttribute('disabled')).toBeNull());
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

  test('should show error when submitting without configuration changes', async () => {
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

    const actionInput = screen.getByTestId('set-dso-config-rules-action');
    await user.click(actionInput);

    const submitButton = screen.getByTestId('submit-button');
    await waitFor(() => {
      expect(submitButton.getAttribute('disabled')).not.toBeInTheDocument();
    });

    await user.click(submitButton);

    await waitFor(() => {
      expect(
        screen.getByText('Cannot submit a proposal with no configuration changes')
      ).toBeInTheDocument();
    });
  });

  test('shows an error for duplicate switch-over keys', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <SetDsoConfigRulesForm />
      </Wrapper>
    );

    const addButton = screen.getByTestId('switchover-add');
    await user.click(addButton);
    await user.click(addButton);

    await user.type(screen.getByTestId('switchover-key-0'), 'amulet-v2');
    await user.type(screen.getByTestId('switchover-key-1'), 'amulet-v2');

    await waitFor(() => {
      expect(screen.getByTestId('switchover-error')).toHaveTextContent(
        'Switch-over keys must be unique'
      );
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
      expect(submitButton.getAttribute('disabled')).not.toBeInTheDocument();
    });

    await user.click(submitButton);

    expect(screen.getByText('Proposal Review')).toBeInTheDocument();
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
      expect(submitButton.getAttribute('disabled')).not.toBeInTheDocument();
    });

    await user.click(submitButton); // review proposal
    await user.click(submitButton); // submit proposal

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
      expect(submitButton.getAttribute('disabled')).not.toBeInTheDocument();
    });

    await user.click(submitButton); //review proposal
    await user.click(submitButton); //submit proposal

    await screen.findByText('Successfully submitted the proposal');
  });

  // REPRO (currently FAILING): submit-gate bypass for invalid field values.
  //
  // The submit button is gated on `canSubmit`, and `handleSubmit` only runs
  // `validateAllFields('submit')` over *mounted* fields. Config value fields have no
  // validators at all, so an obviously-invalid config value (here, a non-numeric string
  // in a numeric field) is accepted with no error and the form advances to the
  // review/confirmation ("lock") step.
  //
  // Desired behaviour: the invalid value is rejected and the form stays on the edit
  // step. When the gate is fixed to validate the full form value, this test should pass.
  // test.fails: locks a known bug (currently failing on purpose). Remove `.fails` once fixed.
  test.fails(
    'does not advance an invalid config value to the review step (submit-gate bypass)',
    async () => {
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

      // Numeric config field set to a non-numeric (invalid) value. Nothing validates it.
      const numericConfig = await screen.findByTestId('config-field-numUnclaimedRewardsThreshold');
      await user.clear(numericConfig);
      await user.type(numericConfig, 'not-a-number');

      const actionInput = screen.getByTestId('set-dso-config-rules-action');
      await user.click(actionInput); // trigger blur/validation

      const submitButton = screen.getByTestId('submit-button');
      await waitFor(() => {
        expect(submitButton.getAttribute('disabled')).not.toBeInTheDocument();
      });

      await user.click(submitButton); // "Review Proposal"

      // Desired: the invalid value is rejected and the form does not advance to the
      // review ("lock") step. Currently it advances with no error, so this fails.
      expect(screen.queryByText('Proposal Review')).not.toBeInTheDocument();
    }
  );

  // REPRO (currently FAILING): stuck-disabled submit after fixing an input (onBlur slot).
  //
  // TanStack keeps field errors in per-cause slots (onChange/onBlur/onSubmit) and clears
  // only the slot whose cause+field just re-validated. A blur writes the error into the
  // `onBlur` slot; fixing by typing re-runs only the `onChange` validator, which clears
  // the `onChange` slot but leaves the stale `onBlur` error -> the field stays invalid ->
  // `canSubmit` stays false -> the submit button never re-enables.
  //
  // Desired behaviour: once the value is valid, the button re-enables.
  // test.fails: locks a known bug (currently failing on purpose). Remove `.fails` once fixed.
  test.fails(
    're-enables submit after an invalid value is fixed without re-blurring (onBlur-slot staleness)',
    async () => {
      const user = userEvent.setup();

      render(
        <Wrapper>
          <SetDsoConfigRulesForm />
        </Wrapper>
      );

      const summaryInput = screen.getByTestId('set-dso-config-rules-summary');
      await user.type(summaryInput, 'Summary of the proposal');

      const urlInput = screen.getByTestId('set-dso-config-rules-url');
      await user.type(urlInput, 'not-a-url'); // invalid -> onChange slot error
      await user.click(summaryInput); // blur the url field -> onBlur slot error

      const submitButton = screen.getByTestId('submit-button');
      await waitFor(() => expect(submitButton).toBeDisabled());

      // Fix by typing a valid URL, without re-blurring.
      await user.clear(urlInput);
      await user.type(urlInput, 'https://valid.com');

      // Desired: the field is valid now, so submit re-enables. Currently the stale onBlur
      // slot keeps it disabled, so this fails.
      await waitFor(() => expect(submitButton).not.toBeDisabled());
    }
  );

  // A1 fix: switch-over validation gates via a self-clearing onChange (re-run on nested
  // edits via per-row listeners), so an invalid row disables submit and fixing it
  // re-enables submit — no permanent stuck.
  test('re-enables submit after fixing an invalid switch-over row', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <SetDsoConfigRulesForm />
      </Wrapper>
    );

    const summaryInput = screen.getByTestId('set-dso-config-rules-summary');
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('set-dso-config-rules-url');
    await user.type(urlInput, 'https://valid.com');

    // A freshly-added switch-over row has an empty key, which is invalid -> submit disables.
    await user.click(screen.getByTestId('switchover-add'));
    await waitFor(() =>
      expect(screen.getByTestId('switchover-error')).toHaveTextContent(
        'Switch-over key is required'
      )
    );
    const submitButton = screen.getByTestId('submit-button');
    await waitFor(() => expect(submitButton).toBeDisabled());

    // Fixing the row (a nested child edit) re-validates the array field and re-enables submit.
    await user.type(screen.getByTestId('switchover-key-0'), 'amulet-v2');
    await waitFor(() => expect(screen.queryByTestId('switchover-error')).not.toBeInTheDocument());
    await waitFor(() => expect(submitButton).not.toBeDisabled());
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

    const jsonDiffsToggle = screen.getByTestId('json-diff-toggle');
    expect(jsonDiffsToggle).toHaveTextContent('Show JSON');
    expect(jsonDiffsToggle).toHaveAttribute('aria-expanded', 'false');

    await user.click(jsonDiffsToggle);
    expect(await screen.findByTestId('config-diffs-display')).toBeVisible();
    expect(jsonDiffsToggle).toHaveTextContent('Hide JSON');
    expect(jsonDiffsToggle).toHaveAttribute('aria-expanded', 'true');

    const reviewButton = screen.getByTestId('submit-button');
    await waitFor(async () => {
      expect(reviewButton.getAttribute('disabled')).not.toBeInTheDocument();
    });

    expect(jsonDiffsToggle).toBeInTheDocument();
    await user.click(jsonDiffsToggle);
    expect(await screen.findByTestId('config-diffs-display')).toBeInTheDocument();
  });

  test('should have decentralized synchronizer fields disabled', async () => {
    render(
      <Wrapper>
        <SetDsoConfigRulesForm />
      </Wrapper>
    );

    await waitFor(() => {
      const lastSyncIdInput = screen.getByTestId(
        'config-field-decentralizedSynchronizerLastSynchronizerId'
      );
      expect(lastSyncIdInput).toBeDisabled();

      const activeSyncIdInput = screen.getByTestId(
        'config-field-decentralizedSynchronizerActiveSynchronizerId'
      );
      expect(activeSyncIdInput).toBeDisabled();

      const syncInput = screen.getByTestId('config-field-decentralizedSynchronizer1');
      expect(syncInput).toBeDisabled();

      const syncStateInput = screen.getByTestId('config-field-decentralizedSynchronizerState1');
      expect(syncStateInput).toBeDisabled();

      const syncGenesisInput = screen.getByTestId(
        'config-field-decentralizedSynchronizerCometBftGenesisJson1'
      );
      expect(syncGenesisInput).toBeDisabled();
    });
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

    const defaultTimeDisplay = screen.getByTestId('nextScheduledSynchronizerUpgradeTime-default');
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
    ).not.toBeInTheDocument();
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

    expect(screen.queryByText(errorMessage)).not.toBeInTheDocument();

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
      expect(screen.queryByText(errorMessage)).not.toBeInTheDocument();
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
      expect(screen.queryByText(errorMessage)).not.toBeInTheDocument();
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

    expect(screen.queryByText(errorMessage)).not.toBeInTheDocument();

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
      expect(screen.queryByText(errorMessage)).not.toBeInTheDocument();
    });
  });
});

describe('Next Scheduled Logical Synchronizer Upgrade', () => {
  test('render default times for next scheduled logical upgrade', async () => {
    render(
      <Wrapper>
        <SetDsoConfigRulesForm />
      </Wrapper>
    );

    const effectiveDateInput = screen.getByTestId('set-dso-config-rules-effective-date-field');
    expect(effectiveDateInput).toBeInTheDocument();

    const tenDaysFromNow = dayjs().add(10, 'day').format(dateTimeFormatISO);

    fireEvent.change(effectiveDateInput, { target: { value: tenDaysFromNow } });

    const defaultUpgradeTimeDisplay = screen.getByTestId(
      'nextScheduledLogicalSynchronizerUpgradeUpgradeTime-default'
    );
    expect(defaultUpgradeTimeDisplay).toBeInTheDocument();

    // The default should be effective date + 1 hour in UTC format
    await waitFor(() => {
      const expectedDefaultTime = dayjs(tenDaysFromNow)
        .utc()
        .add(1, 'hour')
        .add(1, 'day')
        .format(nextScheduledSynchronizerUpgradeFormat);

      expect(defaultUpgradeTimeDisplay.textContent).toContain(`Default: ${expectedDefaultTime}`);
    });

    const defaultFreezeTimeDisplay = screen.getByTestId(
      'nextScheduledLogicalSynchronizerUpgradeTopologyFreezeTime-default'
    );
    expect(defaultFreezeTimeDisplay).toBeInTheDocument();

    // The default should be effective date + 1 hour in UTC format
    await waitFor(() => {
      const expectedDefaultTime = dayjs(tenDaysFromNow)
        .utc()
        .add(1, 'hour')
        .format(nextScheduledSynchronizerUpgradeFormat);

      expect(defaultFreezeTimeDisplay.textContent).toContain(`Default: ${expectedDefaultTime}`);
    });
  });

  test('no validation for logical synchronizer upgrade fields if none is provided', async () => {
    render(
      <Wrapper>
        <SetDsoConfigRulesForm />
      </Wrapper>
    );

    expect(
      screen.queryByText(
        'Topology freeze time, upgrade time, new physical synchronizer serial, and new physical synchronizer protocol version are required for a Scheduled Logical Synchronizer Upgrade'
      )
    ).not.toBeInTheDocument();
  });

  test('show error if only one of the parameters is set', async () => {
    const user = userEvent.setup();
    render(
      <Wrapper>
        <SetDsoConfigRulesForm />
      </Wrapper>
    );
    const errorMessage =
      'Topology freeze time, upgrade time, new physical synchronizer serial, and new physical synchronizer protocol version are required for a Scheduled Logical Synchronizer Upgrade';

    expect(screen.queryByText(errorMessage)).not.toBeInTheDocument();

    // serial field only - should show error
    const serialInput = screen.getByTestId(
      'config-field-nextScheduledLogicalSynchronizerUpgradeNewPhysicalSynchronizerSerial'
    );
    expect(serialInput).toBeInTheDocument();

    await user.type(serialInput, '12345');

    await waitFor(() => {
      expect(screen.queryByText(errorMessage)).toBeInTheDocument();
    });

    // Error should be gone when serial is cleared
    await user.clear(serialInput);
    await user.click(screen.getByTestId('set-dso-config-rules-action'));

    await waitFor(() => {
      expect(screen.queryByText(errorMessage)).not.toBeInTheDocument();
    });

    // protocol version field only - should show error
    const protocolVersionInput = screen.getByTestId(
      'config-field-nextScheduledLogicalSynchronizerUpgradeNewPhysicalSynchronizerProtocolVersion'
    );
    expect(protocolVersionInput).toBeInTheDocument();

    await user.type(protocolVersionInput, '12345');

    await waitFor(() => {
      expect(screen.queryByText(errorMessage)).toBeInTheDocument();
    });

    // Error should be gone when protocol version is cleared
    await user.clear(protocolVersionInput);
    await user.click(screen.getByTestId('set-dso-config-rules-action'));

    await waitFor(() => {
      expect(screen.queryByText(errorMessage)).not.toBeInTheDocument();
    });

    const topologyFreezeTimeInput = screen.getByTestId(
      'config-field-nextScheduledLogicalSynchronizerUpgradeTopologyFreezeTime'
    );
    expect(topologyFreezeTimeInput).toBeInTheDocument();

    const futureTime = dayjs().add(2, 'hour').format(dateTimeFormatISO);
    await user.type(topologyFreezeTimeInput, futureTime);

    await waitFor(() => {
      expect(screen.queryByText(errorMessage)).toBeInTheDocument();
    });

    // Error should be gone when topology freeze time is cleared
    await user.clear(topologyFreezeTimeInput);

    await waitFor(() => {
      expect(screen.queryByText(errorMessage)).not.toBeInTheDocument();
    });

    const topologyUpgradeTimeInput = screen.getByTestId(
      'config-field-nextScheduledLogicalSynchronizerUpgradeUpgradeTime'
    );
    expect(topologyUpgradeTimeInput).toBeInTheDocument();

    await user.type(topologyUpgradeTimeInput, futureTime);

    await waitFor(() => {
      expect(screen.queryByText(errorMessage)).toBeInTheDocument();
    });

    // Error should be gone when upgrade time is cleared
    await user.clear(topologyUpgradeTimeInput);

    await waitFor(() => {
      expect(screen.queryByText(errorMessage)).not.toBeInTheDocument();
    });
  });

  test('show error on form if times do not match constraints', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <SetDsoConfigRulesForm />
      </Wrapper>
    );

    const topologyFreezeTimeErrorMessage =
      'Topology Freeze Time must be at least 1 hour after the Effective Date';
    const upgradeTimeErrorMessage = 'Upgrade Time must be after Topology Freeze Time';

    const effectiveDateInput = screen.getByTestId('set-dso-config-rules-effective-date-field');
    const effectiveDate = dayjs().add(10, 'day').format(dateTimeFormatISO);

    fireEvent.change(effectiveDateInput, { target: { value: effectiveDate } });

    expect(screen.queryByText(topologyFreezeTimeErrorMessage)).not.toBeInTheDocument();

    const topologyFreezeTimeInput = screen.getByTestId(
      'config-field-nextScheduledLogicalSynchronizerUpgradeTopologyFreezeTime'
    );
    const upgradeTimeInput = screen.getByTestId(
      'config-field-nextScheduledLogicalSynchronizerUpgradeUpgradeTime'
    );
    const serialInput = screen.getByTestId(
      'config-field-nextScheduledLogicalSynchronizerUpgradeNewPhysicalSynchronizerSerial'
    );
    const protocolVersionInput = screen.getByTestId(
      'config-field-nextScheduledLogicalSynchronizerUpgradeNewPhysicalSynchronizerProtocolVersion'
    );

    // Set time to be only 30 minutes after effective date (should fail validation)
    const invalidTopologyFreezeTime = dayjs(effectiveDate)
      .utc()
      .add(30, 'minute')
      .format(nextScheduledSynchronizerUpgradeFormat);
    const invalidUpgradeTime = invalidTopologyFreezeTime;
    await user.type(topologyFreezeTimeInput, invalidTopologyFreezeTime);
    await user.type(upgradeTimeInput, invalidUpgradeTime);
    await user.type(serialInput, '1');
    await user.type(protocolVersionInput, '35');

    await waitFor(() => {
      expect(screen.queryByText(topologyFreezeTimeErrorMessage)).toBeInTheDocument();
    });

    // Set freeze time to be more than 1 hour after effective date - error should disappear
    const validTopologyFreezeTime = dayjs(effectiveDate)
      .utc()
      .add(2, 'hours')
      .format(nextScheduledSynchronizerUpgradeFormat);
    await user.clear(topologyFreezeTimeInput);
    await user.type(topologyFreezeTimeInput, validTopologyFreezeTime);

    await waitFor(() => {
      expect(screen.queryByText(topologyFreezeTimeErrorMessage)).not.toBeInTheDocument();
    });
    await waitFor(() => {
      expect(screen.queryByText(upgradeTimeErrorMessage)).toBeInTheDocument();
    });
    // Set upgrade time to be more than 1 hour after effective date - error should disappear
    const validUpgradeTime = dayjs(effectiveDate)
      .utc()
      .add(3, 'hours')
      .format(nextScheduledSynchronizerUpgradeFormat);
    await user.clear(upgradeTimeInput);
    await user.type(upgradeTimeInput, validUpgradeTime);
    await waitFor(() => {
      expect(screen.queryByText(upgradeTimeErrorMessage)).not.toBeInTheDocument();
    });
  });
});

describe('Next Scheduled Synchronizer Upgrade submission', () => {
  test('sends the upgrade time to the backend as the entered UTC value', async () => {
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
        <SetDsoConfigRulesForm />
      </Wrapper>
    );

    await user.type(screen.getByTestId('set-dso-config-rules-summary'), 'Summary of the proposal');
    await user.type(screen.getByTestId('set-dso-config-rules-url'), 'https://example.com');

    const effectiveDateLocal = dayjs().add(10, 'day').startOf('hour');
    fireEvent.change(screen.getByTestId('set-dso-config-rules-effective-date-field'), {
      target: { value: effectiveDateLocal.format(dateTimeFormatISO) },
    });

    const upgradeTimeUtc = effectiveDateLocal
      .utc()
      .add(2, 'hour')
      .format(nextScheduledSynchronizerUpgradeFormat);
    const upgradeTimeIfTreatedAsLocal = effectiveDateLocal
      .add(2, 'hour')
      .format(nextScheduledSynchronizerUpgradeFormat);
    expect(upgradeTimeUtc).not.toBe(upgradeTimeIfTreatedAsLocal);

    await user.type(
      screen.getByTestId('config-field-nextScheduledSynchronizerUpgradeTime'),
      upgradeTimeUtc
    );
    await user.type(
      screen.getByTestId('config-field-nextScheduledSynchronizerUpgradeMigrationId'),
      '12345'
    );

    await user.click(screen.getByTestId('set-dso-config-rules-action'));

    const submitButton = screen.getByTestId('submit-button');
    await waitFor(async () => {
      expect(submitButton).not.toBeDisabled();
    });

    await user.click(submitButton);
    await user.click(submitButton);

    await waitFor(() => {
      expect(requestBody).toContain(`"time":"${upgradeTimeUtc}"`);
    });
    expect(requestBody).toContain('"migrationId":"12345"');
    expect(requestBody).not.toContain(upgradeTimeIfTreatedAsLocal);
  });
});

describe('Values unchanged from the current config', () => {
  const pastTime = (days: number) =>
    dayjs()
      .utc()
      .subtract(days, 'day')
      .startOf('minute')
      .format(nextScheduledSynchronizerUpgradeFormat);

  const renderWithConfig = (configOverrides: Record<string, unknown>) => {
    const contract = dsoInfo.dso_rules.contract;
    server.use(
      http.get(`${svUrl}/v1/dso`, () =>
        HttpResponse.json({
          ...dsoInfo,
          dso_rules: {
            ...dsoInfo.dso_rules,
            contract: {
              ...contract,
              payload: {
                ...contract.payload,
                config: { ...contract.payload.config, ...configOverrides },
              },
            },
          },
        })
      )
    );
    render(
      <Wrapper>
        <SetDsoConfigRulesForm />
      </Wrapper>
    );
  };

  const fillSummaryAndUrl = async (user: ReturnType<typeof userEvent.setup>) => {
    await user.type(screen.getByTestId('set-dso-config-rules-summary'), 'Summary of the proposal');
    await user.type(screen.getByTestId('set-dso-config-rules-url'), 'https://example.com');
  };

  test('a stale logical synchronizer upgrade does not block a proposal effective at threshold', async () => {
    const user = userEvent.setup();
    const staleFreezeTime = pastTime(30);
    renderWithConfig({
      nextScheduledLogicalSynchronizerUpgrade: {
        topologyFreezeTime: staleFreezeTime,
        upgradeTime: pastTime(29),
        newPhysicalSynchronizerSerial: '1',
        newPhysicalSynchronizerProtocolVersion: '34',
      },
    });
    await screen.findByDisplayValue(staleFreezeTime);

    await fillSummaryAndUrl(user);
    await user.click(screen.getByTestId('effective-at-threshold-radio'));

    await waitFor(() => expect(screen.getByTestId('submit-button')).not.toBeDisabled());
    expect(
      screen.queryByText('Topology Freeze Time must be at least 1 hour after the Effective Date')
    ).not.toBeInTheDocument();
  });

  test('a stale switch-over time does not block a proposal with a new effective date', async () => {
    const user = userEvent.setup();
    const staleKey = 'no-featured-app-choice-context';
    renderWithConfig({ svOperationsSwitchOverTimes: { [staleKey]: pastTime(20) } });
    await screen.findByDisplayValue(staleKey);

    await fillSummaryAndUrl(user);
    fireEvent.change(screen.getByTestId('set-dso-config-rules-effective-date-field'), {
      target: { value: dayjs().add(10, 'day').format(dateTimeFormatISO) },
    });

    await waitFor(() => expect(screen.getByTestId('submit-button')).not.toBeDisabled());
    expect(screen.queryByTestId('switchover-error')).not.toBeInTheDocument();
  });
});
