import * as React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import {
  AuthProvider,
  ErrorBoundary,
  UserProvider,
  cnReplaceEqualDeep,
  theme,
} from 'common-frontend';
import {
  Route,
  RouterProvider,
  createBrowserRouter,
  createRoutesFromElements,
  useNavigate,
} from 'react-router-dom';

import { CssBaseline, ThemeProvider } from '@mui/material';
import { LocalizationProvider } from '@mui/x-date-pickers';
import { AdapterDayjs } from '@mui/x-date-pickers/AdapterDayjs';

import { SvAdminClientProvider } from './contexts/SvAdminServiceContext';
import AuthCheck from './routes/authCheck';
import CoinPrice from './routes/coinPrice';
import Leader from './routes/leader';
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
        structuralSharing: cnReplaceEqualDeep,
      },
    },
    logger: {
      log: args => {},
      error: args => {},
      warn: args => {},
    },
  });
  return (
    <AuthProvider authConf={config.auth} redirect={(path: string) => navigate(path)}>
      <QueryClientProvider client={queryClient}>
        <UserProvider authConf={config.auth} testAuthConf={config.testAuth}>
          <SvAdminClientProvider url={config.services.sv.url}>
            <LocalizationProvider dateAdapter={AdapterDayjs}>{children}</LocalizationProvider>
          </SvAdminClientProvider>
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
        <Route path="leader" element={<Leader />} />
      </Route>
    </Route>
  )
);

const App: React.FC = () => (
  <ErrorBoundary>
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <RouterProvider router={router} />
    </ThemeProvider>
  </ErrorBoundary>
);

export default App;
