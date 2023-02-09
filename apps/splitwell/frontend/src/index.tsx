import {
  DirectoryClientProvider,
  AuthProvider,
  ScanClientProvider,
  UserProvider,
} from 'common-frontend';
import React from 'react';
import ReactDOM from 'react-dom/client';

import App from './App';
import { SplitwellClientProvider } from './contexts/SplitwellServiceContext';
import './index.css';
import reportWebVitals from './reportWebVitals';
import { config } from './utils/config';

const root = ReactDOM.createRoot(document.getElementById('root') as HTMLElement);
root.render(
  <React.StrictMode>
    <AuthProvider authConf={config.auth}>
      <UserProvider authConf={config.auth} testAuthConf={config.testAuth} useLedgerApiTokens>
        <SplitwellClientProvider url={config.services.splitwell.grpcUrl}>
          <DirectoryClientProvider url={config.services.directory.grpcUrl}>
            <ScanClientProvider url={config.services.scan.grpcUrl}>
              <App />
            </ScanClientProvider>
          </DirectoryClientProvider>
        </SplitwellClientProvider>
      </UserProvider>
    </AuthProvider>
  </React.StrictMode>
);

// If you want to start measuring performance in your app, pass a function
// to log results (for example: reportWebVitals(console.log))
// or send to an analytics endpoint. Learn more: https://bit.ly/CRA-vitals
reportWebVitals();
