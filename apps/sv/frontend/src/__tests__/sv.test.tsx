// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { rest } from 'msw';
import { mockAllIsIntersecting } from 'react-intersection-observer/test-utils';
import {
  CreateVoteRequest,
  ListDsoRulesVoteRequestsResponse,
} from '@lfdecentralizedtrust/sv-openapi';
import { test, expect, describe } from 'vitest';

import App from '../App';
import { SvConfigProvider } from '../utils';
import { onboardingInfo } from '../components/ValidatorOnboardingSecrets';
import { svPartyId, voteRequests } from './mocks/constants';
import { server, svUrl } from './setup/setup';
import { changeAction } from './helpers';
import {
  dateTimeFormatISO,
  getUTCWithOffset,
} from '@lfdecentralizedtrust/splice-common-frontend-utils';
import dayjs from 'dayjs';
import { dsoInfo } from '@lfdecentralizedtrust/splice-common-test-handlers';

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

  test('browse to the validator onboarding tab', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    expect(await screen.findByText('Validator Onboarding')).toBeDefined();
    await user.click(screen.getByText('Validator Onboarding'));

    expect(await screen.findByText('Validator Onboarding Secrets')).toBeDefined();
  });

  test('create a new validator secret with party hint', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    expect(await screen.findByText('Validator Onboarding')).toBeDefined();
    await user.click(screen.getByText('Validator Onboarding'));

    const partyHintInput = screen.getByTestId('create-party-hint');
    await user.type(partyHintInput, 'wrong-input');

    expect(screen.getByTestId('create-validator-onboarding-secret').hasAttribute('disabled')).toBe(
      true
    );

    await user.clear(partyHintInput);
    await user.type(partyHintInput, 'correct-input-123');

    expect(screen.getByTestId('create-validator-onboarding-secret').hasAttribute('disabled')).toBe(
      false
    );
  });

  test('validator onboarding info has correct format', () => {
    const validatorOnboardingInfo = onboardingInfo(
      {
        partyHint: 'splice-client-2',
        secret: 'exampleSecret',
        expiresAt: '2020-01-01 13:57',
      },
      'testnet'
    );

    expect(validatorOnboardingInfo).toBe(
      `
splice-client-2
Network: testnet
SPONSOR_SV_URL
http://localhost:3000

Secret
exampleSecret

Expiration
2020-01-01 13:57 (${getUTCWithOffset()})
    `.trim()
    );
  });

  test('browse to the governance tab', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    expect(await screen.findByText('Governance')).toBeDefined();
    await user.click(screen.getByText('Governance'));

    expect(await screen.findByText('Vote Requests')).toBeDefined();
  });

  test('see proper time format in popup', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    expect(await screen.findByText('Governance')).toBeDefined();
    await user.click(screen.getByText('Governance'));

    const inOneWeek = dayjs().add(1, 'week').format(dateTimeFormatISO);
    const expirationDate = dayjs().add(23, 'minutes').format(dateTimeFormatISO);

    const dateInput = screen.getByTestId('datetime-picker-vote-request-expiration');

    // We wait for the date to be set to the default value from the ledger.
    await waitFor(() => expect(dateInput.getAttribute('value')).toBe(inOneWeek));
    fireEvent.change(dateInput, { target: { value: expirationDate } });
    expect(dateInput.getAttribute('value')).toBe(expirationDate);

    const formExpirationLabel = screen.getByTestId('vote-request-expiration-duration');
    expect(formExpirationLabel).toHaveTextContent('in 23 minutes');

    const options: HTMLOptionElement[] = await screen.findAllByTestId('display-members-option');

    fireEvent.change(screen.getByTestId('display-members'), {
      target: {
        value: options[0].value,
      },
    });

    const summaryInput = screen.getByTestId('create-reason-summary');
    await user.type(summaryInput, 'summaryABC');

    const urlInput = screen.getByTestId('create-reason-url');
    await user.type(urlInput, 'https://vote-request.url');

    const submitButton = screen.getByTestId('create-voterequest-submit-button');
    await user.click(submitButton);

    const submitPopup = screen.getByRole('dialog');

    expect(submitPopup).toHaveTextContent('in 23 minutes');
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

    await changeAction('CRARC_SetConfig');

    expect(await screen.findByText('transferConfig.createFee.fee')).toBeDefined();
    expect(await screen.findByDisplayValue('0.03')).toBeDefined();

    await changeAction('SRARC_SetConfig');

    expect(await screen.findByText('numUnclaimedRewardsThreshold')).toBeDefined();
    expect(await screen.findByDisplayValue('10')).toBeDefined();
    expect(
      screen.getByTestId('dsoDelegateInactiveTimeout.microseconds-value').hasAttribute('disabled')
    ).toBe(true);
  });

  test('displays a warning when an SV tries to modify a DsoRules field already changed by another request', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    expect(await screen.findByText('Governance')).toBeDefined();
    await user.click(screen.getByText('Governance'));

    expect(await screen.findByText('Vote Requests')).toBeDefined();
    expect(await screen.findByText('Governance')).toBeDefined();

    await changeAction('SRARC_SetConfig');
    await waitFor(() => expect(screen.getByTestId('set-dso-rules-config-header')).toBeDefined());

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
  });

  test('displays a warning when an SV tries to modify an AmuletRules field already changed by another request', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    expect(await screen.findByText('Governance')).toBeDefined();
    await user.click(screen.getByText('Governance'));

    expect(await screen.findByText('Vote Requests')).toBeDefined();

    await changeAction('CRARC_SetConfig');
    await waitFor(() => expect(screen.getByTestId('set-amulet-rules-config-header')).toBeDefined());

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
    const button = screen.getByTestId('create-voterequest-submit-button');
    expect(button.getAttribute('disabled')).toBeDefined();
  });

  test('disables the Proceed button in the confirmation dialog if a conflict arises after request creation', async () => {
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

    await changeAction('CRARC_SetConfig');
    await waitFor(() => expect(screen.getByTestId('set-amulet-rules-config-header')).toBeDefined());

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
  });
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

    // Deprecated from dsoGovernance 0.1.15 (should still be displayed in Executed)
    expect(await screen.findByText('CRARC_AddFutureAmuletConfigSchedule')).toBeDefined();
  });

  test('validator licenses and secrets are displayed and paginable', async () => {
    const user = userEvent.setup();
    render(<AppWithConfig />);

    expect(await screen.findByText('Validator Onboarding')).toBeDefined();
    await user.click(screen.getByText('Validator Onboarding'));

    expect(await screen.findByText('Validator Licenses')).toBeDefined();

    expect(await screen.findByDisplayValue('validator::1')).toBeDefined();
    expect(screen.queryByText('validator::15')).toBeNull();

    mockAllIsIntersecting(true);
    expect(await screen.findByDisplayValue('validator::15')).toBeDefined();

    // secrets
    expect(await screen.queryByText('encoded_secret')).toBeDefined();
    expect(await screen.queryByText('candidate_secret')).toBeNull();
  });
});

