// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import type {
  ActionRequiringConfirmation,
  AmuletRules_ActionRequiringConfirmation,
  DsoRules_ActionRequiringConfirmation,
  DsoRules_CloseVoteRequestResult,
  DsoRules_SetConfig,
  DsoRulesConfig,
  SvInfo,
  Vote,
  VoteRequest,
  VoteRequestOutcome,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import type { DsoInfo } from '@canton-network/splice-common-frontend';
import { type Contract } from '@canton-network/splice-common-frontend-utils';
import dayjs, { type Dayjs } from 'dayjs';
import type {
  AmuletRulesConfigProposal,
  ConfigChange,
  ConfigFormData,
  DsoRulesConfigProposal,
  FeatureAppProposal,
  OffBoardMemberProposal,
  PendingConfigFieldInfo,
  Proposal,
  ProposalListingStatus,
  ProposalListingData,
  SupportedActionTag,
  UnclaimedActivityRecordProposal,
  UnfeatureAppProposal,
  UpdateFeatureAppProposal,
  UpdateSvRewardWeightProposal,
  YourVoteStatus,
  UnpermissionValidatorProposal,
  RepermissionValidatorProposal,
} from '../utils/types';
import { buildAmuletConfigChanges } from './buildAmuletConfigChanges';
import { buildDsoConfigChanges } from './buildDsoConfigChanges';
import {
  switchOverEntriesToConfigValue,
  type SwitchOverEntry,
} from '../components/forms/formValidators';
import { AmuletRules_SetConfig } from '@daml.js/splice-amulet/lib/Splice/AmuletRules';
import { AmuletConfig } from '@daml.js/splice-amulet/lib/Splice/AmuletConfig';
import { Optional } from '@daml/types';

export const actionTagToTitle = (amuletName: string): Record<SupportedActionTag, string> => ({
  CRARC_AddFutureAmuletConfigSchedule: `Add Future ${amuletName} Configuration Schedule`,
  CRARC_SetConfig: `Set ${amuletName} Rules Configuration`,
  SRARC_GrantFeaturedAppRight: 'Feature Application',
  SRARC_OffboardSv: 'Offboard Member',
  SRARC_RevokeFeaturedAppRight: 'Unfeature Application',
  SRARC_CreateUnallocatedUnclaimedActivityRecord: 'Create Unclaimed Activity Record',
  SRARC_SetConfig: 'Set Decentralized Synchronizer Operations (DSO) Rules Configuration',
  SRARC_UpdateSvRewardWeight: 'Update Super Validator Reward Weight',
  SRARC_UpdateFeaturedAppRight: 'Update Featured Application',
  SRARC_UnpermissionValidator: 'Unpermission Validator',
  SRARC_RepermissionValidator: 'Repermission Validator',
});

export const createProposalActions: {
  name: string;
  value: SupportedActionTag;
}[] = [
  { name: 'Offboard Member', value: 'SRARC_OffboardSv' },
  { name: 'Feature Application', value: 'SRARC_GrantFeaturedAppRight' },
  { name: 'Unfeature Application', value: 'SRARC_RevokeFeaturedAppRight' },
  { name: 'Update Featured Application', value: 'SRARC_UpdateFeaturedAppRight' },
  {
    name: 'Set Decentralized Synchronizer Operations (DSO) Rules Configuration',
    value: 'SRARC_SetConfig',
  },
  {
    name: 'Create Unclaimed Activity Record',
    value: 'SRARC_CreateUnallocatedUnclaimedActivityRecord',
  },
  { name: 'Set Amulet Rules Configuration', value: 'CRARC_SetConfig' },
  { name: 'Update Super Validator Reward Weight', value: 'SRARC_UpdateSvRewardWeight' },
  { name: 'Unpermission Validator', value: 'SRARC_UnpermissionValidator' },
  { name: 'Repermission Validator', value: 'SRARC_RepermissionValidator' },
];

export const getVoteResultStatus = (
  outcome: VoteRequestOutcome | undefined
): ProposalListingStatus => {
  if (!outcome) return 'Unknown';

  switch (outcome.tag) {
    case 'VRO_Accepted': {
      const effectiveAt = dayjs(outcome.value.effectiveAt);
      const now = dayjs();
      if (dayjs(effectiveAt).isBefore(now)) return 'Implemented';
      else return 'Accepted';
    }
    case 'VRO_Expired':
      return 'Expired';
    case 'VRO_Rejected':
      return 'Rejected';
    default:
      return 'Unknown';
  }
};

