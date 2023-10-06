import { fireEvent, render, screen, within } from '@testing-library/react';
import { test, expect } from 'vitest';

import App from '../App';

test('home screen shows up', async () => {
  render(<App />);
  const a = await screen.findByText('Canton Coin Scan');
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
  expect(screen.getByTestId('activity-table')).toBeDefined();

  const rows = await screen.findAllByRole('row');
  const firstRow = rows[1];
  expect(within(firstRow).getByText('Merge Fee Burn')).toBeDefined();
  expect(within(firstRow).getByText('0.03 CC')).toBeDefined();
  expect(within(firstRow).getByText('1 CC/USD')).toBeDefined();
});

test('recent activity looks up directory entries', async () => {
  render(<App />);
  const cnsNameElement = await screen.findByText('charlie.unverified.cns');
  expect(cnsNameElement).toBeDefined();
});
