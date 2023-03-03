import * as React from 'react';
import { AuthProvider, theme, UserProvider } from 'common-frontend';
import ReactDOM from 'react-dom/client';
import {
  createBrowserRouter,
  createRoutesFromElements,
  Route,
  RouterProvider,
} from 'react-router-dom';

import { CssBaseline, ThemeProvider } from '@mui/material';

import { ValidatorClientProvider } from './contexts/ValidatorServiceContext';
import { WalletClientProvider } from './contexts/WalletServiceContext';
import AuthCheck from './routes/authCheck';
import ConfirmPayment from './routes/confirmPayment';
import ConfirmSubscription from './routes/confirmSubscription';
import Login from './routes/login';
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
      <Route path="/auth/">
        <Route
          path="login"
          element={<Login authConfig={config.auth} testAuthConfig={config.testAuth} />}
        />
      </Route>
      <Route path="confirm-payment/:cid/" element={<ConfirmPayment />} />
      <Route path="confirm-subscription/:cid/" element={<ConfirmSubscription />} />
    </Route>
  )
);

ReactDOM.createRoot(document.getElementById('root') as HTMLElement).render(
  <React.StrictMode>
    <AuthProvider authConf={config.auth}>
      <UserProvider authConf={config.auth} testAuthConf={config.testAuth}>
        <ValidatorClientProvider url={config.services.validator.grpcUrl}>
          <WalletClientProvider url={config.services.wallet.grpcUrl}>
            <ThemeProvider theme={theme}>
              <CssBaseline />
              <RouterProvider router={router} />
            </ThemeProvider>
          </WalletClientProvider>
        </ValidatorClientProvider>
      </UserProvider>
    </AuthProvider>
  </React.StrictMode>
);
