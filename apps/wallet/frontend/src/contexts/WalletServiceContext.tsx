import React, { useContext } from 'react';

import { WalletServiceClient } from '../com/daml/network/wallet/v0/Wallet_serviceServiceClientPb';

const WalletContext = React.createContext<WalletServiceClient | undefined>(undefined);

export interface WalletProps {
  url: string;
}

export const WalletClientProvider: React.FC<React.PropsWithChildren<WalletProps>> = ({
  url,
  children,
}) => {
  const walletClient = new WalletServiceClient(url, null, null);
  return <WalletContext.Provider value={walletClient}>{children}</WalletContext.Provider>;
};

export const useWalletClient: () => WalletServiceClient = () => {
  const client = useContext<WalletServiceClient | undefined>(WalletContext);
  if (!client) {
    throw new Error('Wallet client not initialized');
  }
  return client;
};
