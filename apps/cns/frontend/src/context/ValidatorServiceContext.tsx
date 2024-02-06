import {
  createConfiguration,
  CnsApi,
  Middleware,
  RequestContext,
  ResponseContext,
  ServerConfiguration,
} from 'cns-external-openapi';
import { useUserState } from 'common-frontend';
import { BaseApiMiddleware, OpenAPILoggingMiddleware } from 'common-frontend-utils';
import React, { useContext, useMemo } from 'react';

const ExternalCnsContext = React.createContext<CnsApi | undefined>(undefined);

export interface ExternalCnsProps {
  url: string;
}

class ExternalApiMiddleware
  extends BaseApiMiddleware<RequestContext, ResponseContext>
  implements Middleware {}

export const ExternalCnsClientProvider: React.FC<React.PropsWithChildren<ExternalCnsProps>> = ({
  url,
  children,
}) => {
  const { userAccessToken } = useUserState();

  const friendlyClient: CnsApi | undefined = useMemo(() => {
    const externalConfiguration = createConfiguration({
      baseServer: new ServerConfiguration(url, {}),
      promiseMiddleware: [
        new ExternalApiMiddleware(userAccessToken),
        new OpenAPILoggingMiddleware('cns'),
      ],
    });

    return new CnsApi(externalConfiguration);
  }, [url, userAccessToken]);

  return (
    <ExternalCnsContext.Provider value={friendlyClient}>{children}</ExternalCnsContext.Provider>
  );
};

export const useExternalCnsClient: () => CnsApi = () => {
  const client = useContext<CnsApi | undefined>(ExternalCnsContext);
  if (!client) {
    throw new Error('External CNS client not initialized');
  }
  return client;
};
