// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useUserState } from '@lfdecentralizedtrust/splice-common-frontend';
import {
  BaseApiMiddleware,
  OpenAPILoggingMiddleware,
} from '@lfdecentralizedtrust/splice-common-frontend-utils';
import React, { useContext, useMemo } from 'react';
import {
  createConfiguration,
  Middleware,
  RequestContext,
  ResponseContext,
  ServerConfiguration,
  WalletApi,
} from 'wallet-openapi';

const WalletContext = React.createContext<WalletClient | undefined>(undefined);

export interface WalletProps {
  url: string;
}

export interface UserStatusResponse {
  userOnboarded: boolean;
  userWalletInstalled: boolean;
  partyId: string;
}

export interface WalletClient {
  userStatus: () => Promise<UserStatusResponse>;
}

class ApiMiddleware
  extends BaseApiMiddleware<RequestContext, ResponseContext>
  implements Middleware {}

export const WalletClientProvider: React.FC<React.PropsWithChildren<WalletProps>> = ({
  url,
  children,
}) => {
  const { userAccessToken } = useUserState();

  // This  is a wrapper around WalletApi, transforming requests and responses into more ergonomic values for the frontend.
  // e.g., OpenAPIContract => Contract<T>
  const friendlyClient: WalletClient | undefined = useMemo(() => {
    const configuration = createConfiguration({
      baseServer: new ServerConfiguration(url, {}),
      promiseMiddleware: [
        new ApiMiddleware(userAccessToken),
        new OpenAPILoggingMiddleware('wallet'),
      ],
    });

    const walletClient = new WalletApi(configuration);

    return {
      userStatus: async () => {
        const res = await walletClient.userStatus();
        return {
          userOnboarded: res.user_onboarded,
          userWalletInstalled: res.user_wallet_installed,
          partyId: res.party_id,
        };
      },
    };
  }, [url, userAccessToken]);

  return <WalletContext.Provider value={friendlyClient}>{children}</WalletContext.Provider>;
};

export const useWalletClient: () => WalletClient = () => {
  const client = useContext<WalletClient | undefined>(WalletContext);
  if (!client) {
    throw new Error('Wallet client not initialized');
  }
  return client;
};
