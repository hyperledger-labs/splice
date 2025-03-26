// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as openapi from 'sv-openapi';
import { useUserState } from '@lfdecentralizedtrust/splice-common-frontend';
import {
  BaseApiMiddleware,
  OpenAPILoggingMiddleware,
} from '@lfdecentralizedtrust/splice-common-frontend-utils';
import BigNumber from 'bignumber.js';
import React, { useContext, useMemo } from 'react';
import {
  CastVoteRequest,
  createConfiguration,
  CreateElectionRequest,
  CreateVoteRequest,
  GetElectionRequestResponse,
  ListAmuletPriceVotesResponse,
  ListOngoingValidatorOnboardingsResponse,
  ListOpenMiningRoundsResponse,
  ListDsoRulesVoteRequestsResponse,
  ListDsoRulesVoteResultsResponse,
  ListValidatorLicensesResponse,
  ListVoteRequestByTrackingCidResponse,
  ListVoteResultsRequest,
  LookupDsoRulesVoteRequestResponse,
  Middleware,
  PrepareValidatorOnboardingRequest,
  PrepareValidatorOnboardingResponse,
  RequestContext,
  ResponseContext,
  ServerConfiguration,
  UpdateAmuletPriceVoteRequest,
} from 'sv-openapi';

import { RelTime } from '@daml.js/b70db8369e1c461d5c70f1c86f526a29e9776c655e6ffc2560f95b05ccb8b946/lib/DA/Time/Types';
import { ActionRequiringConfirmation } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';

const SvAdminContext = React.createContext<SvAdminClient | undefined>(undefined);

export interface SvAdminProps {
  url: string;
}

