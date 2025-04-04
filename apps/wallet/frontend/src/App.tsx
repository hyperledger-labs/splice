// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import {
  AuthProvider,
  ErrorRouterPage,
  theme,
  UserProvider,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { replaceEqualDeep } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ReactQueryDevtools } from '@tanstack/react-query-devtools';
import { Helmet, HelmetProvider } from 'react-helmet-async';
import {
  createBrowserRouter,
  createRoutesFromElements,
  Route,
  RouterProvider,
  useNavigate,
} from 'react-router-dom';

import { CssBaseline, ThemeProvider } from '@mui/material';

import { CurrentUserProvider } from './contexts/CurrentUserContext';
import { ValidatorScanProxyClientProvider } from './contexts/ValidatorScanProxyContext';
import { ValidatorClientProvider } from './contexts/ValidatorServiceContext';
import { WalletClientProvider } from './contexts/WalletServiceContext';
import AuthCheck from './routes/authCheck';
import ConfirmPayment from './routes/confirmPayment';
import ConfirmSubscription from './routes/confirmSubscription';
import Confirmation from './routes/confirmation';
import Faqs from './routes/faqs';
import Root from './routes/root';
import Subscriptions from './routes/subscriptions';
import Transactions from './routes/transactions';
import Transfer from './routes/transfer';
import { useConfigPollInterval, useWalletConfig } from './utils/config';

const App: React.FC = () => {
  const config = useWalletConfig();
  const refetchInterval = useConfigPollInterval();

  const Providers: React.FC<React.PropsWithChildren> = ({ children }) => {
    const config = useWalletConfig();
    const navigate = useNavigate();
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: {
          refetchInterval,
          structuralSharing: replaceEqualDeep,
        },
      },
      logger: {
        log: () => {},
        error: () => {},
        warn: () => {},
      },
    });

    return (
      <AuthProvider authConf={config.auth} redirect={(path: string) => navigate(path)}>
        <QueryClientProvider client={queryClient}>
          <ReactQueryDevtools initialIsOpen={false} />
          <UserProvider authConf={config.auth} testAuthConf={config.testAuth}>
            <ValidatorClientProvider url={config.services.validator.url}>
              <WalletClientProvider url={config.services.validator.url}>
                <ValidatorScanProxyClientProvider validatorUrl={config.services.validator.url}>
                  <CurrentUserProvider>{children}</CurrentUserProvider>
                </ValidatorScanProxyClientProvider>
              </WalletClientProvider>
            </ValidatorClientProvider>
          </UserProvider>
        </QueryClientProvider>
      </AuthProvider>
    );
  };
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
          <Route index element={<Transactions />} />
          <Route path="transactions" element={<Transactions />} />
          <Route path="transfer" element={<Transfer />} />
          <Route path="subscriptions" element={<Subscriptions />} />
          <Route path="faqs" element={<Faqs />} />
        </Route>
        <Route element={<Confirmation />}>
          <Route path="confirm-payment/:cid/" element={<ConfirmPayment />} />
          <Route path="confirm-subscription/:cid/" element={<ConfirmSubscription />} />
        </Route>
      </Route>
    )
  );
  const pageTitle = `${config.spliceInstanceNames.networkName} Wallet Application`;
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
