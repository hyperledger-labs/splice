// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import {
  ActionRequiringConfirmation,
  AmuletRules_ActionRequiringConfirmation,
  DsoRules_ActionRequiringConfirmation,
  SvInfo,
  Vote,
  VoteRequestOutcome,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import dayjs, { Dayjs } from 'dayjs';
import {
  AmuletRulesConfigProposal,
  DsoRulesConfigProposal,
  FeatureAppProposal,
  OffBoardMemberProposal,
  Proposal,
  SupportedActionTag,
  UnfeatureAppProposal,
  UpdateSvRewardWeightProposal,
  ProposalListingStatus,
  YourVoteStatus,
  ConfigFormData,
  ConfigChange,
} from '../utils/types';
import { buildDsoConfigChanges } from './buildDsoConfigChanges';
import { buildAmuletConfigChanges } from './buildAmuletConfigChanges';
import { DsoInfo } from '@lfdecentralizedtrust/splice-common-frontend';

export const actionTagToTitle = (amuletName: string): Record<SupportedActionTag, string> => ({
  CRARC_AddFutureAmuletConfigSchedule: `Add Future ${amuletName} Configuration Schedule`,
  CRARC_SetConfig: `Set ${amuletName} Rules Configuration`,
  SRARC_GrantFeaturedAppRight: 'Feature Application',
  SRARC_OffboardSv: 'Offboard Member',
  SRARC_RevokeFeaturedAppRight: 'Unfeature Application',
  SRARC_SetConfig: 'Set Dso Rules Configuration',
  SRARC_UpdateSvRewardWeight: 'Update SV Reward Weight',
});

export const createProposalActions: { name: string; value: SupportedActionTag }[] = [
  { name: 'Offboard Member', value: 'SRARC_OffboardSv' },
  { name: 'Feature Application', value: 'SRARC_GrantFeaturedAppRight' },
  { name: 'Unfeature Application', value: 'SRARC_RevokeFeaturedAppRight' },
  { name: 'Set Dso Rules Configuration', value: 'SRARC_SetConfig' },
  { name: 'Set Amulet Rules Configuration', value: 'CRARC_SetConfig' },
  { name: 'Update SV Reward Weight', value: 'SRARC_UpdateSvRewardWeight' },
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

export function computeVoteStats(votes: Vote[]): { accepted: number; rejected: number } {
  return votes.reduce(
    (acc, vote) => ({
      accepted: acc.accepted + (vote.accept ? 1 : 0),
      rejected: acc.rejected + (vote.accept ? 0 : 1),
    }),
    { accepted: 0, rejected: 0 }
  );
}

export function computeYourVote(votes: Vote[], svPartyId: string | undefined): YourVoteStatus {
  if (svPartyId === undefined) {
    return 'no-vote';
  }

  const vote = votes.find(vote => vote.sv === svPartyId);
  return vote ? (vote.accept ? 'accepted' : 'rejected') : 'no-vote';
}

export function buildProposal(action: ActionRequiringConfirmation, dsoInfo?: DsoInfo): Proposal {
  if (action.tag === 'ARC_DsoRules') {
    const dsoAction = action.value.dsoAction;
    switch (dsoAction.tag) {
      case 'SRARC_OffboardSv':
        return {
          memberToOffboard: dsoAction.value.sv,
        } as OffBoardMemberProposal;
      case 'SRARC_UpdateSvRewardWeight': {
        const allSvInfos = dsoInfo?.dsoRules.payload.svs.entriesArray() || [];
        const svPartyId = dsoInfo?.svPartyId || '';
        const currentWeight = getSvRewardWeight(allSvInfos, svPartyId);

        return {
          svToUpdate: dsoAction.value.svParty,
          currentWeight: currentWeight,
          weightChange: dsoAction.value.newRewardWeight,
        } as UpdateSvRewardWeightProposal;
      }
      case 'SRARC_GrantFeaturedAppRight':
        return {
          provider: dsoAction.value.provider,
        } as FeatureAppProposal;
      case 'SRARC_RevokeFeaturedAppRight':
        return {
          rightContractId: dsoAction.value.rightCid,
        } as UnfeatureAppProposal;
      case 'SRARC_SetConfig':
        return {
          configChanges: buildDsoConfigChanges(
            dsoAction.value.baseConfig,
            dsoAction.value.newConfig
          ),
        } as DsoRulesConfigProposal;
    }
  } else if (action.tag === 'ARC_AmuletRules') {
    const amuletAction = action.value.amuletRulesAction;
    switch (amuletAction.tag) {
      case 'CRARC_SetConfig':
        return {
          configChanges: buildAmuletConfigChanges(
            amuletAction.value.baseConfig,
            amuletAction.value.newConfig
          ),
        } as AmuletRulesConfigProposal;
    }
  }
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

export function getSvRewardWeight(svs: [string, SvInfo][], svPartyId: string): string {
  const svInfo = svs.find(sv => sv[0] === svPartyId);
  return svInfo ? svInfo[1].svRewardWeight : '';
}
