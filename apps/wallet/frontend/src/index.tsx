import { Auth0Provider } from '@auth0/auth0-react';
import { DirectoryClientProvider } from 'common-frontend';
import React from 'react';
import ReactDOM from 'react-dom/client';

import App from './App';
import { ValidatorClientProvider } from './contexts/ValidatorServiceContext';
import { WalletClientProvider } from './contexts/WalletServiceContext';
import './index.css';
import reportWebVitals from './reportWebVitals';
import { config } from './utils';

const root = ReactDOM.createRoot(document.getElementById('root') as HTMLElement);
root.render(
  <React.StrictMode>
    <WalletClientProvider url={config.wallet.grpcUrl}>
      <ValidatorClientProvider url={config.validator.grpcUrl}>
        <DirectoryClientProvider url={config.directory.grpcUrl}>
          {
            // TODO(i988) Remove hack where we don't use Auth0Provider if the website doesn't run on a secure origin
            crypto.subtle !== undefined ? (
              <Auth0Provider {...config.auth}>
                <App />
              </Auth0Provider>
            ) : (
              <App />
            )
          }
        </DirectoryClientProvider>
      </ValidatorClientProvider>
    </WalletClientProvider>
  </React.StrictMode>
);

// If you want to start measuring performance in your app, pass a function
// to log results (for example: reportWebVitals(console.log))
// or send to an analytics endpoint. Learn more: https://bit.ly/CRA-vitals
reportWebVitals();
