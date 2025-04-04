// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import { ErrorBoundary } from '@lfdecentralizedtrust/splice-common-frontend';
import ReactDOM from 'react-dom/client';

import App from './App';
import { WalletConfigProvider } from './utils/config';

ReactDOM.createRoot(document.getElementById('root') as HTMLElement).render(
  <React.StrictMode>
    <ErrorBoundary>
      <WalletConfigProvider>
        <App />
      </WalletConfigProvider>
    </ErrorBoundary>
  </React.StrictMode>
);
