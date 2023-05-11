import { StateServicePromiseClient } from 'common-protobuf/com/digitalasset/canton/research/participant/multidomain/state_service_grpc_web_pb';
import React, { useContext } from 'react';

const StateSnapshotServiceContext = React.createContext<StateServicePromiseClient | undefined>(
  undefined
);

export interface StateSnapshotServiceProps {
  url: string;
}

export const StateSnapshotServiceClientProvider: React.FC<
  React.PropsWithChildren<StateSnapshotServiceProps>
> = ({ url, children }) => {
  const domainConnectivityClient = new StateServicePromiseClient(url, null, null);
  return (
    <StateSnapshotServiceContext.Provider value={domainConnectivityClient}>
      {children}
    </StateSnapshotServiceContext.Provider>
  );
};

export const useStateSnapshotServiceClient: () => StateServicePromiseClient = () => {
  const client = useContext<StateServicePromiseClient | undefined>(StateSnapshotServiceContext);
  if (!client) {
    throw new Error('State snapshot service cannot be initialized');
  }
  return client;
};
