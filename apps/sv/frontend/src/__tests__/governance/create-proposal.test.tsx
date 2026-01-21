// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { MemoryRouter } from 'react-router';
import { ThemeProvider } from '@emotion/react';
import { theme } from '../../../../../common/frontend/lib/theme';
import { CreateProposal } from '../../routes/createProposal';
import userEvent from '@testing-library/user-event';
import { Wrapper } from '../helpers';
import { createProposalActions } from '../../utils/governance';

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

  await waitFor(async () => {
    const actionToSelect = screen.getByText(actionName);
    expect(actionToSelect).toBeInTheDocument();
    await user.click(actionToSelect);
  });

  const nextButton = screen.getByText('Next');
  expect(nextButton).toBeInTheDocument();
  await user.click(nextButton);

  const actionInput = await screen.findByTestId(testId);
  const action = createProposalActions.find(a => a.value === actionValue);
  expect(actionInput.getAttribute('value')).toBe(action!.name);
}

describe('Create Proposal', () => {
  test('Display action selection and all actions', async () => {
    const user = userEvent.setup();
    render(
      <TestWrapper>
        <CreateProposal />
      </TestWrapper>
    );

    const actionSelectionTitle = screen.getByText('Select an Action');
    expect(actionSelectionTitle).toBeDefined();

    const actionDropdown = screen.getByTestId('select-action');
    expect(actionDropdown).toBeDefined();

    const selectInput = actionDropdown.querySelector('[role="combobox"]') as HTMLElement;
    user.click(selectInput);

    await waitFor(() => {
      expect(screen.getByText('Offboard Member')).toBeInTheDocument();
      expect(screen.getByText('Feature Application')).toBeInTheDocument();
      expect(screen.getByText('Unfeature Application')).toBeInTheDocument();
      expect(screen.getByText('Set Dso Rules Configuration')).toBeInTheDocument();
      expect(screen.getByText('Set Amulet Rules Configuration')).toBeInTheDocument();
      expect(screen.getByText('Update SV Reward Weight')).toBeInTheDocument();
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

  test('Set Dso Rules Configuration Form is rendered after action selection', async () => {
    await checkActionSelection(
      'Set Dso Rules Configuration',
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

  test('Update SV Reward Weight Form is rendered after action selection', async () => {
    await checkActionSelection(
      'Update SV Reward Weight',
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
    expect(nextButton.getAttribute('disabled')).toBeDefined();

    const actionDropdown = screen.getByTestId('select-action');
    expect(actionDropdown).toBeDefined();

    const selectInput = actionDropdown.querySelector('[role="combobox"]') as HTMLElement;
    user.click(selectInput);

    await waitFor(() => {
      const actionToSelect = screen.getByText('Offboard Member');
      expect(actionToSelect).toBeDefined();
      user.click(actionToSelect);
    });

    expect(nextButton.getAttribute('disabled')).toBe('');
  });
});
