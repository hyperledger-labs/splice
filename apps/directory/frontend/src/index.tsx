import React from 'react';
import ReactDOM from 'react-dom/client';
import { AuthProvider } from 'react-oidc-context';

import App from './App';
import { UserProvider } from './contexts/UserContext';
import './index.css';
import reportWebVitals from './reportWebVitals';
import { config } from './utils';

const root = ReactDOM.createRoot(document.getElementById('root') as HTMLElement);
root.render(
  <React.StrictMode>
    {
      <AuthProvider {...config.auth}>
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
