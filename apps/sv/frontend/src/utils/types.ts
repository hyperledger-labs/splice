// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { ContractId } from '@daml/types';
import { VoteRequest } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';

export interface OffBoardMemberProposal {
  memberToOffboard: string;
}

export interface FeatureAppProposal {
  provider: string;
}

export interface UnfeatureAppProposal {
  rightContractId: string;
}

export interface ConfigChange {
  fieldName: string;
  currentValue: string | number;
  newValue: string | number;
  isId?: boolean;
}

export interface UpdateSvRewardWeightProposal {
  svToUpdate: string;
  weightChange: string;
}

export interface AmuletRulesConfigProposal {
  configChanges: ConfigChange[];
}

export interface DsoRulesConfigProposal {
  configChanges: ConfigChange[];
}

export type Proposal =
  | OffBoardMemberProposal
  | FeatureAppProposal
  | UnfeatureAppProposal
  | UpdateSvRewardWeightProposal
  | AmuletRulesConfigProposal
  | DsoRulesConfigProposal
  | undefined;

export type ProposalActionMap = {
  SRARC_OffboardSv: OffBoardMemberProposal;
  SRARC_GrantFeaturedAppRight: FeatureAppProposal;
  SRARC_RevokeFeaturedAppRight: UnfeatureAppProposal;
  SRARC_UpdateSvRewardWeight: UpdateSvRewardWeightProposal;
  CRARC_SetConfig: AmuletRulesConfigProposal;
  SRARC_SetConfig: DsoRulesConfigProposal;
  // If no proposal type is defined, can use unknown or a specific type:
  CRARC_AddFutureAmuletConfigSchedule: unknown;
};

export type ProposalDetails = {
  actionName: string;
  createdAt: string;
  url: string;
  summary: string;
  isVoteRequest?: boolean;
} & {
  // Use types to enforce the right proposal params for the selected action.
  [Tag in keyof ProposalActionMap]: { action: Tag; proposal: ProposalActionMap[Tag] };
}[keyof ProposalActionMap];

export interface ProposalVotingInformation {
  requester: string;
  requesterIsYou?: boolean;
  votingCloses: string;
  voteTakesEffect: string;
  status: ProposalListingStatus;
}

export type YourVoteStatus = 'accepted' | 'rejected' | 'no-vote';

export type SupportedActionTag =
  | 'CRARC_AddFutureAmuletConfigSchedule'
  | 'CRARC_SetConfig'
  | 'SRARC_GrantFeaturedAppRight'
  | 'SRARC_OffboardSv'
  | 'SRARC_RevokeFeaturedAppRight'
  | 'SRARC_SetConfig'
  | 'SRARC_UpdateSvRewardWeight';

export type ProposalListingStatus =
  | 'Accepted'
  | 'In Progress'
  | 'Implemented'
  | 'Rejected'
  | 'Expired'
  | 'Unknown';

export interface ProposalListingData {
  contractId: ContractId<VoteRequest>;
  actionName: string;
  votingCloses: string;
  voteTakesEffect: string;
  yourVote: YourVoteStatus;
  status: ProposalListingStatus;
  voteStats: Record<YourVoteStatus, number>;
  acceptanceThreshold: bigint;
}

export type VoteStatus = 'accepted' | 'rejected' | 'no-vote';

export interface VoteReason {
  url: string;
  body: string;
}

export type ProposalVote = {
  sv: string;
  isYou?: boolean;
} & (
  | {
      vote: 'no-vote';
      reason?: undefined;
    }
  | {
      vote: 'accepted' | 'rejected';
      reason: VoteReason;
    }
);
