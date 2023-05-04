import * as React from 'react';
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
import Root from './routes/root';
import Svc from './routes/svc';
import { config } from './utils';

const Providers: React.FC<React.PropsWithChildren> = ({ children }) => {
  const navigate = useNavigate();
  return (
    <AuthProvider authConf={config.auth} redirect={(path: string) => navigate(path)}>
      <UserProvider authConf={config.auth} testAuthConf={config.testAuth}>
        <SvAdminClientProvider url={config.services.sv.url}>{children}</SvAdminClientProvider>
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
        <Route index element={<Svc />} />
        <Route path="svc" element={<Svc />} />
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
