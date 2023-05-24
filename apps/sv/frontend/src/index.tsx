import * as React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider, theme, ErrorBoundary, UserProvider } from 'common-frontend';
import ReactDOM from 'react-dom/client';
import {
  createBrowserRouter,
  createRoutesFromElements,
  Route,
  RouterProvider,
  useNavigate,
} from 'react-router-dom';

import { ThemeProvider, CssBaseline } from '@mui/material';

import { SvAdminClientProvider } from './contexts/SvAdminServiceContext';
import AuthCheck from './routes/authCheck';
import CoinPrice from './routes/coinPrice';
import Root from './routes/root';
import Svc from './routes/svc';
import ValidatorOnboarding from './routes/validatorOnboarding';
import Voting from './routes/voting';
import { config } from './utils';

const Providers: React.FC<React.PropsWithChildren> = ({ children }) => {
  const navigate = useNavigate();
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        refetchInterval: 500, // re-fetch all queries every 500ms by default
      },
    },
  });
  return (
    <AuthProvider authConf={config.auth} redirect={(path: string) => navigate(path)}>
      <QueryClientProvider client={queryClient}>
        <UserProvider authConf={config.auth} testAuthConf={config.testAuth}>
          <SvAdminClientProvider url={config.services.sv.url}>{children}</SvAdminClientProvider>
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
        <Route index element={<Svc />} />
        <Route path="svc" element={<Svc />} />
        <Route path="validator-onboarding" element={<ValidatorOnboarding />} />
        <Route path="cc-price" element={<CoinPrice />} />
        <Route path="votes" element={<Voting />} />
      </Route>
    </Route>
  )
);

ReactDOM.createRoot(document.getElementById('root') as HTMLElement).render(
  <React.StrictMode>
    <ErrorBoundary>
      <ThemeProvider theme={theme}>
        <CssBaseline />
        <RouterProvider router={router} />
      </ThemeProvider>
    </ErrorBoundary>
  </React.StrictMode>
);
