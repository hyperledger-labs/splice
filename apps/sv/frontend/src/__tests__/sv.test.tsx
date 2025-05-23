// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { rest } from 'msw';
import { mockAllIsIntersecting } from 'react-intersection-observer/test-utils';
import { ListDsoRulesVoteRequestsResponse } from 'sv-openapi';
import { test, expect, describe } from 'vitest';

import App from '../App';
import { SvConfigProvider } from '../utils';
import { svPartyId, voteRequests } from './mocks/constants';
import { server, svUrl } from './setup/setup';
import { changeAction } from './helpers';

const AppWithConfig = () => {
  return (
    <SvConfigProvider>
      <App />
    </SvConfigProvider>
  );
};

describe('SV user can', () => {
  test('login and see the SV party ID', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    expect(await screen.findByText('Log In')).toBeDefined();

    const input = screen.getByRole('textbox');
    await user.type(input, 'sv1');

    const button = screen.getByRole('button', { name: 'Log In' });
    user.click(button);

    expect(await screen.findAllByDisplayValue(svPartyId)).toBeDefined();
  });

  test('can see the network name banner', async () => {
    userEvent.setup();
    render(<AppWithConfig />);

    await screen.findByText('You are on ScratchNet');
  });

  test('browse to the governance tab', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    expect(await screen.findByText('Governance')).toBeDefined();
    await user.click(screen.getByText('Governance'));

    expect(await screen.findByText('Vote Requests')).toBeDefined();
  });
});

