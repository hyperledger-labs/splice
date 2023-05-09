import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ReactQueryDevtools } from '@tanstack/react-query-devtools';
import { AuthProvider, DirectoryClientProvider, theme, UserProvider } from 'common-frontend';
import React from 'react';
import ReactDOM from 'react-dom/client';
import {
  createBrowserRouter,
  createRoutesFromElements,
  Route,
  RouterProvider,
} from 'react-router-dom';

import { CssBaseline, ThemeProvider } from '@mui/material';

import App from './App';
import { DirectoryUiStateProvider } from './contexts/DirectoryContext';
import { LedgerApiClientProvider } from './contexts/LedgerApiContext';
import reportWebVitals from './reportWebVitals';
import { config } from './utils';
import Home from './views/Home';
import PostPayment from './views/PostPayment';

const Providers: React.FC<React.PropsWithChildren> = ({ children }) => {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        refetchInterval: 500, // re-fetch all queries every 500ms by default
      },
    },
  });

  return (
    <AuthProvider authConf={config.auth}>
      <QueryClientProvider client={queryClient}>
        <ReactQueryDevtools initialIsOpen={false} />
        <UserProvider authConf={config.auth} testAuthConf={config.testAuth} useLedgerApiTokens>
          <LedgerApiClientProvider jsonApiUrl={config.services.jsonApi.url}>
            <DirectoryClientProvider url={config.services.directory.url}>
              <DirectoryUiStateProvider>{children}</DirectoryUiStateProvider>
            </DirectoryClientProvider>
          </LedgerApiClientProvider>
        </UserProvider>
      </QueryClientProvider>
    </AuthProvider>
  );
};

const router = createBrowserRouter(
  createRoutesFromElements(
    <Route path="/" element={<App />}>
      <Route index element={<Home />} />
      <Route path="home" element={<Home />} />
      <Route path="post-payment" element={<PostPayment />} />
    </Route>
  )
);

const root = ReactDOM.createRoot(document.getElementById('root') as HTMLElement);
root.render(
  <React.StrictMode>
    {
      <ThemeProvider theme={theme}>
        <CssBaseline />
        <Providers>
          <RouterProvider router={router} />
        </Providers>
      </ThemeProvider>
    }
  </React.StrictMode>
);

// If you want to start measuring performance in your app, pass a function
// to log results (for example: reportWebVitals(console.log))
// or send to an analytics endpoint. Learn more: https://bit.ly/CRA-vitals
reportWebVitals();
