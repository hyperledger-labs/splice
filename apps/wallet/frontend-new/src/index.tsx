import * as React from 'react';
import { ThemeProvider } from '@emotion/react';
import ReactDOM from 'react-dom/client';
import {
  createBrowserRouter,
  createRoutesFromElements,
  Route,
  RouterProvider,
} from 'react-router-dom';

import { CssBaseline } from '@mui/material';

import Login from './routes/login';
import Root from './routes/root';
import Transactions from './routes/transactions';
import Transfer from './routes/transfer';
import theme from './theme';

const router = createBrowserRouter(
  createRoutesFromElements(
    <Route>
      <Route path="/" element={<Root />}>
        <Route index element={<Transactions />} />
        <Route path="transactions" element={<Transactions />} />
        <Route path="transfer" element={<Transfer />} />
      </Route>
      <Route path="/auth/">
        <Route path="login" element={<Login />} />
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
