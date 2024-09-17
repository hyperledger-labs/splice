import * as React from 'react';
import { ErrorBoundary } from 'common-frontend';
import ReactDOM from 'react-dom/client';

import App from './App';
import { AppManagerConfigProvider } from './utils/config';

ReactDOM.createRoot(document.getElementById('root') as HTMLElement).render(
  <React.StrictMode>
    <ErrorBoundary>
      <AppManagerConfigProvider>
        <App />
      </AppManagerConfigProvider>
    </ErrorBoundary>
  </React.StrictMode>
);
