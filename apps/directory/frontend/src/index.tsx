import React from 'react';
import ReactDOM from 'react-dom/client';
import { AuthProvider } from 'react-oidc-context';

import App from './App';
import { UserProvider } from './contexts/UserContext';
import './index.css';
import reportWebVitals from './reportWebVitals';
import { config } from './utils';

// We need this for our auth0 test tenant setup so auth0 gives us the correct access token.
// Note that we don't request the openid scope here, which means we'll only get the access token.
// This is mainly a workaround for the fact that Canton can't parse JTWs with multiple audiences
// (auth0 adds the "../userinfo" audience if we request the openid scope).
const scope = 'daml_ledger_api';
// TODO(#1836) Pick a future-proof audience that doesn't depend on a modified canton instance.
const extraQueryParams = { audience: 'https://canton.network.global' };

const root = ReactDOM.createRoot(document.getElementById('root') as HTMLElement);
root.render(
  <React.StrictMode>
    {
      <AuthProvider scope={scope} extraQueryParams={extraQueryParams} {...config.auth}>
        <UserProvider>
          <App />
        </UserProvider>
      </AuthProvider>
    }
  </React.StrictMode>
);

// If you want to start measuring performance in your app, pass a function
// to log results (for example: reportWebVitals(console.log))
// or send to an analytics endpoint. Learn more: https://bit.ly/CRA-vitals
reportWebVitals();