export function computeVoteStats(votes: Vote[]): {
  accepted: number;
  rejected: number;
} {
  return votes.reduce(
    (acc, vote) => ({
      accepted: acc.accepted + (vote.accept ? 1 : 0),
      rejected: acc.rejected + (vote.accept ? 0 : 1),
    }),
    { accepted: 0, rejected: 0 }
  );
}

export function getRequesterPartyId(
  requester: string,
  svs: { entriesArray(): [string, SvInfo][] } | undefined
): string {
  if (requester.includes('::')) {
    return requester;
  }
  if (!svs) {
    return requester;
  }
  const match = svs.entriesArray().find(([, info]) => info.name === requester);
  return match?.[0] ?? requester;
}

export function computeYourVote(votes: Vote[], svPartyId: string | undefined): YourVoteStatus {
  if (svPartyId === undefined) {
    return 'no-vote';
  }

  const vote = votes.find(vote => vote.sv === svPartyId);
  return vote ? (vote.accept ? 'accepted' : 'rejected') : 'no-vote';
}

export function getGovernanceActionTag(action: ActionRequiringConfirmation): string {
  switch (action.tag) {
    case 'ARC_AmuletRules':
      return action.value.amuletRulesAction.tag;
    case 'ARC_DsoRules':
      return action.value.dsoAction.tag;
    default:
      return 'Action tag not defined.';
  }
}

export function buildVoteHistoryData(
  voteResults: DsoRules_CloseVoteRequestResult[],
  amuletName: string,
  svPartyId: string | undefined,
  votingThreshold: bigint,
  svs: { entriesArray(): [string, SvInfo][] } | undefined
): ProposalListingData[] {
  return voteResults
    .filter(
      vr =>
        (vr.outcome.tag === 'VRO_Accepted' &&
          dayjs(vr.outcome.value.effectiveAt).isBefore(dayjs())) ||
        vr.outcome.tag === 'VRO_Expired' ||
        vr.outcome.tag === 'VRO_Rejected'
    )
    .map(vr => {
      const votes = vr.request.votes.entriesArray().map(e => e[1]);

      return {
        contractId: vr.request.trackingCid,
        actionName:
          actionTagToTitle(amuletName)[
            getGovernanceActionTag(vr.request.action) as SupportedActionTag
          ],
        description: vr.request.reason.body,
        votingThresholdDeadline: vr.request.voteBefore,
        voteTakesEffect:
          (vr.outcome.tag === 'VRO_Accepted' && vr.outcome.value.effectiveAt) || vr.completedAt,
        yourVote: computeYourVote(votes, svPartyId),
        status: getVoteResultStatus(vr.outcome),
        voteStats: computeVoteStats(votes),
        acceptanceThreshold: votingThreshold,
        requester: getRequesterPartyId(vr.request.requester, svs),
      } as ProposalListingData;
    });
}

