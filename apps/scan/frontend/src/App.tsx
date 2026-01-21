// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ErrorRouterPage, theme } from '@lfdecentralizedtrust/splice-common-frontend';
import { replaceEqualDeep } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { ScanClientProvider } from '@lfdecentralizedtrust/splice-common-frontend/scan-api';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ReactQueryDevtools } from '@tanstack/react-query-devtools';
import React from 'react';
import { Helmet, HelmetProvider } from 'react-helmet-async';
import { createBrowserRouter, createRoutesFromElements, Route, RouterProvider } from 'react-router';

import { CssBaseline } from '@mui/material';
import { ThemeProvider } from '@mui/material';

import ScanListVoteRequests from './components/votes/ScanListVoteRequests';
import { ScanAppVotesHooksProvider } from './contexts/ScanAppVotesHooksProvider';
import Activity from './routes/activity';
import AmuletPriceVotes from './routes/amuletPriceVotes';
import AppLeaderboard from './routes/appLeaderboard';
import SynchronizerFeesLeaderboard from './routes/domainFeesLeaderboard';
import DsoWithContexts from './routes/dso';
import Root from './routes/root';
import ScanValidatorLicenses from './routes/scanValidatorLicenses';
import ValidatorFaucetsLeaderboard from './routes/validatorFaucetsLeaderboard';
import ValidatorLeaderboard from './routes/validatorLeaderboard';
import { useConfigPollInterval, useScanConfig } from './utils';
import { TokenMetadataClientProvider } from './api/TokenMetadataClientContext';

const Providers: React.FC<React.PropsWithChildren> = ({ children }) => {
  const config = useScanConfig();
  const refetchInterval = useConfigPollInterval();

  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        // rounds update every 2.5 minutes, but for testing it's better to refresh more often, e.g. every 5 seconds
        refetchInterval,
        structuralSharing: replaceEqualDeep,
      },
    },
  });

  return (
    <ScanClientProvider baseScanUrl={config.services.scan.url}>
      <TokenMetadataClientProvider scanUrl={config.services.scan.url}>
        <QueryClientProvider client={queryClient}>
          <ReactQueryDevtools initialIsOpen={false} />
          <ScanAppVotesHooksProvider>{children}</ScanAppVotesHooksProvider>
        </QueryClientProvider>
      </TokenMetadataClientProvider>
    </ScanClientProvider>
  );
};

const router = createBrowserRouter(
  createRoutesFromElements(
    <Route errorElement={<ErrorRouterPage />}>
      <Route path="/" element={<Root />}>
        <Route index element={<Activity />} />
        <Route path="recent-activity" element={<Activity />} />
        <Route path="app-leaderboard" element={<AppLeaderboard />} />
        <Route path="validator-leaderboard" element={<ValidatorLeaderboard />} />
        <Route path="synchronizer-fees-leaderboard" element={<SynchronizerFeesLeaderboard />} />
        <Route path="validator-faucets-leaderboard" element={<ValidatorFaucetsLeaderboard />} />
      </Route>
      <Route path="/amulet-price-votes" element={<AmuletPriceVotes />} />
      <Route path="/dso" element={<DsoWithContexts />} />
      <Route path="/governance" element={<ScanListVoteRequests />} />
      <Route path="/validator-licenses" element={<ScanValidatorLicenses />} />
    </Route>
  )
);

const App: React.FC = () => {
  const config = useScanConfig();
  const pageTitle = `${config.spliceInstanceNames.amuletName} Scan`;
  return (
    <ThemeProvider theme={theme}>
      <HelmetProvider>
        <Helmet>
          <title>{pageTitle}</title>
          <meta name="description" content={pageTitle} />
          <link rel="icon" href={config.spliceInstanceNames.networkFaviconUrl} />
        </Helmet>
        <CssBaseline />
        <Providers>
          <RouterProvider router={router} />
        </Providers>
      </HelmetProvider>
    </ThemeProvider>
  );
};

export default App;
