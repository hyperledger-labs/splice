import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ReactQueryDevtools } from '@tanstack/react-query-devtools';
import { AuthProvider, theme, UserProvider, cnReplaceEqualDeep } from 'common-frontend';
import { ScanClientProvider } from 'common-frontend/scan-api';
import React from 'react';
import {
  createBrowserRouter,
  createRoutesFromElements,
  Route,
  RouterProvider,
} from 'react-router-dom';

import { CssBaseline, ThemeProvider } from '@mui/material';

import { ExternalCnsClientProvider } from './context/ValidatorServiceContext';
import { WalletClientProvider } from './context/WalletServiceContext';
import AuthCheck from './routes/authCheck';
import Home from './routes/home';
import PostPayment from './routes/postPayment';
import Root from './routes/root';
import { config } from './utils';

const Providers: React.FC<React.PropsWithChildren> = ({ children }) => {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        structuralSharing: cnReplaceEqualDeep,
      },
    },
  });

  return (
    <AuthProvider authConf={config.auth}>
      <QueryClientProvider client={queryClient}>
        <ReactQueryDevtools initialIsOpen={false} />
        <UserProvider authConf={config.auth} testAuthConf={config.testAuth}>
          <WalletClientProvider url={config.services.validator.url}>
            <ScanClientProvider url={config.services.scan.url}>
              <ExternalCnsClientProvider url={config.services.validator.url}>
                {children}
              </ExternalCnsClientProvider>
            </ScanClientProvider>
          </WalletClientProvider>
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
        <Route path="home" element={<Home />} />
        <Route path="post-payment" element={<PostPayment />} />
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
