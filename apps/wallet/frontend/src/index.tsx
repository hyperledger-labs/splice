import * as React from 'react';
import {
  AuthProvider,
  DirectoryClientProvider,
  ScanClientProvider,
  theme,
  UserProvider,
} from 'common-frontend';
import { IsDevNetProvider } from 'common-frontend/lib/components/DevNetOnly';
import ReactDOM from 'react-dom/client';
import {
  createBrowserRouter,
  createRoutesFromElements,
  Route,
  RouterProvider,
  useNavigate,
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

const Providers: React.FC<React.PropsWithChildren> = ({ children }) => {
  const navigate = useNavigate();
  return (
    <AuthProvider authConf={config.auth} redirect={(path: string) => navigate(path)}>
      <UserProvider authConf={config.auth} testAuthConf={config.testAuth}>
        <ValidatorClientProvider url={config.services.validator.url}>
          <WalletClientProvider url={config.services.wallet.url}>
            <DirectoryClientProvider url={config.services.directory.url}>
              <ScanClientProvider url={config.services.scan.url}>
                <CurrentUserProvider>
                  <CoinPriceProvider>
                    <IsDevNetProvider>{children}</IsDevNetProvider>
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

ReactDOM.createRoot(document.getElementById('root') as HTMLElement).render(
  <React.StrictMode>
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <RouterProvider router={router} />
    </ThemeProvider>
  </React.StrictMode>
);