describe('SetAmuletRules', () => {
  test(
    'should not send the transfer fee steps that are set to 0',
    async () => {
      let calledCreate: (body: CreateVoteRequest) => void;
      const createPromise: Promise<CreateVoteRequest> = new Promise(
        resolve => (calledCreate = resolve)
      );
      server.use(
        rest.post(`${svUrl}/v0/admin/sv/voterequest/create`, async (req, res, ctx) => {
          calledCreate(await req.json());
          return res(ctx.json({}));
        })
      );

      const user = userEvent.setup();
      render(<AppWithConfig />);

      expect(await screen.findByText('Governance')).toBeDefined();
      await user.click(screen.getByText('Governance'));

      expect(await screen.findByText('Vote Requests')).toBeDefined();
      expect(await screen.findByText('Governance')).toBeDefined();

      await changeAction('CRARC_SetConfig');

      const summaryInput = screen.getByTestId('create-reason-summary');
      await user.type(summaryInput, 'summaryABC');

      const urlInput = screen.getByTestId('create-reason-url');
      await user.type(urlInput, 'https://vote-request.url');

      const initialSteps =
        dsoInfo.amulet_rules.contract.payload.configSchedule.initialValue.transferConfig.transferFee
          .steps;
      expect(initialSteps.length).toBe(3); // Sanity check

      // editting the second element (.1) of the transferFees
      const input = screen.getByTestId('transferConfig.transferFee.steps.1._2-value');
      await user.clear(input);
      await user.type(input, '0');

      const sendButton = screen.getByRole('button', { name: 'Send Request to Super Validators' });
      await user.click(sendButton);
      const proceedButton = screen.getByRole('button', { name: 'Proceed' });
      await user.click(proceedButton);

      const calledWithBody = await createPromise;
      expect(
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        (calledWithBody.action as any).value.amuletRulesAction.value.newConfig.transferConfig
          .transferFee.steps
        // the second element is gone
      ).toStrictEqual(initialSteps.filter((_, i) => i !== 1));
    },
    { timeout: 20000 }
  );
});
