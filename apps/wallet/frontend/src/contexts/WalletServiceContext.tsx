import React, { useContext } from 'react';

import { WalletServicePromiseClient } from '../com/daml/network/wallet/v0/wallet_service_grpc_web_pb';

const WalletContext = React.createContext<WalletServicePromiseClient | undefined>(undefined);

export interface WalletProps {
  url: string;
}

export const WalletClientProvider: React.FC<React.PropsWithChildren<WalletProps>> = ({
  url,
  children,
}) => {
  const walletClient = new WalletServicePromiseClient(url, null, null);
  return <WalletContext.Provider value={walletClient}>{children}</WalletContext.Provider>;
};

export const useWalletClient: () => WalletServicePromiseClient = () => {
  const client = useContext<WalletServicePromiseClient | undefined>(WalletContext);
  if (!client) {
    throw new Error('Wallet client not initialized');
  }
  return client;
};
