// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { fireEvent, render, screen, within } from '@testing-library/react';
import { test, expect } from 'vitest';

import App from '../App';
import { config } from './setup/config';

test('home screen shows up', async () => {
  render(<App />);
  const amuletName = config.spliceInstanceNames.amuletName;
  const a = await screen.findByText(`${amuletName} Scan`);
  expect(a).toBeDefined();
});

test('recent activity link from tab opens a tab', async () => {
  render(<App />);
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
  expect(within(firstRow).getByText('0.03 CC')).toBeDefined();
  expect(within(firstRow).getByText('1 CC/USD')).toBeDefined();
});

test('recent activity looks up CNS entries', async () => {
  render(<App />);
  const ansNameElement = await screen.findByText('charlie.unverified.cns');
  expect(ansNameElement).toBeDefined();
});
