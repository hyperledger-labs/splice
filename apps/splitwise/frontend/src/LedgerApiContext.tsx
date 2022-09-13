import React, { useContext } from 'react';

import { GroupKey } from './com/daml/network/splitwise/v0/splitwise_service_pb';

class LedgerApiClient {
  // TODO(#661) Fill in the stubs so they actually work
  async createGroup(provider: string, id: string) {
    throw new Error('not implemented');
  }
  async createGroupInvite(provider: string, id: string, observers: string[]) {
    throw new Error('not implemented');
  }
  async acceptInvite(provider: string, inviteContractId: string) {
    throw new Error('not implemented');
  }
  async joinGroup(provider: string, inviteContractId: string) {
    throw new Error('not implemented');
  }
  async enterPayment(provider: string, key: GroupKey, quantity: string, description: string) {
    throw new Error('not implemented');
  }
  async initiateTransfer(provider: string, key: GroupKey, receiver: string, quantity: string) {
    throw new Error('not implemented');
  }
  async completeTransfer(provider: string, key: GroupKey, acceptedPaymentContractId: string) {
    throw new Error('not implemented');
  }
}

const LedgerApiClientContext = React.createContext<LedgerApiClient | undefined>(undefined);

export const LedgerApiClientProvider: React.FC<React.PropsWithChildren<Record<string, never>>> = ({
  children,
}) => {
  const splitwiseClient = new LedgerApiClient();
  return (
    <LedgerApiClientContext.Provider value={splitwiseClient}>
      {children}
    </LedgerApiClientContext.Provider>
  );
};

export const useLedgerApiClient: () => LedgerApiClient = () => {
  const client = useContext(LedgerApiClientContext);
  if (!client) {
    throw new Error('Splitwise client not initialized');
  }
  return client;
};
