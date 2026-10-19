// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import userEvent from '@testing-library/user-event';
import { SvConfigProvider } from '../../../utils';
import App from '../../../App';
import { svPartyId } from '../../mocks/constants';
import { Wrapper } from '../../helpers';
import { dateTimeFormatISO } from '@canton-network/splice-common-frontend-utils';
import dayjs from 'dayjs';
import { GrantRevokeFeaturedAppForm } from '../../../components/forms/GrantRevokeFeaturedAppForm';
import { server, svUrl } from '../../setup/setup';
import { http, HttpResponse } from 'msw';
import {
  CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE,
  CREATE_PROPOSAL_LABEL_PROVIDER_PARTY_ID,
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
    await user.click(button);

    expect(await screen.findAllByDisplayValue(svPartyId)).not.toHaveLength(0);
  });
});

describe('Grant Featured App Form', () => {
  test('should render all Form components', () => {
    render(
      <Wrapper>
        <GrantRevokeFeaturedAppForm selectedAction="SRARC_GrantFeaturedAppRight" />
      </Wrapper>
    );

    expect(screen.getByTestId('grant-featured-app-form')).toBeInTheDocument();
    expect(screen.getByText(CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE)).toBeInTheDocument();

    const actionInput = screen.getByTestId('grant-featured-app-action');
    expect(actionInput).toBeInTheDocument();
    expect(actionInput.textContent).toBe('Feature Application');

    const summaryInput = screen.getByTestId('grant-featured-app-summary');
    expect(summaryInput).toBeInTheDocument();
    expect(summaryInput.getAttribute('value')).toBeNull();

    const summarySubtitle = screen.getByTestId('grant-featured-app-summary-subtitle');
    expect(summarySubtitle).toBeInTheDocument();
    expect(summarySubtitle.textContent).toBe(PROPOSAL_SUMMARY_SUBTITLE);

    const urlInput = screen.getByTestId('grant-featured-app-url');
    expect(urlInput).toBeInTheDocument();
    expect(urlInput.getAttribute('value')).toBe('');

    const idInput = screen.getByTestId('grant-featured-app-idValue');
    expect(idInput).toBeInTheDocument();
    expect(idInput.getAttribute('value')).toBe('');

    const providerInput = screen.getByTestId('grant-featured-app-idValue-title');
    expect(providerInput).toBeInTheDocument();
    expect(providerInput.textContent).toBe(CREATE_PROPOSAL_LABEL_PROVIDER_PARTY_ID);

    expect(screen.getByText('Review Proposal')).toBeInTheDocument();
  });

  test('should render errors when submit button is clicked on new form', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <GrantRevokeFeaturedAppForm selectedAction="SRARC_GrantFeaturedAppRight" />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('grant-featured-app-action');
    const submitButton = screen.getByTestId('submit-button');
    expect(submitButton).toBeInTheDocument();

    await user.click(submitButton);
    expect(submitButton).toBeDisabled();
    await expect(async () => await user.click(submitButton)).rejects.toThrowError(
      /Unable to perform pointer interaction/
    );

    screen.getByText('Summary is required');
    screen.getByText('Invalid URL');
    expect(screen.getByTestId('grant-featured-app-idValue-error').textContent).toBe('Required');

    // completing the form should reenable the submit button
    const summaryInput = screen.getByTestId('grant-featured-app-summary');
    expect(summaryInput).toBeInTheDocument();
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('grant-featured-app-url');
    expect(urlInput).toBeInTheDocument();
    await user.type(urlInput, 'https://example.com');

    const providerInput = screen.getByTestId('grant-featured-app-idValue');
    expect(providerInput).toBeInTheDocument();
    await user.type(providerInput, 'a-party-id::1014912492');

    await user.click(actionInput); // using this to trigger the onBlur event which triggers the validation

    expect(submitButton).not.toBeDisabled();
  });

  test('expiry date must be in the future', async () => {
    render(
      <Wrapper>
        <GrantRevokeFeaturedAppForm selectedAction="SRARC_GrantFeaturedAppRight" />
      </Wrapper>
    );

    const expiryDateInput = screen.getByTestId('grant-featured-app-expiry-date-field');
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
        <GrantRevokeFeaturedAppForm selectedAction="SRARC_GrantFeaturedAppRight" />
      </Wrapper>
    );

    const expiryDateInput = screen.getByTestId('grant-featured-app-expiry-date-field');
    const effectiveDateInput = screen.getByTestId('grant-featured-app-effective-date-field');

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
        <GrantRevokeFeaturedAppForm selectedAction="SRARC_GrantFeaturedAppRight" />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('grant-featured-app-action');

    const summaryInput = screen.getByTestId('grant-featured-app-summary');
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('grant-featured-app-url');
    await user.type(urlInput, 'https://example.com');

    const providerInput = screen.getByTestId('grant-featured-app-idValue');
    await user.type(providerInput, 'a-party-id::1014912492');

    expect(screen.getByText('Review Proposal')).toBeInTheDocument();
    const submitButton = screen.getByTestId('submit-button');
    await user.click(actionInput); // using this to trigger the onBlur event which triggers the validation

    await waitFor(() => {
      expect(screen.queryByText('Validating provider...')).not.toBeInTheDocument();
    });

    expect(screen.queryByText('Provider party not found on ledger')).not.toBeInTheDocument();

    await waitFor(async () => {
      expect(submitButton).not.toBeDisabled();
    });

    await user.click(submitButton);

    expect(screen.getByText(PROPOSAL_REVIEW_TITLE)).toBeInTheDocument();
  });

  test('activity weight is optional and is sent to backend as null when left blank', async () => {
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
        <GrantRevokeFeaturedAppForm selectedAction="SRARC_GrantFeaturedAppRight" />
      </Wrapper>
    );

    const activityWeightInput = screen.getByTestId('grant-featured-app-activityWeight');
    expect(activityWeightInput.getAttribute('value')).toBe('');

    const actionInput = screen.getByTestId('grant-featured-app-action');
    const submitButton = screen.getByTestId('submit-button');

    const summaryInput = screen.getByTestId('grant-featured-app-summary');
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('grant-featured-app-url');
    await user.type(urlInput, 'https://example.com');

    const providerInput = screen.getByTestId('grant-featured-app-idValue');
    await user.type(providerInput, 'a-party-id::1014912492');

    await user.click(activityWeightInput);

    await user.click(actionInput); // using this to trigger the onBlur event which triggers the validation

    await waitFor(() => {
      expect(screen.queryByText('Validating provider...')).not.toBeInTheDocument();
    });

    await waitFor(async () => {
      expect(submitButton).not.toBeDisabled();
    });

    await user.click(submitButton); // review proposal

    expect(screen.getByTestId('grantRightActivityWeight-field').textContent).toBe('');

    await user.click(submitButton); // submit proposal

    await waitFor(() => {
      expect(requestBody).toContain('"activityWeight":null');
    });
  });

  test('should send explicit activity weight to backend when provided', async () => {
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
        <GrantRevokeFeaturedAppForm selectedAction="SRARC_GrantFeaturedAppRight" />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('grant-featured-app-action');
    const submitButton = screen.getByTestId('submit-button');

    const summaryInput = screen.getByTestId('grant-featured-app-summary');
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('grant-featured-app-url');
    await user.type(urlInput, 'https://example.com');

    const providerInput = screen.getByTestId('grant-featured-app-idValue');
    await user.type(providerInput, 'a-party-id::1014912492');

    const activityWeightInput = screen.getByTestId('grant-featured-app-activityWeight');
    await user.type(activityWeightInput, '2.5');

    await user.click(actionInput); // using this to trigger the onBlur event which triggers the validation

    await waitFor(() => {
      expect(screen.queryByText('Validating provider...')).not.toBeInTheDocument();
    });

    await waitFor(async () => {
      expect(submitButton).not.toBeDisabled();
    });

    await user.click(submitButton); // review proposal
    await user.click(submitButton); // submit proposal

    await waitFor(() => {
      expect(requestBody).toContain('"activityWeight":"2.5"');
    });
  });

  test('sends opsParties to backend when provided', async () => {
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
        <GrantRevokeFeaturedAppForm selectedAction="SRARC_GrantFeaturedAppRight" />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('grant-featured-app-action');
    const submitButton = screen.getByTestId('submit-button');

    await user.type(screen.getByTestId('grant-featured-app-summary'), 'Summary of the proposal');
    await user.type(screen.getByTestId('grant-featured-app-url'), 'https://example.com');
    await user.type(screen.getByTestId('grant-featured-app-idValue'), 'a-party-id::1014912492');

    // add one ops party
    await user.click(screen.getByTestId('grant-featured-app-opsParties-add'));
    await user.type(
      screen.getByTestId('grant-featured-app-opsParties-row-0'),
      'ops-alice::1014912493'
    );

    await user.click(actionInput); // trigger provider onBlur validation

    await waitFor(() => {
      expect(screen.queryByText('Validating provider...')).not.toBeInTheDocument();
    });
    await waitFor(() => {
      expect(submitButton).not.toBeDisabled();
    });

    await user.click(submitButton); // review proposal
    await user.click(submitButton); // submit proposal

    await waitFor(() => {
      expect(requestBody).toContain('"opsParties":["ops-alice::1014912493"]');
    });
  });

  test('warns when the provider already has a featured app right', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <GrantRevokeFeaturedAppForm selectedAction="SRARC_GrantFeaturedAppRight" />
      </Wrapper>
    );

    // the mock returns an existing right for this provider party id
    await user.type(screen.getByTestId('grant-featured-app-idValue'), 'a-party-id::1014912492');

    await waitFor(() => {
      expect(screen.getByTestId('grant-featured-app-provider-warning').textContent).toMatch(
        /already has a featured app right/
      );
    });
  });

  test('activity weight rejects negative numbers and more than 10 decimal places', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <GrantRevokeFeaturedAppForm selectedAction="SRARC_GrantFeaturedAppRight" />
      </Wrapper>
    );

    const activityWeightInput = screen.getByTestId('grant-featured-app-activityWeight');
    const activityWeightError = screen.getByTestId('grant-featured-app-activityWeight-error');

    await user.type(activityWeightInput, '-1');
    await waitFor(() => {
      expect(activityWeightError.textContent).toBe('Weight must be a valid non-negative number');
    });

    await user.clear(activityWeightInput);
    await user.type(activityWeightInput, '1.1234567891');
    await waitFor(() => {
      expect(activityWeightError.textContent).toBe('');
    });

    await user.clear(activityWeightInput);
    await user.type(activityWeightInput, '1.12345678912');
    await waitFor(() => {
      expect(activityWeightError.textContent).toBe('Weight can have at most 10 decimal places');
    });
  });
});

