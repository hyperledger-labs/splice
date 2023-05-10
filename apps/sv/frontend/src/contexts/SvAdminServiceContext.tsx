import * as openapi from 'sv-openapi';
import { BaseApiMiddleware, OpenAPILoggingMiddleware, useUserState } from 'common-frontend';
import React, { useContext, useMemo } from 'react';
import {
  createConfiguration,
  ListCoinPriceVotesResponse,
  ListOngoingValidatorOnboardingsResponse,
  ListValidatorLicensesResponse,
  Middleware,
  PrepareValidatorOnboardingRequest,
  PrepareValidatorOnboardingResponse,
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
  prepareValidatorOnboarding: (expiresIn: number) => Promise<PrepareValidatorOnboardingResponse>;
  listOngoingValidatorOnboardings: () => Promise<ListOngoingValidatorOnboardingsResponse>;
  listValidatorLicenses: () => Promise<ListValidatorLicensesResponse>;
  listCoinPriceVotes: () => Promise<ListCoinPriceVotesResponse>;
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
      prepareValidatorOnboarding: async (
        expiresIn: number
      ): Promise<PrepareValidatorOnboardingResponse> => {
        const request: PrepareValidatorOnboardingRequest = { expiresIn };
        return await svAdminClient.prepareValidatorOnboarding(request);
      },
      listOngoingValidatorOnboardings:
        async (): Promise<ListOngoingValidatorOnboardingsResponse> => {
          return await svAdminClient.listOngoingValidatorOnboardings();
        },
      listValidatorLicenses: async (): Promise<ListValidatorLicensesResponse> => {
        return await svAdminClient.listValidatorLicenses();
      },
      listCoinPriceVotes: async (): Promise<ListCoinPriceVotesResponse> => {
        return await svAdminClient.listCoinPriceVotes();
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
