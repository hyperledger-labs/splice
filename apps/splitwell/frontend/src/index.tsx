// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ErrorBoundary } from '@lfdecentralizedtrust/splice-common-frontend';
import React from 'react';
import ReactDOM from 'react-dom/client';

import App from './App';
import './index.css';
import { SplitwellStaticConfigProvider } from './utils/config';

ReactDOM.createRoot(document.getElementById('root') as HTMLElement).render(
  <React.StrictMode>
    <ErrorBoundary>
      <SplitwellStaticConfigProvider>
        <App />
      </SplitwellStaticConfigProvider>
    </ErrorBoundary>
  </React.StrictMode>
);
