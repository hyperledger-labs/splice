// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { screen } from '@testing-library/react';
import { expect } from 'vitest';
import format from 'html-format';

export async function checkAmuletRulesExpectedConfigDiffsHTML(
  mockHtmlContent: string,
  expectedNumberOfInFlightDiffs: number = 0 // useful when we unfold the diffs
): Promise<void> {
  const htmlContents = await screen.findAllByTestId('config-diffs-display');
  if (expectedNumberOfInFlightDiffs > 0) {
    expect(await screen.findAllByTestId('folded-accordion')).toHaveLength(
      expectedNumberOfInFlightDiffs
    );
  } else {
    expect(screen.queryByTestId('folded-accordion')).toBeNull();
  }
  const formattedMockHtml = format(mockHtmlContent).trim();
  const formattedActualHtml = format(htmlContents[0].innerHTML).trim();

  expect(formattedActualHtml).toBe(formattedMockHtml);
}

export async function checkDsoRulesExpectedConfigDiffsHTML(
  mockHtmlContent: string,
  expectedNumberOfInFlightDiffs: number = 0,
  stringified: boolean = false
): Promise<void> {
  const htmlContents = stringified
    ? await screen.findAllByTestId('stringify-display')
    : await screen.findAllByTestId('config-diffs-display');
  if (expectedNumberOfInFlightDiffs > 0) {
    expect(await screen.findAllByTestId('folded-accordion')).toHaveLength(
      expectedNumberOfInFlightDiffs
    );
  } else {
    expect(screen.queryByTestId('folded-accordion')).toBeNull();
  }

  const formattedMockHtml = format(mockHtmlContent).trim();
  const formattedActualHtml = format(htmlContents[0].innerHTML).trim();

  expect(formattedActualHtml).toBe(formattedMockHtml);
}
