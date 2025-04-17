// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import { ErrorBoundary } from '@lfdecentralizedtrust/splice-common-frontend';
import ReactDOM from 'react-dom/client';

import App from './App';
import { worker } from './__tests__/mocks/browser';
import { WalletConfigProvider } from './utils/config';

async function deferRender() {
  if (import.meta.env.MODE === 'testing') {
    await worker.start();
  }
}

deferRender().then(() => {
  ReactDOM.createRoot(document.getElementById('root') as HTMLElement).render(
    <React.StrictMode>
      <ErrorBoundary>
        <WalletConfigProvider>
          <App />
        </WalletConfigProvider>
      </ErrorBoundary>
    </React.StrictMode>
  );
});
