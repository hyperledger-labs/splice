import * as openapi from 'sv-openapi';
import React, { useContext, useMemo } from 'react';
import { GetSvcInfoResponse, ServerConfiguration } from 'sv-openapi';

const SvContext = React.createContext<SvClient | undefined>(undefined);

export interface SVProps {
  url: string;
}

export interface SvClient {
  getSvcInfo: () => Promise<GetSvcInfoResponse>;
}

export const SvClientProvider: React.FC<React.PropsWithChildren<SVProps>> = ({ url, children }) => {
  const friendlyClient: SvClient | undefined = useMemo(() => {
    const configuration = openapi.createConfiguration({
      baseServer: new ServerConfiguration(url, {}),
    });
    const svClient = new openapi.SvApi(configuration);

    return {
      getSvcInfo: async (): Promise<GetSvcInfoResponse> => {
        return await svClient.getSvcInfo();
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
