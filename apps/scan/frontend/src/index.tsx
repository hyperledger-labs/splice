// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ErrorBoundary } from '@lfdecentralizedtrust/splice-common-frontend';
import React from 'react';
import ReactDOM from 'react-dom/client';

import App from './App';
import { worker } from './__tests__/mocks/browser';
import { ScanConfigProvider } from './utils';

async function deferRender() {
  if (import.meta.env.MODE === 'testing') {
    await worker.start();
  }
}

deferRender().then(() => {
  ReactDOM.createRoot(document.getElementById('root') as HTMLElement).render(
    <React.StrictMode>
      <ErrorBoundary>
        <ScanConfigProvider>
          <App />
        </ScanConfigProvider>
      </ErrorBoundary>
    </React.StrictMode>
  );
});
