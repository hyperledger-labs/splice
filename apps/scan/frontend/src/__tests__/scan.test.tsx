// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { rest } from 'msw';
import { mockAllIsIntersecting } from 'react-intersection-observer/test-utils';
import {
  GetAmuletRulesResponse,
  GetBackfillingStatusResponse,
  GetRoundOfLatestDataResponse,
} from '@lfdecentralizedtrust/scan-openapi';
import { test, expect } from 'vitest';

import App from '../App';
import { ScanConfigProvider } from '../utils';
import { config } from './setup/config';
import { server } from './setup/setup';
import { amuletRules, getAmuletRulesResponse } from './mocks/data';
import BigNumber from 'bignumber.js';

const spliceInstanceNames = config.spliceInstanceNames;
const scanUrl = window.splice_config.services.scan.url + '/api/scan';

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

test('round of latest data is displayed', async () => {
  server.use(
    rest.get(`${scanUrl}/v0/round-of-latest-data`, (_, res, ctx) => {
      return res(ctx.json<GetRoundOfLatestDataResponse>({ round: 0, effectiveAt: new Date() }));
    })
  );

  render(<AppWithConfig />);

  const roundOfLatestDataText = await screen.findByTestId('round-of-latest-data-text');
  const roundOfLatestDataValue = await screen.findByTestId('round-of-latest-data-value');

  expect(roundOfLatestDataText.textContent).toMatch(
    /The content on this page is computed as of round:/
  );
  expect(roundOfLatestDataValue.textContent).toBe('0');
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

  mockAllIsIntersecting(true);
  expect(await screen.findByDisplayValue('validator::15')).toBeDefined();
});

test('backfilling indicator shows when backfilling', async () => {
  server.use(
    rest.get(`${scanUrl}/v0/backfilling/status`, (_, res, ctx) => {
      return res(
        ctx.json<GetBackfillingStatusResponse>({
          complete: false,
        })
      );
    })
  );
  render(<AppWithConfig />);
  const backfillingIndicator = screen.queryByTestId('backfilling-alert');
  expect(backfillingIndicator).toBeDefined();
});

test('backfilling indicator does not shows when not backfilling', async () => {
  server.use(
    rest.get(`${scanUrl}/v0/backfilling/status`, (_, res, ctx) => {
      return res(
        ctx.json<GetBackfillingStatusResponse>({
          complete: true,
        })
      );
    })
  );
  render(<AppWithConfig />);
  const backfillingIndicator = screen.queryByTestId('backfilling-alert');
  expect(backfillingIndicator).toBeNull();
});

test('backfilling indicator does not shows when response is unclear', async () => {
  server.use(
    rest.get(`${scanUrl}/v0/backfilling/status`, (_, res, ctx) => {
      return res(ctx.status(503, 'Internal Server Error'));
    })
  );
  render(<AppWithConfig />);
  const backfillingIndicator = screen.queryByTestId('backfilling-alert');
  expect(backfillingIndicator).toBeNull();
});

// the server handler returns no fees by default
test('Fees with value 0 are not shown', async () => {
  const user = userEvent.setup();
  const amuletConfig = amuletRules(true).configSchedule.initialValue;
  render(<AppWithConfig />);
  await user.click(screen.getByText(`${spliceInstanceNames.amuletName} Activity`));

  expect(await screen.findByText('Synchronizer Fee')).toBeDefined();
  expect(
    await screen.findByText(
      `${BigNumber(amuletConfig.decentralizedSynchronizer.fees.extraTrafficPrice)} $/MB`
    )
  ).toBeDefined();
  expect(await screen.findByText('Holding Fee')).toBeDefined();
  expect(
    await screen.findByText(`${BigNumber(amuletConfig.transferConfig.holdingFee.rate)} USD/Round`)
  ).toBeDefined();

  expect(screen.queryByText('Base Transfer Fee')).toBeNull();
  expect(screen.queryByText('Transfer Fee')).toBeNull();
  expect(screen.queryByText('Lock Holder Fee')).toBeNull();
});

test('Fees with value greater than 0 are shown', async () => {
  const amuletConfig = amuletRules(false).configSchedule.initialValue;
  server.use(
    rest.post(`${scanUrl}/v0/amulet-rules`, (_, res, ctx) => {
      return res(ctx.json<GetAmuletRulesResponse>(getAmuletRulesResponse(false)));
    })
  );
  const user = userEvent.setup();
  render(<AppWithConfig />);
  await user.click(screen.getByText(`${spliceInstanceNames.amuletName} Activity`));

  expect(await screen.findByText('Synchronizer Fee')).toBeDefined();
  expect(await screen.findByText('Holding Fee')).toBeDefined();
  expect(await screen.findByText('Base Transfer Fee')).toBeDefined();
  expect(
    await screen.findByText(`${BigNumber(amuletConfig.transferConfig.createFee.fee)} USD`)
  ).toBeDefined();
  expect(await screen.findByText('Transfer Fee')).toBeDefined();
  expect(await screen.findByText('Lock Holder Fee')).toBeDefined();
  expect(
    await screen.findByText(`${BigNumber(amuletConfig.transferConfig.lockHolderFee.fee)} USD`)
  ).toBeDefined();
});
