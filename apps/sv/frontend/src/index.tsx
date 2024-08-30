// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import { ErrorBoundary } from 'common-frontend';
import ReactDOM from 'react-dom/client';

import App from './App';
import { SvConfigProvider } from './utils';

ReactDOM.createRoot(document.getElementById('root') as HTMLElement).render(
  <React.StrictMode>
    <ErrorBoundary>
      <SvConfigProvider>
        <App />
      </SvConfigProvider>
    </ErrorBoundary>
  </React.StrictMode>
);
