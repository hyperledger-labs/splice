import { useUserState } from 'common-frontend';
import { BaseApiMiddleware, OpenAPILoggingMiddleware } from 'common-frontend-utils';
import React, { useContext, useMemo } from 'react';
import {
  Middleware,
  createConfiguration,
  ServerConfiguration,
  RequestContext,
  ResponseContext,
  AppManagerApi,
  AppManagerAdminApi,
} from 'validator-openapi';

type AppManagerApis = {
  api: AppManagerApi;
  adminApi: AppManagerAdminApi;
};

const AppManagerContext = React.createContext<AppManagerApis | undefined>(undefined);

export interface AppManagerProps {
  url: string;
}
class ApiMiddleware
  extends BaseApiMiddleware<RequestContext, ResponseContext>
  implements Middleware {}

export const AppManagerClientProvider: React.FC<React.PropsWithChildren<AppManagerProps>> = ({
  url,
  children,
}) => {
  const { userAccessToken } = useUserState();

  const friendlyClient = useMemo(() => {
    const configuration = createConfiguration({
      baseServer: new ServerConfiguration(url, {}),
      promiseMiddleware: [
        new ApiMiddleware(userAccessToken),
        new OpenAPILoggingMiddleware('app-manager'),
      ],
    });

    return {
      api: new AppManagerApi(configuration),
      adminApi: new AppManagerAdminApi(configuration),
    };
  }, [url, userAccessToken]);

  return <AppManagerContext.Provider value={friendlyClient}>{children}</AppManagerContext.Provider>;
};

export const useAppManagerClient: () => AppManagerApi = () => {
  const clients = useContext<AppManagerApis | undefined>(AppManagerContext);
  if (!clients) {
    throw new Error('App manager client not initialized');
  }
  return clients.api;
};

export const useAppManagerAdminClient: () => AppManagerAdminApi = () => {
  const clients = useContext<AppManagerApis | undefined>(AppManagerContext);
  if (!clients) {
    throw new Error('App manager client not initialized');
  }
  return clients.adminApi;
};
