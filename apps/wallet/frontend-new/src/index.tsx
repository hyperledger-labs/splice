import * as React from 'react';
import { theme } from 'common-frontend';
import ReactDOM from 'react-dom/client';
import {
  createBrowserRouter,
  createRoutesFromElements,
  Route,
  RouterProvider,
} from 'react-router-dom';

import { CssBaseline, ThemeProvider } from '@mui/material';

import ConfirmPayment from './routes/confirmPayment';
import ConfirmSubscription from './routes/confirmSubscription';
import Login from './routes/login';
import Root from './routes/root';
import Subscriptions from './routes/subscriptions';
import Transactions from './routes/transactions';
import Transfer from './routes/transfer';

const router = createBrowserRouter(
  createRoutesFromElements(
    <Route>
      <Route path="/" element={<Root />}>
        <Route index element={<Transactions />} />
        <Route path="transactions" element={<Transactions />} />
        <Route path="transfer" element={<Transfer />} />
        <Route path="subscriptions" element={<Subscriptions />} />
      </Route>
      <Route path="/auth/">
        <Route path="login" element={<Login />} />
      </Route>
      <Route path="confirm-payment/:cid/" element={<ConfirmPayment />} />
      <Route path="confirm-subscription/:cid/" element={<ConfirmSubscription />} />
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
