import * as openapi from 'sv-openapi';
import React, { useContext, useMemo } from 'react';
import { GetDebugInfoResponse, ServerConfiguration } from 'sv-openapi';

const SvContext = React.createContext<SvClient | undefined>(undefined);

export interface SVProps {
  url: string;
}

export interface SvClient {
  getDebugInfo: () => Promise<GetDebugInfoResponse>;
}

export const SvClientProvider: React.FC<React.PropsWithChildren<SVProps>> = ({ url, children }) => {
  const friendlyClient: SvClient | undefined = useMemo(() => {
    const configuration = openapi.createConfiguration({
      baseServer: new ServerConfiguration(url, {}),
    });
    const svClient = new openapi.SvApi(configuration);

    return {
      getDebugInfo: async (): Promise<GetDebugInfoResponse> => {
        return await svClient.getDebugInfo();
      },
    };
  }, [url]);

  return <SvContext.Provider value={friendlyClient}>{children}</SvContext.Provider>;
};

export const useSvClient: () => SvClient = () => {
  const client = useContext<SvClient | undefined>(SvContext);
  if (!client) {
    throw new Error('SV client not initialized');
  }
  return client;
};
