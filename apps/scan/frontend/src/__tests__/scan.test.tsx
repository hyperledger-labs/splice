import { fireEvent, render, screen } from '@testing-library/react';
import { test, expect } from 'vitest';

import App from '../App';

render(<App />);

test('home screen shows up', async () => {
  const a = await screen.findByText('Canton Coin Scan');
  expect(a).toBeDefined();
});

test('recent activity link from tab opens a tab', async () => {
  const appLeaderboardLink = screen.getByRole('tab', { name: 'App Leaderboard' });
  fireEvent.click(appLeaderboardLink);
  expect(screen.queryAllByTestId('activity-table')).toHaveLength(0);

  const recentActivityLink = screen.getByRole('tab', { name: 'Recent Activity' });
  const href = recentActivityLink.getAttribute('href');
  expect(href?.endsWith('recent-activity')).toBe(true);

  fireEvent.click(recentActivityLink);
  expect(screen.getByTestId('activity-table')).toBeDefined();
});
