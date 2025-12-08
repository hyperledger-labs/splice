// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import type { ContractId, Optional } from '@daml/types';
import type { AmuletConfig } from '@daml.js/splice-amulet/lib/Splice/AmuletConfig';
import type {
  ActionRequiringConfirmation,
  DsoRulesConfig,
  VoteRequest,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import type { ConfigFieldState } from '../components/form-components/ConfigField';
import type { CreateUnallocatedUnclaimedActivityRecordFormData } from '../components/forms/CreateUnallocatedUnclaimedActivityRecordForm';
import type { GrantRevokeFeaturedAppFormData } from '../components/forms/GrantRevokeFeaturedAppForm';
import type { OffboardSvFormData } from '../components/forms/OffboardSvForm';
import type { SetAmuletConfigCompleteFormData } from '../components/forms/SetAmuletConfigRulesForm';
import type { SetDsoConfigCompleteFormData } from '../components/forms/SetDsoConfigRulesForm';
import type { UpdateSvRewardWeightFormData } from '../components/forms/UpdateSvRewardWeightForm';

export interface OffBoardMemberProposal {
  memberToOffboard: string;
}

export interface FeatureAppProposal {
  provider: string;
}

export interface UnfeatureAppProposal {
  rightContractId: string;
}

export interface UnclaimedActivityRecordProposal {
  beneficiary: string;
  amount: string;
  mintBefore: string;
}

/**
 * A config change represents a field that has been changed in a config.
 * This could be DSO or Amulet Configs.
 */
export interface ConfigChange {
  /**
   * A unique name based on the json path of the field
   */
  fieldName: string;
  /**
   * A label that can be displayed to the user
   */
  label: string;
  currentValue: string;
  newValue: string;
  /**
   * If the field is an id, e.g a party id.
   */
  isId?: boolean;
}

export interface UpdateSvRewardWeightProposal {
  svToUpdate: string;
  currentWeight: string;
  weightChange: string;
}

export interface AmuletRulesConfigProposal {
  configChanges: ConfigChange[];
  baseConfig: AmuletConfig<'USD'>;
  newConfig: AmuletConfig<'USD'>;
}

export interface DsoRulesConfigProposal {
  configChanges: ConfigChange[];
  baseConfig: Optional<DsoRulesConfig>;
  newConfig: DsoRulesConfig;
}

export type Proposal =
  | OffBoardMemberProposal
  | FeatureAppProposal
  | UnfeatureAppProposal
  | UpdateSvRewardWeightProposal
  | UnclaimedActivityRecordProposal
  | AmuletRulesConfigProposal
  | DsoRulesConfigProposal
  | undefined;

export type ProposalActionMap = {
  SRARC_OffboardSv: OffBoardMemberProposal;
  SRARC_GrantFeaturedAppRight: FeatureAppProposal;
  SRARC_RevokeFeaturedAppRight: UnfeatureAppProposal;
  SRARC_UpdateSvRewardWeight: UpdateSvRewardWeightProposal;
  SRARC_CreateUnallocatedUnclaimedActivityRecord: UnclaimedActivityRecordProposal;
  CRARC_SetConfig: AmuletRulesConfigProposal;
  SRARC_SetConfig: DsoRulesConfigProposal;
  // If no proposal type is defined, can use unknown or a specific type:
  CRARC_AddFutureAmuletConfigSchedule: unknown;
};

export type ProposalActionKeys = keyof ProposalActionMap;

export type ProposalDetails = {
  actionName: string;
  createdAt: string;
  url: string;
  summary: string;
  isVoteRequest?: boolean;
} & {
  // Use types to enforce the right proposal params for the selected action.
  [Tag in keyof ProposalActionMap]: {
    action: Tag;
    proposal: ProposalActionMap[Tag];
  };
}[keyof ProposalActionMap];

export interface ProposalVotingInformation {
  requester: string;
  requesterIsYou?: boolean;
  votingThresholdDeadline: string;
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
  | 'SRARC_UpdateSvRewardWeight'
  | 'SRARC_CreateUnallocatedUnclaimedActivityRecord';

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
  votingThresholdDeadline: string;
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

export type ConfigFormData = Record<string, ConfigFieldState>;

export interface CommonProposalFormData {
  action: string;
  expiryDate: string;
  effectiveDate: Effectivity;
  url: string;
  summary: string;
}

export type EffectivityType = 'custom' | 'threshold';

export interface Effectivity {
  type: EffectivityType;
  effectiveDate: string | undefined;
}

export interface ProposalMutationArgs {
  formData: ProposalFormData;
  action: ActionRequiringConfirmation;
}

export type NonConfigProposalFormData =
  | UpdateSvRewardWeightFormData
  | OffboardSvFormData
  | GrantRevokeFeaturedAppFormData
  | CreateUnallocatedUnclaimedActivityRecordFormData;

export type ConfigProposalFormData = SetDsoConfigCompleteFormData | SetAmuletConfigCompleteFormData;

export type ProposalFormData = NonConfigProposalFormData | ConfigProposalFormData;

export interface PendingConfigFieldInfo {
  fieldName: string;
  pendingValue: string;
  proposalCid: string;
  effectiveDate: string;
}