describe('Revoke Featured App Form', () => {
  const fillOutRevokeForm = async (user: ReturnType<typeof userEvent.setup>) => {
    const summaryInput = screen.getByTestId('revoke-featured-app-summary');
    await user.type(summaryInput, 'Summary of the proposal');

    const urlInput = screen.getByTestId('revoke-featured-app-url');
    await user.type(urlInput, 'https://example.com');

    const partyIdInput = screen.getByTestId('revoke-featured-app-partyId');
    await user.type(partyIdInput, 'a-party-id::1014912492');
    fireEvent.blur(partyIdInput);

    await waitFor(() => {
      expect(
        screen.queryByText('Loading Featured Application Contract IDs...')
      ).not.toBeInTheDocument();
    });

    const rightCidDropdown = screen.getByTestId('revoke-featured-app-rightCid-dropdown');
    await waitFor(
      () => {
        expect(rightCidDropdown).not.toBeDisabled();
      },
      { timeout: 3000 }
    );
    fireEvent.change(rightCidDropdown, { target: { value: 'rightCid123' } });
    fireEvent.blur(rightCidDropdown);
    await waitFor(() => {
      expect(screen.getByTestId('revoke-featured-app-rightCid-error').textContent).toBeFalsy();
    });
  };

  test('should render all Form components', () => {
    render(
      <Wrapper>
        <GrantRevokeFeaturedAppForm selectedAction="SRARC_RevokeFeaturedAppRight" />
      </Wrapper>
    );

    expect(screen.getByTestId('revoke-featured-app-form')).toBeInTheDocument();
    expect(screen.getByText(CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE)).toBeInTheDocument();

    const actionInput = screen.getByTestId('revoke-featured-app-action');
    expect(actionInput).toBeInTheDocument();
    expect(actionInput.textContent).toBe('Unfeature Application');

    const summaryInput = screen.getByTestId('revoke-featured-app-summary');
    expect(summaryInput).toBeInTheDocument();
    expect(summaryInput.getAttribute('value')).toBeNull();

    const urlInput = screen.getByTestId('revoke-featured-app-url');
    expect(urlInput).toBeInTheDocument();
    expect(urlInput.getAttribute('value')).toBe('');

    const partyIdInput = screen.getByTestId('revoke-featured-app-partyId');
    expect(partyIdInput).toBeInTheDocument();
    expect(partyIdInput.getAttribute('value')).toBe('');

    const partyIdTitle = screen.getByTestId('revoke-featured-app-partyId-title');
    expect(partyIdTitle).toBeInTheDocument();
    expect(partyIdTitle.textContent).toBe(CREATE_PROPOSAL_LABEL_PROVIDER_PARTY_ID);

    const rightCidDropdown = screen.getByTestId('revoke-featured-app-rightCid-dropdown');
    expect(rightCidDropdown).toBeInTheDocument();
    expect(rightCidDropdown).toBeDisabled();
  });

  test('communicates when the provider has no featured app rights to unfeature', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <GrantRevokeFeaturedAppForm selectedAction="SRARC_RevokeFeaturedAppRight" />
      </Wrapper>
    );

    const partyIdInput = screen.getByTestId('revoke-featured-app-partyId');
    await user.type(partyIdInput, 'no-rights-party::1014912492');
    fireEvent.blur(partyIdInput);

    await waitFor(() => {
      expect(screen.getByTestId('revoke-featured-app-rightCid')).toHaveTextContent(
        'No Featured Application Contract IDs found for this provider'
      );
    });

    expect(screen.getByTestId('revoke-featured-app-rightCid-error')).not.toHaveTextContent(
      'No Featured Application Contract IDs found for this provider'
    );

    expect(screen.getByTestId('revoke-featured-app-rightCid-dropdown')).toBeDisabled();
  });

  test('should render errors when submit button is clicked on new form', async () => {
    const user = userEvent.setup();

    render(
      <Wrapper>
        <GrantRevokeFeaturedAppForm selectedAction="SRARC_RevokeFeaturedAppRight" />
      </Wrapper>
    );

    const submitButton = screen.getByTestId('submit-button');
    expect(submitButton).toBeInTheDocument();
    expect(submitButton).toBeDisabled();

    // completing the form should reenable the submit button
    await fillOutRevokeForm(user);

    await waitFor(() => {
      expect(submitButton).not.toBeDisabled();
    });
  });

  test('expiry date must be in the future', async () => {
    render(
      <Wrapper>
        <GrantRevokeFeaturedAppForm selectedAction="SRARC_RevokeFeaturedAppRight" />
      </Wrapper>
    );

    const expiryDateInput = screen.getByTestId('revoke-featured-app-expiry-date-field');
    expect(expiryDateInput).toBeInTheDocument();

    const thePast = dayjs().subtract(1, 'day').format(dateTimeFormatISO);
    const theFuture = dayjs().add(1, 'day').format(dateTimeFormatISO);

    fireEvent.change(expiryDateInput, { target: { value: thePast } });

    await waitFor(() => {
      expect(screen.queryByText('Expiration must be in the future')).toBeInTheDocument();
    });

    fireEvent.change(expiryDateInput, { target: { value: theFuture } });

    expect(screen.queryByText('Expiration must be in the future')).not.toBeInTheDocument();
  });

  test('effective date must be after expiry date', async () => {
    render(
      <Wrapper>
        <GrantRevokeFeaturedAppForm selectedAction="SRARC_RevokeFeaturedAppRight" />
      </Wrapper>
    );

    const expiryDateInput = screen.getByTestId('revoke-featured-app-expiry-date-field');
    const effectiveDateInput = screen.getByTestId('revoke-featured-app-effective-date-field');

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
        <GrantRevokeFeaturedAppForm selectedAction="SRARC_RevokeFeaturedAppRight" />
      </Wrapper>
    );

    await fillOutRevokeForm(user);

    expect(screen.getByText('Review Proposal')).toBeInTheDocument();

    const submitButton = screen.getByTestId('submit-button');
    await waitFor(async () => {
      expect(submitButton).not.toBeDisabled();
    });

    await user.click(submitButton);

    expect(screen.getByText(PROPOSAL_REVIEW_TITLE)).toBeInTheDocument();
    expect(screen.getByTestId('revokeProviderPartyId-title').textContent).toBe('Provider Party ID');
    expect(screen.getByTestId('revokeProviderPartyId-field').textContent).toBe(
      'a-party-id::1014912492'
    );
    expect(screen.getByTestId('revokeRight-title').textContent).toBe(
      'Featured Application Contract ID'
    );
    expect(screen.getByTestId('revokeRight-field').textContent).toBe('rightCid123');
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
        <GrantRevokeFeaturedAppForm selectedAction="SRARC_RevokeFeaturedAppRight" />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('revoke-featured-app-action');
    const submitButton = screen.getByTestId('submit-button');

    await fillOutRevokeForm(user);
    await user.click(actionInput);

    await waitFor(async () => {
      expect(submitButton).not.toBeDisabled();
    });

    await user.click(submitButton); //review proposal
    await user.click(submitButton); //submit proposal

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
        <GrantRevokeFeaturedAppForm selectedAction="SRARC_RevokeFeaturedAppRight" />
      </Wrapper>
    );

    const actionInput = screen.getByTestId('revoke-featured-app-action');
    const submitButton = screen.getByTestId('submit-button');

    await fillOutRevokeForm(user);
    await user.click(actionInput);

    await waitFor(async () => {
      expect(submitButton).not.toBeDisabled();
    });

    await user.click(submitButton); //review proposal
    await user.click(submitButton); //submit proposal

    await screen.findByText('Successfully submitted the proposal');
  });
});
