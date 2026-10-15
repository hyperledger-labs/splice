// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { render, screen, within } from '@testing-library/react';
import userEvent, { UserEvent } from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, describe, expect, test } from 'vitest';

import App from '../../App';
import { SvConfigProvider } from '../../utils';
import { voteRequests } from '../mocks/constants';
import { server, svUrl } from '../setup/setup';

// The SV app's per-client-IP rate limiter answers a burst of requests with a 429 and this body.
const tooManyRequests = () =>
  HttpResponse.json(
    { error: 'Too Many Requests: Server is busy, please try again later.' },
    { status: 429 }
  );

const contractId = voteRequests.dso_rules_vote_requests[0].contract_id;

// Retries back off exponentially starting at one second, so give the page a few seconds.
const RETRY_TIMEOUT = { timeout: 5000 };

const loginAndOpenDetailsPage = async (): Promise<UserEvent> => {
  const user = userEvent.setup();
  render(
    <SvConfigProvider>
      <App />
    </SvConfigProvider>
  );
  expect(await screen.findByText('Log In')).toBeInTheDocument();
  await user.type(screen.getByRole('textbox'), 'sv1');
  await user.click(screen.getByRole('button', { name: 'Log In' }));
  expect(await screen.findByTestId('navlink-governance', {}, RETRY_TIMEOUT)).toBeInTheDocument();

  window.history.pushState({}, '', `/governance/proposals/${contractId}`);
  window.dispatchEvent(new PopStateEvent('popstate'));
  return user;
};

describe('proposal details under rate limiting', () => {
  afterEach(() => {
    // Start every test logged out and on the landing page.
    window.sessionStorage.clear();
    window.history.pushState({}, '', '/');
  });

  test('renders the proposal when nothing is rate limited', async () => {
    await loginAndOpenDetailsPage();

    expect(
      await screen.findByTestId('proposal-details-title', {}, RETRY_TIMEOUT)
    ).toHaveTextContent('Proposal Details');
  });

  test('renders the proposal after the lookup was rate limited once', async () => {
    server.use(http.get(`${svUrl}/v0/admin/sv/voterequests/:id`, tooManyRequests, { once: true }));

    await loginAndOpenDetailsPage();

    expect(
      await screen.findByTestId('proposal-details-title', {}, RETRY_TIMEOUT)
    ).toHaveTextContent('Proposal Details');
    expect(screen.queryByText(/Unable to find the proposal/)).not.toBeInTheDocument();
  });

  test('shows the success message after casting the vote was rate limited once', async () => {
    server.use(http.post(`${svUrl}/v0/admin/sv/votes`, tooManyRequests, { once: true }));

    const user = await loginAndOpenDetailsPage();
    const votingForm = await screen.findByTestId('your-vote-form', {}, RETRY_TIMEOUT);

    // Clicking Accept both selects the vote and submits; not awaited so the submitting state is observable.
    void user.click(within(votingForm).getByTestId('your-vote-accept'));

    const successMessage = await screen.findByTestId('vote-submission-success', {}, RETRY_TIMEOUT);
    expect(successMessage.textContent).toMatch(/Vote successfully updated/);
  });
});