export interface SvAdminClient {
  isAuthorized: () => Promise<void>;
  getElectionRequest: () => Promise<GetElectionRequestResponse>;
  createElectionRequest: (requester: string, ranking: string[]) => Promise<void>;
  createVoteRequest: (
    requester: string,
    action: ActionRequiringConfirmation,
    url: string,
    description: string,
    expiration: RelTime
  ) => Promise<void>;
  listDsoRulesVoteRequests: () => Promise<ListDsoRulesVoteRequestsResponse>;
  listVoteRequestResults: (
    limit: number,
    actionName?: string,
    requester?: string,
    effectiveFrom?: string,
    effectiveTo?: string,
    accepted?: boolean
  ) => Promise<ListDsoRulesVoteResultsResponse>;
  lookupDsoRulesVoteRequest: (
    voteRequestContractId: string
  ) => Promise<LookupDsoRulesVoteRequestResponse>;
  listVoteRequestsByTrackingCid: (
    voteRequestContractIds: string[]
  ) => Promise<ListVoteRequestByTrackingCidResponse>;
  castVote: (
    voteRequestContractId: string,
    isAccepted: boolean,
    reasonUrl: string,
    reasonDescription: string
  ) => Promise<void>;
  prepareValidatorOnboarding: (expiresIn: number) => Promise<PrepareValidatorOnboardingResponse>;
  listOngoingValidatorOnboardings: () => Promise<ListOngoingValidatorOnboardingsResponse>;
  listValidatorLicenses: (limit: number, after?: number) => Promise<ListValidatorLicensesResponse>;
  listAmuletPriceVotes: () => Promise<ListAmuletPriceVotesResponse>;
  updateDesiredAmuletPrice: (amuletPrice: BigNumber) => Promise<void>;
  listOpenMiningRounds: () => Promise<ListOpenMiningRoundsResponse>;
  getCometBftNodeDebug: () => Promise<openapi.CometBftNodeDumpOrErrorResponse>;
  getSequencerNodeStatus: () => Promise<openapi.NodeStatus>;
  getMediatorNodeStatus: () => Promise<openapi.NodeStatus>;
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
      getElectionRequest: async (): Promise<GetElectionRequestResponse> => {
        return await svAdminClient.getElectionRequest();
      },
      createElectionRequest: async (requester, ranking: string[]): Promise<void> => {
        const request: CreateElectionRequest = {
          requester,
          ranking,
        };
        return await svAdminClient.createElectionRequest(request);
      },
      createVoteRequest: async (
        requester,
        action: ActionRequiringConfirmation,
        url,
        description,
        expiration
      ): Promise<void> => {
        const request: CreateVoteRequest = {
          requester,
          action: ActionRequiringConfirmation.encode(action),
          url,
          description,
          expiration: RelTime.encode(expiration),
        };
        return await svAdminClient.createVoteRequest(request);
      },
      listDsoRulesVoteRequests: async (): Promise<ListDsoRulesVoteRequestsResponse> => {
        return await svAdminClient.listDsoRulesVoteRequests();
      },
      listVoteRequestResults: async (
        limit: number,
        actionName?: string,
        requester?: string,
        effectiveFrom?: string,
        effectiveTo?: string,
        accepted?: boolean
      ): Promise<ListDsoRulesVoteResultsResponse> => {
        const request: ListVoteResultsRequest = {
          actionName: actionName,
          accepted: accepted,
          requester: requester,
          effectiveFrom: effectiveFrom,
          effectiveTo: effectiveTo,
          limit: limit,
        };
        return await svAdminClient.listVoteRequestResults(request);
      },
      lookupDsoRulesVoteRequest: async (
        voteRequestContractId: string
      ): Promise<LookupDsoRulesVoteRequestResponse> => {
        return await svAdminClient.lookupDsoRulesVoteRequest(voteRequestContractId);
      },
      listVoteRequestsByTrackingCid: async (
        voteRequestContractIds: string[]
      ): Promise<ListVoteRequestByTrackingCidResponse> => {
        const request = {
          vote_request_contract_ids: voteRequestContractIds,
        };
        return await svAdminClient.listVoteRequestsByTrackingCid(request);
      },
      castVote: async (
        vote_request_contract_id,
        is_accepted,
        reason_url,
        reason_description
      ): Promise<void> => {
        const request: CastVoteRequest = {
          vote_request_contract_id,
          is_accepted,
          reason_url,
          reason_description,
        };
        return await svAdminClient.castVote(request);
      },
      prepareValidatorOnboarding: async (
        expires_in: number
      ): Promise<PrepareValidatorOnboardingResponse> => {
        const request: PrepareValidatorOnboardingRequest = { expires_in };
        return await svAdminClient.prepareValidatorOnboarding(request);
      },
      listOngoingValidatorOnboardings:
        async (): Promise<ListOngoingValidatorOnboardingsResponse> => {
          return await svAdminClient.listOngoingValidatorOnboardings();
        },
      listValidatorLicenses: async (
        limit: number,
        after?: number
      ): Promise<ListValidatorLicensesResponse> => {
        return await svAdminClient.listValidatorLicenses(after, limit);
      },
      listAmuletPriceVotes: async (): Promise<ListAmuletPriceVotesResponse> => {
        return await svAdminClient.listAmuletPriceVotes();
      },
      updateDesiredAmuletPrice: async (amuletPrice: BigNumber): Promise<void> => {
        const request: UpdateAmuletPriceVoteRequest = { amulet_price: amuletPrice.toString() };
        return await svAdminClient.updateAmuletPriceVote(request);
      },
      listOpenMiningRounds: async (): Promise<ListOpenMiningRoundsResponse> => {
        return await svAdminClient.listOpenMiningRounds();
      },
      getCometBftNodeDebug: async (): Promise<openapi.CometBftNodeDumpOrErrorResponse> => {
        return await svAdminClient.getCometBftNodeDebugDump();
      },
      getSequencerNodeStatus: async (): Promise<openapi.NodeStatus> => {
        return await svAdminClient.getSequencerNodeStatus();
      },
      getMediatorNodeStatus: async (): Promise<openapi.NodeStatus> => {
        return await svAdminClient.getMediatorNodeStatus();
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
