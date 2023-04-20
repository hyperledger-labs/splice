import * as React from 'react';
import {
  AuthProvider,
  DirectoryClientProvider,
  ScanClientProvider,
  theme,
  UserProvider,
} from 'common-frontend';
import ReactDOM from 'react-dom/client';
import {
  createBrowserRouter,
  createRoutesFromElements,
  Route,
  RouterProvider,
} from 'react-router-dom';

import { CssBaseline, ThemeProvider } from '@mui/material';

import { CoinPriceProvider } from './contexts/CoinPriceContext';
import { CurrentUserProvider } from './contexts/CurrentUserContext';
import { ValidatorClientProvider } from './contexts/ValidatorServiceContext';
import { WalletClientProvider } from './contexts/WalletServiceContext';
import AuthCheck from './routes/authCheck';
import ConfirmPayment from './routes/confirmPayment';
import ConfirmSubscription from './routes/confirmSubscription';
import Confirmation from './routes/confirmation';
import Root from './routes/root';
import Subscriptions from './routes/subscriptions';
import Transactions from './routes/transactions';
import Transfer from './routes/transfer';
import { config } from './utils/config';

const router = createBrowserRouter(
  createRoutesFromElements(
    <Route element={<AuthCheck authConfig={config.auth} testAuthConfig={config.testAuth} />}>
      <Route path="/" element={<Root />}>
        <Route index element={<Transactions />} />
        <Route path="transactions" element={<Transactions />} />
        <Route path="transfer" element={<Transfer />} />
        <Route path="subscriptions" element={<Subscriptions />} />
      </Route>
      <Route element={<Confirmation />}>
        <Route path="confirm-payment/:cid/" element={<ConfirmPayment />} />
        <Route path="confirm-subscription/:cid/" element={<ConfirmSubscription />} />
      </Route>
    </Route>
  )
);

const Providers: React.FC<React.PropsWithChildren> = ({ children }) => {
  return (
    <AuthProvider authConf={config.auth}>
      <UserProvider authConf={config.auth} testAuthConf={config.testAuth}>
        <ValidatorClientProvider url={config.services.validator.url}>
          <WalletClientProvider url={config.services.wallet.url}>
            <DirectoryClientProvider url={config.services.directory.url}>
              <ScanClientProvider url={config.services.scan.url}>
                <CurrentUserProvider>
                  <CoinPriceProvider>
                    <ThemeProvider theme={theme}>{children}</ThemeProvider>
                  </CoinPriceProvider>
                </CurrentUserProvider>
              </ScanClientProvider>
            </DirectoryClientProvider>
          </WalletClientProvider>
        </ValidatorClientProvider>
      </UserProvider>
    </AuthProvider>
  );
};

ReactDOM.createRoot(document.getElementById('root') as HTMLElement).render(
  <React.StrictMode>
    <Providers>
      <CssBaseline />
      <RouterProvider router={router} />
    </Providers>
  </React.StrictMode>
);
