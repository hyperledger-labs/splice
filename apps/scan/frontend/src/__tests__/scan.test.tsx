// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { test, expect } from 'vitest';

import App from '../App';
import { ScanConfigProvider } from '../utils';
import { config } from './setup/config';

const spliceInstanceNames = config.spliceInstanceNames;

const AppWithConfig: React.FC = () => {
  return (
    <ScanConfigProvider>
      <App />
    </ScanConfigProvider>
  );
};

test('home screen shows up', async () => {
  render(<AppWithConfig />);
  const appName = await screen.findByText(`${spliceInstanceNames.amuletName} Scan`);

  expect(appName).toBeDefined();
});

test('total circulating amulet balance is displayed', async () => {
  render(<AppWithConfig />);

  const circulatingSupplyTitle = await screen.findByRole('heading', {
    name: /TOTAL CIRCULATING [A-Z]+/i,
    level: 5,
  });

  const circulatingSupplyContainer = await screen.findByTestId('circulating-supply-container');

  const amuletSupply = within(circulatingSupplyContainer).getByTestId('amulet-circulating-supply');
  const usdSupply = within(circulatingSupplyContainer).getByTestId('usd-circulating-supply');

  expect(circulatingSupplyTitle).toBeDefined();
  expect(amuletSupply.toString()).toMatch(/\d+\d+ TLM/i);
  expect(usdSupply.toString()).toMatch(/\d+\d+ USD/i);
});

test('recent activity link from tab opens a tab', async () => {
  render(<AppWithConfig />);
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

test('validator licenses are displayed and paginable', async () => {
  const user = userEvent.setup();
  render(<AppWithConfig />);
  await user.click(screen.getByText('Validators'));

  expect(await screen.findByText('Validator Licenses')).toBeDefined();

  expect(await screen.findByDisplayValue('validator::1')).toBeDefined();
  expect(screen.queryByText('validator::15')).toBeNull();

  expect(await screen.findByText('View More')).toBeDefined();
  await user.click(screen.getByText('View More'));

  expect(await screen.findByDisplayValue('validator::15')).toBeDefined();
});
