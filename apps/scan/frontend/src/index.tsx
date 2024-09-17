// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ErrorBoundary } from 'common-frontend';
import React from 'react';
import ReactDOM from 'react-dom/client';

import App from './App';
import { ScanConfigProvider } from './utils';

ReactDOM.createRoot(document.getElementById('root') as HTMLElement).render(
  <React.StrictMode>
    <ErrorBoundary>
      <ScanConfigProvider>
        <App />
      </ScanConfigProvider>
    </ErrorBoundary>
  </React.StrictMode>
);
