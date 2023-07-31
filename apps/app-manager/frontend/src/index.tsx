import * as React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ReactQueryDevtools } from '@tanstack/react-query-devtools';
import {
  AuthProvider,
  cnReplaceEqualDeep,
  DirectoryClientProvider,
  theme,
  UserProvider,
} from 'common-frontend';
import ReactDOM from 'react-dom/client';
import {
  createBrowserRouter,
  createRoutesFromElements,
  Route,
  RouterProvider,
  useNavigate,
} from 'react-router-dom';

import { CssBaseline, ThemeProvider } from '@mui/material';

import { AppManagerClientProvider } from './contexts/AppManagerServiceContext';
import Apps from './routes/Apps';
import Authorize from './routes/Authorize';
import AuthCheck from './routes/authCheck';
import Root from './routes/root';
import { config } from './utils/config';

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
        <ReactQueryDevtools initialIsOpen={false} />
        <UserProvider authConf={config.auth} testAuthConf={config.testAuth}>
          <DirectoryClientProvider url={config.services.directory.url}>
            <AppManagerClientProvider url={config.services.validator.url}>
              {children}
            </AppManagerClientProvider>
          </DirectoryClientProvider>
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
        <Route index element={<Apps />} />
        <Route path="authorize" element={<Authorize />} />
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
