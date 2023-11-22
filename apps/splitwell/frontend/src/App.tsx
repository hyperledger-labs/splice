import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ReactQueryDevtools } from '@tanstack/react-query-devtools';
import {
  AuthProvider,
  UserProvider,
  theme,
  cnReplaceEqualDeep,
  useUserState,
  ScanClientProvider as OldScanClientProvider,
  PackageIdResolver,
} from 'common-frontend';
import { ScanClientProvider } from 'common-frontend/scan-api';
import React, { useEffect } from 'react';
import {
  createBrowserRouter,
  createRoutesFromElements,
  Route,
  RouterProvider,
} from 'react-router-dom';

import { CssBaseline, ThemeProvider } from '@mui/material';

import * as splitwellOld from '@daml.js/splitwell-old/lib/CN/Splitwell';

import { SplitwellLedgerApiClientProvider } from './contexts/SplitwellLedgerApiContext';
import { SplitwellClientProvider } from './contexts/SplitwellServiceContext';
import './index.css';
import AuthCheck from './routes/authCheck';
import Home from './routes/home';
import Root from './routes/root';
import { useConfig } from './utils/config';

// TODO(#8268) Infer the package id based on coin rules here.
class SplitwellPackageIdResolver extends PackageIdResolver {
  async resolveTemplateId(templateId: string): Promise<string> {
    switch (this.getQualifiedName(templateId)) {
      case 'CN.Splitwell:SplitwellRules': {
        return splitwellOld.SplitwellRules.templateId;
      }
      default: {
        throw new Error(`Unknown temmplate id: ${templateId}`);
      }
    }
  }
}

const Providers: React.FC<React.PropsWithChildren> = ({ children }) => {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
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

  const config = useConfig();

  // TODO: (#8692) remove OldScanClientProvider when we no longer use it.
  return (
    <AuthProvider authConf={config.auth}>
      <QueryClientProvider client={queryClient}>
        <ReactQueryDevtools initialIsOpen={false} />
        <UserProvider authConf={config.auth} testAuthConf={config.testAuth} useLedgerApiTokens>
          <SplitwellClientProvider url={config.services.splitwell.url}>
            <OldScanClientProvider url={config.services.scan.url}>
              <ScanClientProvider url={config.services.scan.url}>
                <SplitwellLedgerApiClientProvider
                  jsonApiUrl={config.services.jsonApi.url}
                  packageIdResolver={new SplitwellPackageIdResolver()}
                >
                  {children}
                </SplitwellLedgerApiClientProvider>
              </ScanClientProvider>
            </OldScanClientProvider>
          </SplitwellClientProvider>
        </UserProvider>
      </QueryClientProvider>
    </AuthProvider>
  );
};

const SplitwellAuthCheck: React.FC = () => {
  const config = useConfig();
  const { loginWithOidc, oidcAuthState } = useUserState();
  useEffect(() => {
    // Auth-login after the user launched the app from their app manager.
    if (
      config.appManager &&
      oidcAuthState &&
      !oidcAuthState.isLoading &&
      !oidcAuthState.isAuthenticated
    ) {
      loginWithOidc();
    }
  }, [loginWithOidc, oidcAuthState, config]);
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
