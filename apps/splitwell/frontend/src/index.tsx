import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ReactQueryDevtools } from '@tanstack/react-query-devtools';
import {
  DirectoryClientProvider,
  AuthProvider,
  UserProvider,
  theme,
  cnReplaceEqualDeep,
} from 'common-frontend';
import { ScanClientProvider } from 'common-frontend/scan-api';
import React from 'react';
import ReactDOM from 'react-dom/client';
import {
  createBrowserRouter,
  createRoutesFromElements,
  Route,
  RouterProvider,
} from 'react-router-dom';

import { CssBaseline, ThemeProvider } from '@mui/material';

import { SplitwellLedgerApiClientProvider } from './contexts/SplitwellLedgerApiContext';
import { SplitwellClientProvider } from './contexts/SplitwellServiceContext';
import './index.css';
import AuthCheck from './routes/authCheck';
import Home from './routes/home';
import Root from './routes/root';
import { config } from './utils/config';

const Providers: React.FC<React.PropsWithChildren> = ({ children }) => {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        refetchInterval: 500, // re-fetch all queries every 500ms by default
        structuralSharing: cnReplaceEqualDeep,
      },
      mutations: {
        retry: (failureCount, error) =>
          // We only retry certain JSON API errors. Retrying everything is more confusing than helpful
          // because that then also retries on invalid user input.
          // The status field is defined as part of LedgerError in @daml/ledger which is thrown on JSON API errors.
          /* eslint-disable @typescript-eslint/no-explicit-any */
          [404, 409].includes((error as any).status) && failureCount < 10,
        retryDelay: 500,
      },
    },
  });

  return (
    <AuthProvider authConf={config.auth}>
      <QueryClientProvider client={queryClient}>
        <ReactQueryDevtools initialIsOpen={false} />

        <UserProvider authConf={config.auth} testAuthConf={config.testAuth} useLedgerApiTokens>
          <SplitwellClientProvider url={config.services.splitwell.url}>
            <DirectoryClientProvider url={config.services.directory.url}>
              <ScanClientProvider url={config.services.scan.url}>
                <SplitwellLedgerApiClientProvider jsonApiUrl={config.services.jsonApi.url}>
                  {children}
                </SplitwellLedgerApiClientProvider>
              </ScanClientProvider>
            </DirectoryClientProvider>
          </SplitwellClientProvider>
        </UserProvider>
      </QueryClientProvider>
    </AuthProvider>
  );
};

const router = createBrowserRouter(
  createRoutesFromElements(
    <Route
      element={
        <Providers>
          <AuthCheck authConfig={config.auth} testAuthConfig={config.testAuth} />
        </Providers>
      }
    >
      <Route path="/" element={<Root />}>
        <Route index element={<Home />} />
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
