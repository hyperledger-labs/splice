// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useUserState } from '@lfdecentralizedtrust/splice-common-frontend';
import {
  BaseApiMiddleware,
  OpenAPILoggingMiddleware,
} from '@lfdecentralizedtrust/splice-common-frontend-utils';
import {
  createConfiguration,
  AnsApi,
  Middleware,
  RequestContext,
  ResponseContext,
  ServerConfiguration,
} from 'ans-external-openapi';
import React, { useContext, useMemo } from 'react';

import { useAnsConfig } from '../utils';

const ExternalAnsContext = React.createContext<AnsApi | undefined>(undefined);

export interface ExternalAnsProps {
  url: string;
}

class ExternalApiMiddleware
  extends BaseApiMiddleware<RequestContext, ResponseContext>
  implements Middleware {}

export const ExternalAnsClientProvider: React.FC<React.PropsWithChildren<ExternalAnsProps>> = ({
  url,
  children,
}) => {
  const { userAccessToken } = useUserState();

  const friendlyClient: AnsApi | undefined = useMemo(() => {
    const externalConfiguration = createConfiguration({
      baseServer: new ServerConfiguration(url, {}),
      promiseMiddleware: [
        new ExternalApiMiddleware(userAccessToken),
        new OpenAPILoggingMiddleware('ans'),
      ],
    });

    return new AnsApi(externalConfiguration);
  }, [url, userAccessToken]);

  return (
    <ExternalAnsContext.Provider value={friendlyClient}>{children}</ExternalAnsContext.Provider>
  );
};

export const useExternalAnsClient: () => AnsApi = () => {
  const config = useAnsConfig();
  const nameServiceAcronym = config.spliceInstanceNames.nameServiceNameAcronym;
  const client = useContext<AnsApi | undefined>(ExternalAnsContext);
  if (!client) {
    throw new Error(`External ${nameServiceAcronym} client not initialized`);
  }
  return client;
};
