import {
  DirectoryClientProvider,
  AuthProvider,
  ScanClientProvider,
  UserProvider,
  StateSnapshotServiceClientProvider,
  theme,
} from 'common-frontend';
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
  return (
    <AuthProvider authConf={config.auth}>
      <UserProvider authConf={config.auth} testAuthConf={config.testAuth} useLedgerApiTokens>
        <SplitwellClientProvider url={config.services.splitwell.url}>
          <DirectoryClientProvider url={config.services.directory.url}>
            <ScanClientProvider url={config.services.scan.url}>
              <SplitwellLedgerApiClientProvider url={config.services.ledgerApi.url}>
                <StateSnapshotServiceClientProvider url={config.services.ledgerApi.url}>
                  {children}
                </StateSnapshotServiceClientProvider>
              </SplitwellLedgerApiClientProvider>
            </ScanClientProvider>
          </DirectoryClientProvider>
        </SplitwellClientProvider>
      </UserProvider>
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
