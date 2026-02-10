// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as openapi from '@lfdecentralizedtrust/sv-openapi';
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
  CreateVoteRequest,
  GetPartyToParticipantResponse,
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
} from '@lfdecentralizedtrust/sv-openapi';

import { RelTime } from '@daml.js/daml-stdlib-DA-Time-Types-1.0.0/lib/DA/Time/Types/module';
import { ActionRequiringConfirmation } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';

const SvAdminContext = React.createContext<SvAdminClient | undefined>(undefined);

export interface SvAdminProps {
  url: string;
}

export interface SvAdminClient {
  isAuthorized: () => Promise<void>;
  createVoteRequest: (
    requester: string,
    action: ActionRequiringConfirmation,
    url: string,
    description: string,
    expiration: RelTime,
    effectiveTime?: Date
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
  prepareValidatorOnboarding: (
    expiresIn: number,
    partyHint: string
  ) => Promise<PrepareValidatorOnboardingResponse>;
  listOngoingValidatorOnboardings: () => Promise<ListOngoingValidatorOnboardingsResponse>;
  listValidatorLicenses: (limit: number, after?: number) => Promise<ListValidatorLicensesResponse>;
  listAmuletPriceVotes: () => Promise<ListAmuletPriceVotesResponse>;
  updateDesiredAmuletPrice: (amuletPrice: BigNumber) => Promise<void>;
  listOpenMiningRounds: () => Promise<ListOpenMiningRoundsResponse>;
  getCometBftNodeDebug: () => Promise<openapi.CometBftNodeDumpOrErrorResponse>;
  getSequencerNodeStatus: () => Promise<openapi.NodeStatus>;
  getMediatorNodeStatus: () => Promise<openapi.NodeStatus>;
  featureSupport: () => Promise<openapi.FeatureSupportResponse>;
  getPartyToParticipant: (partyId: string) => Promise<GetPartyToParticipantResponse>;
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
      createVoteRequest: async (
        requester,
        action: ActionRequiringConfirmation,
        url,
        description,
        expiration,
        effectiveAt
      ): Promise<void> => {
        const request: CreateVoteRequest = {
          requester,
          action: ActionRequiringConfirmation.encode(action),
          url,
          description,
          expiration: RelTime.encode(expiration),
          effectiveTime: effectiveAt,
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
        expires_in: number,
        party_hint: string
      ): Promise<PrepareValidatorOnboardingResponse> => {
        const request: PrepareValidatorOnboardingRequest = { expires_in, party_hint };
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
      featureSupport: async (): Promise<openapi.FeatureSupportResponse> => {
        return await svAdminClient.featureSupport();
      },
      getPartyToParticipant: async (partyId: string): Promise<GetPartyToParticipantResponse> => {
        return await svAdminClient.getPartyToParticipant(partyId);
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
