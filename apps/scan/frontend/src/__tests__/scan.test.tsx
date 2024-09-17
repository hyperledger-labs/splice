// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { fireEvent, render, screen, within } from '@testing-library/react';
import { test, expect } from 'vitest';

import App from '../App';
import { ScanConfigProvider } from '../utils';
import { config } from './setup/config';

const spliceInstanceNames = config.spliceInstanceNames;

test('home screen shows up', async () => {
  render(
    <ScanConfigProvider>
      <App />
    </ScanConfigProvider>
  );
  const a = await screen.findByText(`${spliceInstanceNames.amuletName} Scan`);
  expect(a).toBeDefined();
});

test('recent activity link from tab opens a tab', async () => {
  render(
    <ScanConfigProvider>
      <App />
    </ScanConfigProvider>
  );
  const appLeaderboardLink = screen.getByRole('tab', { name: 'App Leaderboard' });
  fireEvent.click(appLeaderboardLink);
  expect(screen.queryAllByTestId('activity-table')).toHaveLength(0);

  const recentActivityLink = screen.getByRole('tab', { name: 'Recent Activity' });
  const href = recentActivityLink.getAttribute('href');
  expect(href?.endsWith('recent-activity')).toBe(true);

  fireEvent.click(recentActivityLink);
  const table = screen.getByTestId('activity-table');
  expect(table).toBeDefined();

  const rows = await within(table).findAllByRole('row');
  const firstRow = rows[1];
  expect(within(firstRow).getByText('Automation')).toBeDefined();
  expect(within(firstRow).getByText(`0.03 ${spliceInstanceNames.amuletNameAcronym}`)).toBeDefined();
  expect(
    within(firstRow).getByText(`1 ${spliceInstanceNames.amuletNameAcronym}/USD`)
  ).toBeDefined();
});

test('recent activity looks up entries', async () => {
  render(
    <ScanConfigProvider>
      <App />
    </ScanConfigProvider>
  );
  const ansNameElement = await screen.findByText(
    `charlie.unverified.${spliceInstanceNames.nameServiceNameAcronym.toLowerCase()}`
  );
  expect(ansNameElement).toBeDefined();
});
