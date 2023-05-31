import * as openapi from 'sv-openapi';
import BigNumber from 'bignumber.js';
import { BaseApiMiddleware, OpenAPILoggingMiddleware, useUserState } from 'common-frontend';
import React, { useContext, useMemo } from 'react';
import {
  createConfiguration,
  ListCoinPriceVotesResponse,
  ListOngoingValidatorOnboardingsResponse,
  ListOpenMiningRoundsResponse,
  ListValidatorLicensesResponse,
  Middleware,
  PrepareValidatorOnboardingRequest,
  PrepareValidatorOnboardingResponse,
  RequestContext,
  ResponseContext,
  ServerConfiguration,
  UpdateCoinPriceVoteRequest,
  CreateVoteRequest,
  ListVotesResponse,
  ListSvcRulesVoteRequestsResponse,
  LookupSvcRulesVoteRequestResponse,
  CastVoteRequest,
  UpdateVoteRequest,
  BatchListVotesByVoteRequestsRequest,
} from 'sv-openapi';

const SvAdminContext = React.createContext<SvAdminClient | undefined>(undefined);

export interface SvAdminProps {
  url: string;
}

export interface SvAdminClient {
  isAuthorized: () => Promise<void>;
  createVoteRequest: (
    requester: string,
    action: string,
    url: string,
    description: string
  ) => Promise<void>;
  listSvcRulesVoteRequests: () => Promise<ListSvcRulesVoteRequestsResponse>;
  lookupSvcRulesVoteRequest: (
    voteRequestContractId: string
  ) => Promise<LookupSvcRulesVoteRequestResponse>;
  castVote: (
    voteRequestContractId: string,
    isAccepted: boolean,
    reasonUrl: string,
    reasonDescription: string
  ) => Promise<void>;
  updateVote: (
    voteContractId: string,
    isAccepted: boolean,
    reasonUrl: string,
    reasonDescription: string
  ) => Promise<void>;
  listVotesByVoteRequests: (voteRequestContractIds: string[]) => Promise<ListVotesResponse>;
  prepareValidatorOnboarding: (expiresIn: number) => Promise<PrepareValidatorOnboardingResponse>;
  listOngoingValidatorOnboardings: () => Promise<ListOngoingValidatorOnboardingsResponse>;
  listValidatorLicenses: () => Promise<ListValidatorLicensesResponse>;
  listCoinPriceVotes: () => Promise<ListCoinPriceVotesResponse>;
  updateDesiredCoinPrice: (coinPrice: BigNumber) => Promise<void>;
  listOpenMiningRounds: () => Promise<ListOpenMiningRoundsResponse>;
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
      createVoteRequest: async (requester, action, url, description): Promise<void> => {
        const request: CreateVoteRequest = { requester, action, url, description };
        return await svAdminClient.createVoteRequest(request);
      },
      listSvcRulesVoteRequests: async (): Promise<ListSvcRulesVoteRequestsResponse> => {
        return await svAdminClient.listSvcRulesVoteRequests();
      },
      lookupSvcRulesVoteRequest: async (
        voteRequestContractId: string
      ): Promise<LookupSvcRulesVoteRequestResponse> => {
        return await svAdminClient.lookupSvcRulesVoteRequest(voteRequestContractId);
      },
      castVote: async (
        voteRequestContractId,
        isAccepted,
        reasonUrl,
        reasonDescription
      ): Promise<void> => {
        const request: CastVoteRequest = {
          voteRequestContractId,
          isAccepted,
          reasonUrl,
          reasonDescription,
        };
        return await svAdminClient.castVote(request);
      },
      updateVote: async (
        voteContractId,
        isAccepted,
        reasonUrl,
        reasonDescription
      ): Promise<void> => {
        const request: UpdateVoteRequest = {
          voteContractId,
          isAccepted,
          reasonUrl,
          reasonDescription,
        };
        return await svAdminClient.updateVote(request);
      },
      listVotesByVoteRequests: async (
        voteRequestContractIds: string[]
      ): Promise<ListVotesResponse> => {
        const request: BatchListVotesByVoteRequestsRequest = {
          voteRequestContractIds,
        };
        return await svAdminClient.batchListVotesByVoteRequests(request);
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
      updateDesiredCoinPrice: async (coinPrice: BigNumber): Promise<void> => {
        const request: UpdateCoinPriceVoteRequest = { coinPrice: coinPrice.toString() };
        return await svAdminClient.updateCoinPriceVote(request);
      },
      listOpenMiningRounds: async (): Promise<ListOpenMiningRoundsResponse> => {
        return await svAdminClient.listOpenMiningRounds();
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
