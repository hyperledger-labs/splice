// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { screen, fireEvent } from '@testing-library/react';
import { expect } from 'vitest';

export function changeAction(actionName: string = 'SRARC_SetConfig'): void {
  const dropdown = screen.getByTestId('display-actions');
  expect(dropdown).toBeDefined();
  fireEvent.change(dropdown!, { target: { value: actionName } });

  const actionChangeDialog = screen.getByTestId('action-change-dialog');
  expect(actionChangeDialog).toBeDefined();
  const actionChangeDialogProceed = screen.getByTestId('action-change-dialog-proceed');
  expect(actionChangeDialogProceed).toBeDefined();
  fireEvent.click(actionChangeDialogProceed);
}
