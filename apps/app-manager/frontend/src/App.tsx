import * as React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ReactQueryDevtools } from '@tanstack/react-query-devtools';
import { AuthProvider, ErrorRouterPage, theme, UserProvider } from 'common-frontend';
import { replaceEqualDeep } from 'common-frontend-utils';
import { ScanClientProvider } from 'common-frontend/scan-api';
import {
  createBrowserRouter,
  createRoutesFromElements,
  Route,
  RouterProvider,
  useNavigate,
} from 'react-router-dom';

import { CssBaseline, ThemeProvider } from '@mui/material';
import { LocalizationProvider } from '@mui/x-date-pickers';
import { AdapterDayjs } from '@mui/x-date-pickers/AdapterDayjs';

import { AppManagerClientProvider } from './contexts/AppManagerServiceContext';
import Apps from './routes/Apps';
import Authorize from './routes/Authorize';
import AuthCheck from './routes/authCheck';
import Root from './routes/root';
import { useAppManagerConfig } from './utils/config';

const Providers: React.FC<React.PropsWithChildren> = ({ children }) => {
  const config = useAppManagerConfig();
  const navigate = useNavigate();
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        structuralSharing: replaceEqualDeep,
      },
    },
    logger: {
      log: () => {},
      error: () => {},
      warn: () => {},
    },
  });

  return (
    <AuthProvider authConf={config.auth} redirect={(path: string) => navigate(path)}>
      <QueryClientProvider client={queryClient}>
        <ReactQueryDevtools initialIsOpen={false} />
        <UserProvider authConf={config.auth} testAuthConf={config.testAuth}>
          <ScanClientProvider url={config.services.scan.url}>
            <AppManagerClientProvider url={config.services.validator.url}>
              <LocalizationProvider dateAdapter={AdapterDayjs}>{children}</LocalizationProvider>
            </AppManagerClientProvider>
          </ScanClientProvider>
        </UserProvider>
      </QueryClientProvider>
    </AuthProvider>
  );
};

const App: React.FC = () => {
  const config = useAppManagerConfig();
  const router = createBrowserRouter(
    createRoutesFromElements(
      <Route
        errorElement={<ErrorRouterPage />}
        element={
          <Providers>
            <AuthCheck authConfig={config.auth} testAuthConfig={config.testAuth} />
          </Providers>
        }
      >
        <Route path="/" element={<Root />}>
          <Route index element={<Apps />} />
          <Route path="authorize" element={<Authorize />} />
        </Route>
      </Route>
    )
  );
  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <RouterProvider router={router} />
    </ThemeProvider>
  );
};

export default App;