export function buildProposal(action: ActionRequiringConfirmation, dsoInfo?: DsoInfo): Proposal {
  if (action.tag === 'ARC_DsoRules') {
    const dsoAction = action.value.dsoAction;
    switch (dsoAction.tag) {
      case 'SRARC_RepermissionValidator':
        return createRepermissionValidatorProposal(dsoAction.value.validatorUnpermissionCid);
      case 'SRARC_UnpermissionValidator':
        return createUnpermissionValidatorProposal(
          dsoAction.value.participantId,
          dsoAction.value.revoked,
          dsoAction.value.loginAfter
        );
      case 'SRARC_OffboardSv':
        return createOffboardMemberProposal(dsoAction.value.sv);
      case 'SRARC_UpdateSvRewardWeight': {
        const allSvInfos = dsoInfo?.dsoRules.payload.svs.entriesArray() || [];
        const svToUpdate = dsoAction.value.svParty;
        const currentWeight = getSvRewardWeight(allSvInfos, svToUpdate);

        return createSvRewardWeightProposal(
          svToUpdate,
          formatBasisPoints(currentWeight),
          formatBasisPoints(dsoAction.value.newRewardWeight)
        );
      }
      case 'SRARC_CreateUnallocatedUnclaimedActivityRecord':
        return createUnallocatedUnclaimedActivityRecordProposal(
          dsoAction.value.beneficiary,
          dsoAction.value.amount,
          dsoAction.value.expiresAt
        );
      case 'SRARC_GrantFeaturedAppRight':
        return createGrantFeatureAppProposal(
          dsoAction.value.provider,
          dsoAction.value.activityWeight ?? ''
        );
      case 'SRARC_RevokeFeaturedAppRight':
        return createRevokeFeatureAppProposal(dsoAction.value.rightCid);
      case 'SRARC_UpdateFeaturedAppRight':
        return createUpdateFeatureAppProposal(
          dsoAction.value.rightCid,
          dsoAction.value.update.newActivityWeight
        );
      case 'SRARC_SetConfig':
        return createDsoRulesConfigProposal(dsoAction.value.baseConfig, dsoAction.value.newConfig);
    }
  } else if (action.tag === 'ARC_AmuletRules') {
    const amuletAction = action.value.amuletRulesAction;
    switch (amuletAction.tag) {
      case 'CRARC_SetConfig':
        return createAmuletRulesConfigProposal(
          amuletAction.value.baseConfig,
          amuletAction.value.newConfig
        );
    }
  }
}

function createRepermissionValidatorProposal(
  unpermissionCid: string
): RepermissionValidatorProposal {
  return {
    unpermissionCid,
  };
}

function createUnpermissionValidatorProposal(
  participantId: string,
  revoked: boolean,
  loginAfter: string | null
): UnpermissionValidatorProposal {
  return {
    participantId,
    revoked,
    loginAfter: loginAfter ?? undefined,
  };
}

function createOffboardMemberProposal(memberToOffboard: string): OffBoardMemberProposal {
  return { memberToOffboard };
}

function createGrantFeatureAppProposal(
  provider: string,
  activityWeight: string
): FeatureAppProposal {
  return {
    provider: provider,
    activityWeight: activityWeight,
  };
}

function createUpdateFeatureAppProposal(
  rightContractId: string,
  newActivityWeight: string
): UpdateFeatureAppProposal {
  return { rightContractId, newActivityWeight };
}

function createRevokeFeatureAppProposal(rightContractId: string): UnfeatureAppProposal {
  return {
    rightContractId: rightContractId,
  };
}

function createSvRewardWeightProposal(
  svToUpdate: string,
  currentWeight: string,
  weightChange: string
): UpdateSvRewardWeightProposal {
  return {
    svToUpdate: svToUpdate,
    currentWeight: currentWeight,
    weightChange: weightChange,
  };
}

function createUnallocatedUnclaimedActivityRecordProposal(
  beneficiary: string,
  amount: string,
  mintBefore: string
): UnclaimedActivityRecordProposal {
  return {
    beneficiary: beneficiary,
    amount: amount,
    mintBefore: mintBefore,
  };
}

function createAmuletRulesConfigProposal(
  baseConfig: AmuletConfig<'USD'>,
  newConfig: AmuletConfig<'USD'>
): AmuletRulesConfigProposal {
  return {
    configChanges: buildAmuletConfigChanges(baseConfig, newConfig),
    baseConfig: baseConfig,
    newConfig: newConfig,
  };
}

function createDsoRulesConfigProposal(
  baseConfig: Optional<DsoRulesConfig>,
  newConfig: DsoRulesConfig
): DsoRulesConfigProposal {
  return {
    configChanges: buildDsoConfigChanges(baseConfig, newConfig),
    baseConfig: baseConfig,
    newConfig: newConfig,
  };
}

export function getActionValue(
  a: ActionRequiringConfirmation
): DsoRules_ActionRequiringConfirmation | AmuletRules_ActionRequiringConfirmation | undefined {
  if (!a) return undefined;

  switch (a.tag) {
    case 'ARC_AmuletRules':
      return a.value.amuletRulesAction;
    case 'ARC_DsoRules':
      return a.value.dsoAction;
    default:
      return undefined;
  }
}

