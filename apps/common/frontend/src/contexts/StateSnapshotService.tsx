import { StateSnapshotServicePromiseClient } from 'common-protobuf/com/digitalasset/canton/research/participant/multidomain/state_snapshot_service_grpc_web_pb';
import React, { useContext } from 'react';

const StateSnapshotServiceContext = React.createContext<
  StateSnapshotServicePromiseClient | undefined
>(undefined);

export interface StateSnapshotServiceProps {
  url: string;
}

export const StateSnapshotServiceClientProvider: React.FC<
  React.PropsWithChildren<StateSnapshotServiceProps>
> = ({ url, children }) => {
  const domainConnectivityClient = new StateSnapshotServicePromiseClient(url, null, null);
  return (
    <StateSnapshotServiceContext.Provider value={domainConnectivityClient}>
      {children}
    </StateSnapshotServiceContext.Provider>
  );
};

export const useStateSnapshotServiceClient: () => StateSnapshotServicePromiseClient = () => {
  const client = useContext<StateSnapshotServicePromiseClient | undefined>(
    StateSnapshotServiceContext
  );
  if (!client) {
    throw new Error('State snapshot service cannot be initialized');
  }
  return client;
};
