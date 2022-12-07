import { DirectoryClientProvider, ScanClientProvider, UserProvider } from 'common-frontend';
import React from 'react';
import ReactDOM from 'react-dom/client';

import App from './App';
import AuthProvider from './components/AuthProvider';
import { ValidatorClientProvider } from './contexts/ValidatorServiceContext';
import { WalletClientProvider } from './contexts/WalletServiceContext';
import './index.css';
import reportWebVitals from './reportWebVitals';
import { config } from './utils/config';

const root = ReactDOM.createRoot(document.getElementById('root') as HTMLElement);
root.render(
  <React.StrictMode>
    <AuthProvider>
      <UserProvider authConf={config.auth}>
        <WalletClientProvider url={config.services.wallet.grpcUrl}>
          <ValidatorClientProvider url={config.services.validator.grpcUrl}>
            <DirectoryClientProvider url={config.services.directory.grpcUrl}>
              <ScanClientProvider url={config.services.scan.grpcUrl}>
                <App />
              </ScanClientProvider>
            </DirectoryClientProvider>
          </ValidatorClientProvider>
        </WalletClientProvider>
      </UserProvider>
    </AuthProvider>
  </React.StrictMode>
);

// If you want to start measuring performance in your app, pass a function
// to log results (for example: reportWebVitals(console.log))
// or send to an analytics endpoint. Learn more: https://bit.ly/CRA-vitals
reportWebVitals();