export function getInitialExpiration(dsoInfo: DsoInfo | undefined): Dayjs {
  if (!dsoInfo) return dayjs().add(1, 'day');

  return dayjs().add(
    Math.floor(parseInt(dsoInfo.dsoRules.payload.config.voteRequestTimeout.microseconds!) / 1000),
    'milliseconds'
  );
}

/**
 * Builds a list of config changes from the form data and filters out the ones that have not changed
 **/
export function configFormDataToConfigChanges(
  formData: ConfigFormData,
  configChanges: ConfigChange[],
  onlyChangedFields = true
): ConfigChange[] {
  const changes = configChanges.map(change => {
    const fieldState = formData[change.fieldName];
    return {
      fieldName: change.fieldName,
      label: change.label,
      currentValue: change.currentValue,
      newValue: fieldState?.value || '',
    } as ConfigChange;
  });

  return onlyChangedFields
    ? changes.filter(change => change.currentValue !== change.newValue)
    : changes;
}

/**
 * Mirror the switch-over map editor's entries into the config field of the given
 * name, so switch-over times participate in {@link configFormDataToConfigChanges}
 * like any other config field (change detection, review summary, reconstruction).
 * The entries slice remains the editable source of truth; this derives the flat
 * config value from it at read time.
 */
export function withSwitchOverConfigValue(
  config: ConfigFormData,
  fieldName: string,
  entries: SwitchOverEntry[]
): ConfigFormData {
  return {
    ...config,
    [fieldName]: { fieldName, value: switchOverEntriesToConfigValue(entries) },
  };
}

export function formatBasisPoints(value: string): string {
  if (!value) return '';
  const padded = value.padStart(5, '0');
  const integerPart = padded.slice(0, -4);
  const decimalPart = padded.slice(-4);
  return `${integerPart}_${decimalPart}`;
}

export function getSvRewardWeight(svs: [string, SvInfo][], svPartyId: string): string {
  const svInfo = svs.find(sv => sv[0] === svPartyId);
  return svInfo ? svInfo[1].svRewardWeight : '';
}

export function activityWeightToOptional(weight: string): string | null {
  return weight.trim() === '' ? null : weight;
}

export function buildPendingConfigFields(
  proposals: Contract<VoteRequest>[] | undefined
): PendingConfigFieldInfo[] {
  if (!proposals?.length) {
    return [];
  }

  return proposals
    .filter(proposal => {
      const a = proposal.payload.action;
      return a.tag === 'ARC_DsoRules' && a.value.dsoAction.tag === 'SRARC_SetConfig';
    })
    .flatMap(proposal => {
      const dsoAction = (proposal.payload.action.value as ActionRequiringConfirmation.ARC_DsoRules)
        .dsoAction.value as DsoRules_SetConfig;
      const changes = buildDsoConfigChanges(dsoAction.baseConfig, dsoAction.newConfig);

      return changes.map(change => ({
        fieldName: change.fieldName,
        pendingValue: change.newValue as string,
        proposalCid: proposal.contractId,
        effectiveDate: proposal.payload.targetEffectiveAt
          ? proposal.payload.targetEffectiveAt
          : 'Threshold',
      }));
    });
}

export function buildAmuletRulesPendingConfigFields(
  proposals: Contract<VoteRequest>[] | undefined
): PendingConfigFieldInfo[] {
  if (!proposals?.length) {
    return [];
  }

  return proposals
    .filter(proposal => {
      const a = proposal.payload.action;
      return a.tag === 'ARC_AmuletRules' && a.value.amuletRulesAction.tag === 'CRARC_SetConfig';
    })
    .flatMap(proposal => {
      const amuletAction = (
        proposal.payload.action.value as ActionRequiringConfirmation.ARC_AmuletRules
      ).amuletRulesAction.value as AmuletRules_SetConfig;
      const changes = buildAmuletConfigChanges(amuletAction.baseConfig, amuletAction.newConfig);

      return changes.map(change => ({
        fieldName: change.fieldName,
        pendingValue: change.newValue as string,
        proposalCid: proposal.contractId,
        effectiveDate: proposal.payload.targetEffectiveAt
          ? proposal.payload.targetEffectiveAt
          : 'Threshold',
      }));
    });
}
