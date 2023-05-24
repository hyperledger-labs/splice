import { theme } from 'common-frontend';
import React from 'react';
import ReactDOM from 'react-dom/client';
import {
  createBrowserRouter,
  createRoutesFromElements,
  Route,
  RouterProvider,
} from 'react-router-dom';

import { CssBaseline } from '@mui/material';
import { ThemeProvider } from '@mui/material';

import AppLeaderboard from './routes/appLeaderboard';
import DomainFeesLeaderboard from './routes/domainFeesLeaderboard';
import RecentActivity from './routes/recentActivity';
import Root from './routes/root';
import ValidatorLeaderboard from './routes/validatorLeaderboard';

const router = createBrowserRouter(
  createRoutesFromElements(
    <Route>
      <Route path="/" element={<Root />}>
        <Route index element={<RecentActivity />} />
        <Route path="recent-activity" element={<RecentActivity />} />
        <Route path="app-leaderboard" element={<AppLeaderboard />} />
        <Route path="validator-leaderboard" element={<ValidatorLeaderboard />} />
        <Route path="domain-fees-leaderboard" element={<DomainFeesLeaderboard />} />
      </Route>
    </Route>
  )
);

ReactDOM.createRoot(document.getElementById('root') as HTMLElement).render(
  <React.StrictMode>
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <RouterProvider router={router} />
    </ThemeProvider>
  </React.StrictMode>
);
