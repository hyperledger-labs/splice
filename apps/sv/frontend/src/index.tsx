import { theme } from 'common-frontend';
import { ErrorBoundary } from 'common-frontend';
import React from 'react';
import ReactDOM from 'react-dom/client';
import {
  createBrowserRouter,
  createRoutesFromElements,
  Route,
  RouterProvider,
} from 'react-router-dom';

import { ThemeProvider, CssBaseline } from '@mui/material';

import Debug from './routes/debug';
import Root from './routes/root';

const router = createBrowserRouter(
  createRoutesFromElements(
    <Route>
      <Route path="/" element={<Root />}>
        <Route index element={<Debug />} />
        <Route path="debug" element={<Debug />} />
      </Route>
    </Route>
  )
);

ReactDOM.createRoot(document.getElementById('root') as HTMLElement).render(
  <React.StrictMode>
    {
      <ErrorBoundary>
        <ThemeProvider theme={theme}>
          <CssBaseline />
          <RouterProvider router={router} />
        </ThemeProvider>
      </ErrorBoundary>
    }
  </React.StrictMode>
);
