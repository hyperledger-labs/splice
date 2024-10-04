// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { screen } from '@testing-library/react';
import { expect } from 'vitest';

export function checkAmuletRulesExpectedConfigDiffsHTML(
  mockHtmlContent: string,
  expectedNumberOfInFlightDiffs: number = 0 // useful when we unfold the diffs
): void {
  const htmlContents = screen.getAllByTestId('config-diffs-display');
  if (expectedNumberOfInFlightDiffs > 0) {
    expect(screen.getAllByTestId('folded-accordion')).toHaveLength(expectedNumberOfInFlightDiffs);
  } else {
    expect(screen.queryByTestId('folded-accordion')).toBeNull();
  }
  expect(htmlContents[0].innerHTML).toBe(mockHtmlContent);
}

export function checkDsoRulesExpectedConfigDiffsHTML(
  mockHtmlContent: string,
  expectedNumberOfInFlightDiffs: number = 0,
  stringified: boolean = false
): void {
  const htmlContents = stringified
    ? screen.getAllByTestId('stringify-display')
    : screen.getAllByTestId('config-diffs-display');
  if (expectedNumberOfInFlightDiffs > 0) {
    expect(screen.getAllByTestId('folded-accordion')).toHaveLength(expectedNumberOfInFlightDiffs);
  } else {
    expect(screen.queryByTestId('folded-accordion')).toBeNull();
  }
  expect(htmlContents[0].innerHTML).toBe(mockHtmlContent);
}
