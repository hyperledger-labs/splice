// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  AuthProvider,
  theme,
  UserProvider,
  ErrorRouterPage,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { replaceEqualDeep } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ReactQueryDevtools } from '@tanstack/react-query-devtools';
import React from 'react';
import { Helmet, HelmetProvider } from 'react-helmet-async';
import { createBrowserRouter, createRoutesFromElements, Route, RouterProvider } from 'react-router';

import { CssBaseline, ThemeProvider } from '@mui/material';

import { ExternalAnsClientProvider } from './context/AnsServiceContext';
import { ValidatorScanProxyClientProvider } from './context/ValidatorScanProxyContext';
import { WalletClientProvider } from './context/WalletServiceContext';
import AuthCheck from './routes/authCheck';
import Home from './routes/home';
import PostPayment from './routes/postPayment';
import Root from './routes/root';
import { useAnsConfig, useConfigPollInterval } from './utils';

const Providers: React.FC<React.PropsWithChildren> = ({ children }) => {
  const config = useAnsConfig();
  const refetchInterval = useConfigPollInterval();

  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        refetchInterval,
        structuralSharing: replaceEqualDeep,
      },
    },
  });

  return (
    <AuthProvider authConf={config.auth}>
      <QueryClientProvider client={queryClient}>
        <ReactQueryDevtools initialIsOpen={false} />
        <UserProvider authConf={config.auth} testAuthConf={config.testAuth}>
          <WalletClientProvider url={config.services.validator.url}>
            <ValidatorScanProxyClientProvider validatorUrl={config.services.validator.url}>
              <ExternalAnsClientProvider url={config.services.validator.url}>
                {children}
              </ExternalAnsClientProvider>
            </ValidatorScanProxyClientProvider>
          </WalletClientProvider>
        </UserProvider>
      </QueryClientProvider>
    </AuthProvider>
  );
};

const App: React.FC = () => {
  const config = useAnsConfig();
  const router = createBrowserRouter(
    createRoutesFromElements(
      <Route
        errorElement={<ErrorRouterPage />}
        element={
          <Providers>
            <AuthCheck authConfig={config.auth} testAuthConfig={config.testAuth} />
          </Providers>
        }
      >
        <Route path="/" element={<Root />}>
          <Route index element={<Home />} />
          <Route path="home" element={<Home />} />
          <Route path="post-payment" element={<PostPayment />} />
        </Route>
      </Route>
    )
  );
  const pageTitle = config.spliceInstanceNames.nameServiceName;
  return (
    <ThemeProvider theme={theme}>
      <HelmetProvider>
        <Helmet>
          <title>{pageTitle}</title>
          <meta name="description" content={pageTitle} />
          <link rel="icon" href={config.spliceInstanceNames.networkFaviconUrl} />
        </Helmet>
        <CssBaseline />
        <RouterProvider router={router} />
      </HelmetProvider>
    </ThemeProvider>
  );
};

export default App;
