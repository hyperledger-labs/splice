// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, test } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { ThemeProvider } from '@emotion/react';
import { theme } from '../../../../../common/frontend/lib/theme';
import { CreateProposal } from '../../routes/createProposal';
import userEvent from '@testing-library/user-event';

const TestWrapper: React.FC<React.PropsWithChildren> = ({ children }) => {
  return (
    <MemoryRouter>
      <ThemeProvider theme={theme}>{children}</ThemeProvider>
    </MemoryRouter>
  );
};

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
      expect(screen.getByText('Offboard Member')).toBeTruthy();
      expect(screen.getByText('Feature Application')).toBeTruthy();
      expect(screen.getByText('Unfeature Application')).toBeTruthy();
      expect(screen.getByText('Set Dso Rules Configuration')).toBeTruthy();
      expect(screen.getByText('Set Amulet Rules Configuration')).toBeTruthy();
      expect(screen.getByText('Update SV Reward Weight')).toBeTruthy();
    });
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
