// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { render, screen, waitFor } from '@testing-library/react';
import { beforeAll, describe, expect, test } from 'vitest';
import { MemoryRouter } from 'react-router';
import { ThemeProvider } from '@emotion/react';
import { theme } from '../../../../../common/frontend/lib/theme';
import { CreateProposal } from '../../routes/createProposal';
import userEvent from '@testing-library/user-event';
import { Wrapper } from '../helpers';
import { createProposalActions } from '../../utils/governance';
import { http, HttpResponse } from 'msw';
import { dsoInfo } from '@canton-network/splice-common-test-handlers';
import { server, svUrl } from '../setup/setup';
import { dateTimeFormatISO } from '@canton-network/splice-common-frontend-utils';
import dayjs from 'dayjs';

const TestWrapper: React.FC<React.PropsWithChildren> = ({ children }) => {
  return (
    <MemoryRouter>
      <ThemeProvider theme={theme}>{children}</ThemeProvider>
    </MemoryRouter>
  );
};

async function checkActionSelection(actionName: string, actionValue: string, testId: string) {
  const user = userEvent.setup();

  render(
    <Wrapper>
      <CreateProposal />
    </Wrapper>
  );

  const actionDropdown = screen.getByTestId('select-action');

  const selectInput = actionDropdown.querySelector('[role="combobox"]') as HTMLElement;
  await user.click(selectInput);

  const actionToSelect = await screen.findByText(actionName);
  await user.click(actionToSelect);

  const nextButton = screen.getByText('Next');
  expect(nextButton).toBeInTheDocument();
  await user.click(nextButton);

  const actionInput = await screen.findByTestId(testId);
  const action = createProposalActions.find(a => a.value === actionValue);
  expect(actionInput.textContent).toBe(action!.name);
}

// The SV app's /v1/dso endpoint requires authentication, so log in before rendering
// (matches the UserProvider's session-restore path for test auth).
beforeAll(() => {
  window.sessionStorage.setItem('canton.network.wallet.userid', 'sv1');
});

describe('Create Proposal', () => {
  test('Does not render the form while dsoInfo is pending, then lands on +7d default', async () => {
    let releaseDso!: () => void;
    const dsoReady = new Promise<void>(resolve => {
      releaseDso = resolve;
    });

    server.use(
      http.get(`${svUrl}/v1/dso`, async () => {
        await dsoReady;
        return HttpResponse.json(dsoInfo);
      })
    );

    render(
      <Wrapper initialEntries={['/?action=SRARC_OffboardSv']}>
        <CreateProposal />
      </Wrapper>
    );

    await screen.findByTestId('loading-spinner');
    expect(screen.queryByTestId('offboard-sv-expiry-date-field')).not.toBeInTheDocument();

    releaseDso();

    const expiryDateInput = await screen.findByTestId('offboard-sv-expiry-date-field');
    const expectedExpiry = dayjs().add(7, 'days');
    const actualExpiry = dayjs(expiryDateInput.getAttribute('value')!, dateTimeFormatISO);
    expect(Math.abs(actualExpiry.diff(expectedExpiry, 'minute'))).toBeLessThan(2);
  });

  test('Display action selection and all actions', async () => {
    const user = userEvent.setup();
    render(
      <TestWrapper>
        <CreateProposal />
      </TestWrapper>
    );

    const actionDropdown = screen.getByTestId('select-action');
    expect(actionDropdown).toBeDefined();

    const selectInput = actionDropdown.querySelector('[role="combobox"]') as HTMLElement;
    await user.click(selectInput);

    await waitFor(() => {
      expect(screen.getByText('Offboard Member')).toBeInTheDocument();
      expect(screen.getByText('Feature Application')).toBeInTheDocument();
      expect(screen.getByText('Unfeature Application')).toBeInTheDocument();
      expect(
        screen.getByText('Set Decentralized Synchronizer Operations (DSO) Rules Configuration')
      ).toBeInTheDocument();
      expect(screen.getByText('Set Amulet Rules Configuration')).toBeInTheDocument();
      expect(screen.getByText('Update Super Validator Reward Weight')).toBeInTheDocument();
      expect(screen.getByText('Create Unclaimed Activity Record')).toBeInTheDocument();
    });
  });

  test('Offboard SV Form is rendered after action selection', async () => {
    await checkActionSelection('Offboard Member', 'SRARC_OffboardSv', 'offboard-sv-action');
  });

  test('Feature Application Form is rendered after action selection', async () => {
    await checkActionSelection(
      'Feature Application',
      'SRARC_GrantFeaturedAppRight',
      'grant-featured-app-action'
    );
  });

  test('Unfeature Application Form is rendered after action selection', async () => {
    await checkActionSelection(
      'Unfeature Application',
      'SRARC_RevokeFeaturedAppRight',
      'revoke-featured-app-action'
    );
  });

  test('Set Decentralized Synchronizer Operations (DSO) Rules Configuration Form is rendered after action selection', async () => {
    await checkActionSelection(
      'Set Decentralized Synchronizer Operations (DSO) Rules Configuration',
      'SRARC_SetConfig',
      'set-dso-config-rules-action'
    );
  });

  test('Set Amulet Rules Configuration Form is rendered after action selection', async () => {
    await checkActionSelection(
      'Set Amulet Rules Configuration',
      'CRARC_SetConfig',
      'set-amulet-config-rules-action'
    );
  });

  test('Update Super Validator Reward Weight Form is rendered after action selection', async () => {
    await checkActionSelection(
      'Update Super Validator Reward Weight',
      'SRARC_UpdateSvRewardWeight',
      'update-sv-reward-weight-action'
    );
  });

  test('Create Unclaimed Activity Record Form is rendered after action selection', async () => {
    await checkActionSelection(
      'Create Unclaimed Activity Record',
      'SRARC_CreateUnallocatedUnclaimedActivityRecord',
      'create-unallocated-unclaimed-activity-record-action'
    );
  });

  test('Display cancel and next buttons', () => {
    render(
      <MemoryRouter>
        <ThemeProvider theme={theme}>
          <CreateProposal />
        </ThemeProvider>
      </MemoryRouter>
    );

    const cancelButton = screen.getByText('Cancel');
    expect(cancelButton).toBeDefined();

    const nextButton = screen.getByText('Next');
    expect(nextButton).toBeDefined();
  });

  test('Next button is disabled on initial render but enabled after action selection', async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <ThemeProvider theme={theme}>
          <CreateProposal />
        </ThemeProvider>
      </MemoryRouter>
    );

    const nextButton = screen.getByText('Next');
    expect(nextButton).toBeDefined();
    expect(nextButton.getAttribute('disabled')).not.toBeNull();

    const actionDropdown = screen.getByTestId('select-action');
    expect(actionDropdown).toBeDefined();

    const selectInput = actionDropdown.querySelector('[role="combobox"]') as HTMLElement;
    await user.click(selectInput);

    const actionToSelect = await screen.findByText('Offboard Member');
    await user.click(actionToSelect);

    await waitFor(() => {
      expect(nextButton.getAttribute('disabled')).toBeNull();
    });
  });
});
