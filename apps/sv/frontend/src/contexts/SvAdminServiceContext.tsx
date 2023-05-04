import * as openapi from 'sv-openapi';
import { BaseApiMiddleware, OpenAPILoggingMiddleware, useUserState } from 'common-frontend';
import React, { useContext, useMemo } from 'react';
import {
  createConfiguration,
  Middleware,
  RequestContext,
  ResponseContext,
  ServerConfiguration,
} from 'sv-openapi';

const SvAdminContext = React.createContext<SvAdminClient | undefined>(undefined);

export interface SvAdminProps {
  url: string;
}

export interface SvAdminClient {
  isAuthorized: () => Promise<void>;
}

class ApiMiddleware
  extends BaseApiMiddleware<RequestContext, ResponseContext>
  implements Middleware {}

export const SvAdminClientProvider: React.FC<React.PropsWithChildren<SvAdminProps>> = ({
  url,
  children,
}) => {
  const { userAccessToken } = useUserState();
  const friendlyClient: SvAdminClient | undefined = useMemo(() => {
    const configuration = createConfiguration({
      baseServer: new ServerConfiguration(url, {}),
      promiseMiddleware: [new ApiMiddleware(userAccessToken), new OpenAPILoggingMiddleware('sv')],
    });

    const svAdminClient = new openapi.SvApi(configuration);

    return {
      isAuthorized: async (): Promise<void> => {
        return await svAdminClient.isAuthorized();
      },
    };
  }, [url, userAccessToken]);

  return <SvAdminContext.Provider value={friendlyClient}>{children}</SvAdminContext.Provider>;
};

export const useSvAdminClient: () => SvAdminClient = () => {
  const client = useContext<SvAdminClient | undefined>(SvAdminContext);
  if (!client) {
    throw new Error('Sv Admin client not initialized');
  }
  return client;
};
