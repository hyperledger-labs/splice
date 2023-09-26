import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ReactQueryDevtools } from '@tanstack/react-query-devtools';
import { cnReplaceEqualDeep, theme } from 'common-frontend';
import { ScanClientProvider } from 'common-frontend/scan-api';
import React from 'react';
import {
  createBrowserRouter,
  createRoutesFromElements,
  Route,
  RouterProvider,
} from 'react-router-dom';

import { CssBaseline } from '@mui/material';
import { ThemeProvider } from '@mui/material';

import Activity from './routes/activity';
import AppLeaderboard from './routes/appLeaderboard';
import DomainFeesLeaderboard from './routes/domainFeesLeaderboard';
import Root from './routes/root';
import ValidatorLeaderboard from './routes/validatorLeaderboard';
import { config } from './utils';

const Providers: React.FC<React.PropsWithChildren> = ({ children }) => {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        // rounds update every 2.5 minutes, but for testing it's better to refresh more often, e.g. every 5 seconds
        refetchInterval: 5 * 1000,
        structuralSharing: cnReplaceEqualDeep,
      },
    },
  });

  return (
    <QueryClientProvider client={queryClient}>
      <ReactQueryDevtools initialIsOpen={false} />
      <ScanClientProvider url={config.services.scan.url}>{children}</ScanClientProvider>
    </QueryClientProvider>
  );
};

const router = createBrowserRouter(
  createRoutesFromElements(
    <Route>
      <Route path="/" element={<Root />}>
        <Route index element={<Activity />} />
        <Route path="recent-activity" element={<Activity />} />
        <Route path="app-leaderboard" element={<AppLeaderboard />} />
        <Route path="validator-leaderboard" element={<ValidatorLeaderboard />} />
        <Route path="domain-fees-leaderboard" element={<DomainFeesLeaderboard />} />
      </Route>
    </Route>
  )
);

const App: React.FC = () => (
  <ThemeProvider theme={theme}>
    <CssBaseline />
    <Providers>
      <RouterProvider router={router} />
    </Providers>
  </ThemeProvider>
);

export default App;
