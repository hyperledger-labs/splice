import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ReactQueryDevtools } from '@tanstack/react-query-devtools';
import {
  AuthProvider,
  DirectoryClientProvider,
  LedgerApiClientProvider,
  theme,
  UserProvider,
  cnReplaceEqualDeep,
  PackageIdResolver,
} from 'common-frontend';
import React from 'react';
import {
  createBrowserRouter,
  createRoutesFromElements,
  Route,
  RouterProvider,
} from 'react-router-dom';

import { CssBaseline, ThemeProvider } from '@mui/material';

import * as directoryOld from '@daml.js/directory-old/lib/CN/Directory';
import * as walletOld from '@daml.js/wallet-payments-old/lib/CN/Wallet/Subscriptions';

import AuthCheck from './routes/authCheck';
import Home from './routes/home';
import PostPayment from './routes/postPayment';
import Root from './routes/root';
import { config } from './utils';

// TODO(#7114) Remove this together with the whole LedgerApiClientProvider once directory
// talks to the backend.
class DirectoryPackageIdResolver extends PackageIdResolver {
  async resolveTemplateId(templateId: string): Promise<string> {
    switch (this.getQualifiedName(templateId)) {
      case 'CN.Directory:DirectoryInstall': {
        return directoryOld.DirectoryInstall.templateId;
      }
      case 'CN.Directory:DirectoryInstallRequest': {
        return directoryOld.DirectoryInstallRequest.templateId;
      }
      case 'CN.Directory:DirectoryEntry': {
        return directoryOld.DirectoryEntry.templateId;
      }
      case 'CN.Directory:DirectoryEntryContext': {
        return directoryOld.DirectoryEntryContext.templateId;
      }
      case 'CN.Wallet.Subscriptions:Subscription': {
        return walletOld.Subscription.templateId;
      }
      case 'CN.Wallet.Subscriptions:SubscriptionPayment': {
        return walletOld.SubscriptionPayment.templateId;
      }
      case 'CN.Wallet.Subscriptions:SubscriptionIdleState': {
        return walletOld.SubscriptionIdleState.templateId;
      }
      default: {
        throw new Error(`Unknown temmplate id: ${templateId}`);
      }
    }
  }
}

const Providers: React.FC<React.PropsWithChildren> = ({ children }) => {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        structuralSharing: cnReplaceEqualDeep,
      },
    },
  });

  return (
    <AuthProvider authConf={config.auth}>
      <QueryClientProvider client={queryClient}>
        <ReactQueryDevtools initialIsOpen={false} />
        <UserProvider authConf={config.auth} testAuthConf={config.testAuth} useLedgerApiTokens>
          <LedgerApiClientProvider
            jsonApiUrl={config.services.jsonApi.url}
            packageIdResolver={new DirectoryPackageIdResolver()}
          >
            <DirectoryClientProvider url={config.services.directory.url}>
              {children}
            </DirectoryClientProvider>
          </LedgerApiClientProvider>
        </UserProvider>
      </QueryClientProvider>
    </AuthProvider>
  );
};

const router = createBrowserRouter(
  createRoutesFromElements(
    <Route
      element={
        <Providers>
          <AuthCheck authConfig={config.auth} testAuthConfig={config.testAuth} />
        </Providers>
      }
    >
      <Route path="/" element={<Root />}>
        <Route index element={<Home />} />
        <Route path="home" element={<Home />} />
        <Route path="post-payment" element={<PostPayment />} />
      </Route>
    </Route>
  )
);

const App: React.FC = () => (
  <ThemeProvider theme={theme}>
    <CssBaseline />
    <RouterProvider router={router} />
  </ThemeProvider>
);

export default App;