describe('An SetConfig request', () => {
  test('defaults to the current amulet configuration', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    expect(await screen.findByText('Governance')).toBeDefined();
    await user.click(screen.getByText('Governance'));

    expect(await screen.findByText('Vote Requests')).toBeDefined();
    expect(await screen.findByText('Governance')).toBeDefined();

    changeAction('CRARC_SetConfig');

    expect(await screen.findByText('transferConfig.createFee.fee')).toBeDefined();
    expect(await screen.findByDisplayValue('0.03')).toBeDefined();

    changeAction('SRARC_SetConfig');

    expect(await screen.findByText('numUnclaimedRewardsThreshold')).toBeDefined();
    expect(await screen.findByDisplayValue('10')).toBeDefined();
  });

  test(
    'displays a warning when an SV tries to modify a DsoRules field already changed by another request',
    { timeout: 10000 },
    async () => {
      const user = userEvent.setup();
      render(<AppWithConfig />);

      expect(await screen.findByText('Governance')).toBeDefined();
      await user.click(screen.getByText('Governance'));

      expect(await screen.findByText('Vote Requests')).toBeDefined();
      expect(await screen.findByText('Governance')).toBeDefined();

      changeAction('SRARC_SetConfig');

      const input = screen.getByTestId(
        'decentralizedSynchronizer.synchronizers.0.1.acsCommitmentReconciliationInterval-value'
      );
      await user.clear(input);
      await user.type(input, '481516');
      expect(await screen.findByDisplayValue('481516')).toBeDefined();

      const summaryInput = screen.getByTestId('create-reason-summary');
      await user.type(summaryInput, 'summaryABC');
      expect(await screen.findByDisplayValue('summaryABC')).toBeDefined();

      const urlInput = screen.getByTestId('create-reason-url');
      await user.type(urlInput, 'https://vote-request.url');

      const warning = screen.getByTestId('voterequest-creation-alert');
      expect(warning).toBeDefined();
      expect(warning.textContent).toContain(
        'A Vote Request aiming to change similar fields already exists. ' +
          'You are therefore not allowed to modify the fields: decentralizedSynchronizer.synchronizers.acsCommitmentReconciliationInterval'
      );

      const button = screen.getByRole('button', { name: 'Send Request to Super Validators' });
      expect(button.getAttribute('disabled')).toBeDefined();
    }
  );

  test(
    'displays a warning when an SV tries to modify an AmuletRules field already changed by another request',
    { timeout: 10000 },
    async () => {
      const user = userEvent.setup();
      render(<AppWithConfig />);

      expect(await screen.findByText('Governance')).toBeDefined();
      await user.click(screen.getByText('Governance'));

      expect(await screen.findByText('Vote Requests')).toBeDefined();
      expect(await screen.findByText('Governance')).toBeDefined();

      changeAction('CRARC_SetConfig');

      const input = screen.getByTestId('transferConfig.createFee.fee-value');
      await user.clear(input);
      await user.type(input, '481516');
      expect(await screen.findByDisplayValue('481516')).toBeDefined();

      const input2 = screen.getByTestId('create-reason-summary');
      await user.type(input2, 'summaryABC');
      expect(await screen.findByDisplayValue('summaryABC')).toBeDefined();

      const urlInput = screen.getByTestId('create-reason-url');
      await user.type(urlInput, 'https://vote-request.url');

      const warning = screen.getByTestId('voterequest-creation-alert');
      expect(warning).toBeDefined();
      expect(warning.textContent).toContain(
        'A Vote Request aiming to change similar fields already exists. ' +
          'You are therefore not allowed to modify the fields: transferConfig.createFee.fee'
      );

      const button = screen.getByRole('button', { name: 'Send Request to Super Validators' });
      expect(button.getAttribute('disabled')).toBeDefined();
    }
  );

  test(
    'disables the Proceed button in the confirmation dialog if a conflict arises after request creation',
    { timeout: 10000 },
    async () => {
      server.use(
        rest.get(`${svUrl}/v0/admin/sv/voterequests`, (_, res, ctx) => {
          return res(ctx.json<ListDsoRulesVoteRequestsResponse>({ dso_rules_vote_requests: [] }));
        })
      );

      const user = userEvent.setup();
      render(<AppWithConfig />);

      expect(await screen.findByText('Governance')).toBeDefined();
      await user.click(screen.getByText('Governance'));

      expect(await screen.findByText('Vote Requests')).toBeDefined();
      expect(await screen.findByText('Governance')).toBeDefined();

      changeAction('CRARC_SetConfig');

      const input = screen.getByTestId('transferConfig.createFee.fee-value');
      await user.clear(input);
      await user.type(input, '481516');
      expect(await screen.findByDisplayValue('481516')).toBeDefined();

      const input2 = screen.getByTestId('create-reason-summary');
      await user.type(input2, 'summaryABC');
      expect(await screen.findByDisplayValue('summaryABC')).toBeDefined();

      const urlInput = screen.getByTestId('create-reason-url');
      await user.type(urlInput, 'https://vote-request.url');
      expect(await screen.findByDisplayValue('https://vote-request.url')).toBeDefined();

      expect(await screen.findByText('Send Request to Super Validators')).toBeDefined();
      await user.click(screen.getByText('Send Request to Super Validators'));

      server.use(
        rest.get(`${svUrl}/v0/admin/sv/voterequests`, (_, res, ctx) => {
          return res(ctx.json<ListDsoRulesVoteRequestsResponse>(voteRequests));
        })
      );

      const button = screen.getByRole('button', { name: 'Proceed' });
      expect(button.getAttribute('disabled')).toBeDefined();
    }
  );
});

describe('An AddFutureAmuletConfigSchedule request', () => {
  test('is displayed in executed section when its effective date is in the past', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    expect(await screen.findByText('Governance')).toBeDefined();
    await user.click(screen.getByText('Governance'));

    expect(await screen.findByText('Vote Requests')).toBeDefined();
    expect(await screen.findByText('Governance')).toBeDefined();

    expect(await screen.findByText('Executed')).toBeDefined();
    await user.click(screen.getByText('Executed'));

    expect(await screen.findByText('CRARC_AddFutureAmuletConfigSchedule')).toBeDefined();
  });

  test('validator licenses are displayed and paginable', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    expect(await screen.findByText('Validator Onboarding')).toBeDefined();
    await user.click(screen.getByText('Validator Onboarding'));

    expect(await screen.findByText('Validator Licenses')).toBeDefined();

    expect(await screen.findByDisplayValue('validator::1')).toBeDefined();
    expect(screen.queryByText('validator::15')).toBeNull();

    mockAllIsIntersecting(true);
    expect(await screen.findByDisplayValue('validator::15')).toBeDefined();
  });
});
