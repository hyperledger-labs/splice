// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { getDsoSetConfigAction } from '@lfdecentralizedtrust/splice-common-test-handlers';
import type { ListDsoRulesVoteRequestsResponse } from '@lfdecentralizedtrust/sv-openapi';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { rest } from 'msw';
import { describe, expect, test } from 'vitest';
import App from '../../../App';
import { SetDsoConfigRulesForm } from '../../../components/forms/SetDsoConfigRulesForm';
import { SvConfigProvider } from '../../../utils';
import { Wrapper } from '../../helpers';
import { svPartyId } from '../../mocks/constants';
import { server, svUrl } from '../../setup/setup';
import dayjs from 'dayjs';
import { SetAmuletConfigRulesForm } from '../../../components/forms/SetAmuletConfigRulesForm';

const today = dayjs();
const proposals: ListDsoRulesVoteRequestsResponse = {
  dso_rules_vote_requests: [
    {
      template_id: '012345abce:Splice.DsoRules:VoteRequest',
      contract_id: '0123456789abcdefg',
      payload: {
        dso: 'DSO::12345',
        votes: [],
        targetEffectiveAt: today.add(4, 'day').toISOString(),
        voteBefore: today.add(2, 'day').toISOString(),
        requester: 'Digital-Asset-2',
        reason: {
          url: 'http://example.com',
          body: 'a summary',
        },
        trackingCid: null,
        //change only acs commitment interval in this proposal
        action: getDsoSetConfigAction({ new: '2100', base: '1600' }),
      },
      created_event_blob: '',
      created_at: '2025-09-19T10:28:09.304591Z',
    },
    {
      template_id: '12345abce:Splice.DsoRules:VoteRequest',
      contract_id: '123456789abcdefg',
      payload: {
        dso: 'DSO::12345',
        votes: [],
        voteBefore: today.add(2, 'day').toISOString(),
        requester: 'Digital-Asset-2',
        reason: {
          url: 'http://example.com',
          body: 'a summary',
        },
        trackingCid: null,
        // keep acs commitment interval but update traffic threshold
        action: getDsoSetConfigAction({ new: '2100', base: '2100' }, { new: '100', base: '99' }),
      },
      created_event_blob: '',
      created_at: '2025-09-19T10:28:09.304591Z',
    },
  ],
};

describe('DSO Pending Fields', () => {
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
    await user.click(button);

    expect(await screen.findAllByDisplayValue(svPartyId)).toBeDefined();
  });
});

describe('Pending Fields', () => {
  test('DSO Pending fields should be disabled and pending info displayed', async () => {
    server.use(
      rest.get(`${svUrl}/v0/admin/sv/voterequests`, (_, res, ctx) => {
        return res(ctx.json<ListDsoRulesVoteRequestsResponse>(proposals));
      })
    );

    render(
      <Wrapper>
        <SetDsoConfigRulesForm />
      </Wrapper>
    );

    await waitFor(
      () => {
        expect(
          screen.queryByText('Some fields are disabled for editing due to pending votes.')
        ).not.toBeNull();
      },
      { timeout: 5000 }
    );

    const pendingLabels = screen.queryAllByTestId(/^config-pending-value-/);
    expect(pendingLabels.length).toBe(2);

    const acsPendingFieldInput = await screen.findByTestId(
      'config-field-decentralizedSynchronizerAcsCommitmentReconciliationInterval1'
    );
    expect(acsPendingFieldInput).toBeDisabled();

    const acsPendingValueDisplay = await screen.findByTestId(
      'config-pending-value-decentralizedSynchronizerAcsCommitmentReconciliationInterval1'
    );
    expect(acsPendingValueDisplay).toBeDefined();
    expect(acsPendingValueDisplay).toHaveTextContent('Pending Configuration: 2100');
    expect(acsPendingValueDisplay).toHaveTextContent(
      /This pending configuration will go into effect in 4 days/
    );

    const trafficThresholdPendingFieldInput = await screen.findByTestId(
      'config-field-numMemberTrafficContractsThreshold'
    );
    expect(trafficThresholdPendingFieldInput).toBeDisabled();

    const trafficThresholdPendingValueDisplay = await screen.findByTestId(
      'config-pending-value-numMemberTrafficContractsThreshold'
    );
    expect(trafficThresholdPendingValueDisplay).toBeDefined();
    expect(trafficThresholdPendingValueDisplay).toHaveTextContent('Pending Configuration: 100');
    expect(trafficThresholdPendingValueDisplay).toHaveTextContent(
      /This pending configuration will go into effect at Threshold/
    );
  });

  test('Amulet Pending fields validation', async () => {
    render(
      <Wrapper>
        <SetAmuletConfigRulesForm />
      </Wrapper>
    );

    await waitFor(
      () => {
        expect(
          screen.queryByText('Some fields are disabled for editing due to pending votes.')
        ).not.toBeNull();
      },
      { timeout: 5000 }
    );

    const pendingLabels = screen.queryAllByTestId(/^config-pending-value-/);
    expect(pendingLabels.length).toBe(7);
  });
});
