import { DomainConnectivityServicePromiseClient } from 'common-protobuf/com/digitalasset/canton/participant/admin/v0/domain_connectivity_grpc_web_pb';
import React, { useContext } from 'react';

const DomainConnectivityContext = React.createContext<
  DomainConnectivityServicePromiseClient | undefined
>(undefined);

export interface DommainConnectivityProps {
  url: string;
}

export const DomainConnectivityClientProvider: React.FC<
  React.PropsWithChildren<DommainConnectivityProps>
> = ({ url, children }) => {
  const domainConnectivityClient = new DomainConnectivityServicePromiseClient(url, null, null);
  return (
    <DomainConnectivityContext.Provider value={domainConnectivityClient}>
      {children}
    </DomainConnectivityContext.Provider>
  );
};

export const useDomainConnectivityClient: () => DomainConnectivityServicePromiseClient = () => {
  const client = useContext<DomainConnectivityServicePromiseClient | undefined>(
    DomainConnectivityContext
  );
  if (!client) {
    throw new Error('Domain connectivity service cannot be initialized');
  }
  return client;
};
