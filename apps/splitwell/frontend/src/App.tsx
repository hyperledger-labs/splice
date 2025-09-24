// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  AuthProvider,
  UserProvider,
  theme,
  isDomainConnectionError,
  PackageIdResolver,
  JsonApiError,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { replaceEqualDeep } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { ScanClientProvider } from '@lfdecentralizedtrust/splice-common-frontend/scan-api';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ReactQueryDevtools } from '@tanstack/react-query-devtools';
import React from 'react';
import { Helmet, HelmetProvider } from 'react-helmet-async';
import {
  createBrowserRouter,
  createRoutesFromElements,
  Route,
  RouterProvider,
} from 'react-router-dom';

import { CssBaseline, ThemeProvider } from '@mui/material';

import * as splitwell from '@daml.js/splitwell/lib/Splice/Splitwell';

import { SplitwellLedgerApiClientProvider } from './contexts/SplitwellLedgerApiContext';
import { SplitwellClientProvider } from './contexts/SplitwellServiceContext';
import './index.css';
import AuthCheck from './routes/authCheck';
import Home from './routes/home';
import Root from './routes/root';
import { useConfig, useConfigPollInterval } from './utils/config';

// We only support splitwell upgrades in the backend.
class SplitwellPackageIdResolver extends PackageIdResolver {
  async resolveTemplateId(templateId: string): Promise<string> {
    switch (this.getQualifiedName(templateId)) {
      case 'Splice.Splitwell:SplitwellRules': {
        return splitwell.SplitwellRules.templateId;
      }
      default: {
        throw new Error(`Unknown temmplate id: ${templateId}`);
      }
    }
  }
}

const Providers: React.FC<React.PropsWithChildren> = ({ children }) => {
  const refetchInterval = useConfigPollInterval();

  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        refetchInterval,
        structuralSharing: replaceEqualDeep,
      },
      mutations: {
        retry: (failureCount, error) => {
          // We only retry certain JSON API errors. Retrying everything is more confusing than helpful
          // because that then also retries on invalid user input.
          const errResponse = error as JsonApiError;
          const is404or409 = [404, 409].includes(errResponse.status);

          return (is404or409 || isDomainConnectionError(error)) && failureCount < 10;
        },
        // Exponential backoff up to a maximum of 30 seconds
        retryDelay: attemptIndex => Math.min(1000 * 1.5 ** attemptIndex, 30000),
      },
    },
  });

  const config = useConfig();

  return (
    <HelmetProvider>
      <Helmet>
        <title>Splitwell Sample Application</title>
        <meta name="description" content="Splitwell Sample Application" />
        <link rel="icon" href={config.spliceInstanceNames.networkFaviconUrl} />
      </Helmet>
      <AuthProvider authConf={config.auth}>
        <ScanClientProvider baseScanUrl={config.services.scan.url}>
          <QueryClientProvider client={queryClient}>
            <ReactQueryDevtools initialIsOpen={false} />
            <UserProvider authConf={config.auth} testAuthConf={config.testAuth} useLedgerApiTokens>
              <SplitwellClientProvider url={config.services.splitwell.url}>
                <SplitwellLedgerApiClientProvider
                  jsonApiUrl={config.services.jsonApi.url}
                  packageIdResolver={new SplitwellPackageIdResolver()}
                >
                  {children}
                </SplitwellLedgerApiClientProvider>
              </SplitwellClientProvider>
            </UserProvider>
          </QueryClientProvider>
        </ScanClientProvider>
      </AuthProvider>
    </HelmetProvider>
  );
};

const SplitwellAuthCheck: React.FC = () => {
  const config = useConfig();
  return <AuthCheck authConfig={config.auth} testAuthConfig={config.testAuth} />;
};

const router = createBrowserRouter(
  createRoutesFromElements(
    <Route
      element={
        <Providers>
          <SplitwellAuthCheck />
        </Providers>
      }
    >
      <Route path="/" element={<Root />}>
        <Route index element={<Home />} />
      </Route>
    </Route>
  )
);

const App: React.FC = () => (
  <ThemeProvider theme={theme}>
    <CssBaseline />
    <RouterProvider router={router} />
  </ThemeProvider>
);

export default App;
