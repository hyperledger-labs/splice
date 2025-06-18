import {
  ActionRequiringConfirmation,
  AmuletRules_ActionRequiringConfirmation,
  DsoRules_ActionRequiringConfirmation,
  Vote,
  VoteRequestOutcome,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import dayjs from 'dayjs';
import {
  AmuletRulesConfigProposal,
  DsoRulesConfigProposal,
  FeatureAppProposal,
  OffBoardMemberProposal,
  Proposal,
  SupportedActionTag,
  UnfeatureAppProposal,
  UpdateSvRewardWeightProposal,
  VoteListingStatus,
  YourVoteStatus,
} from '../utils/types';
import { buildDsoConfigChanges } from './buildDsoConfigChanges';
import { buildAmuletConfigChanges } from './buildAmuletConfigChanges';

export const actionTagToTitle = (amuletName: string): Record<SupportedActionTag, string> => ({
  CRARC_AddFutureAmuletConfigSchedule: `Add Future ${amuletName} Configuration Schedule`,
  CRARC_SetConfig: `Set ${amuletName} Rules Configuration`,
  SRARC_GrantFeaturedAppRight: 'Feature Application',
  SRARC_OffboardSv: 'Offboard Member',
  SRARC_RevokeFeaturedAppRight: 'Unfeature Application',
  SRARC_SetConfig: 'Set Dso Rules Configuration',
  SRARC_UpdateSvRewardWeight: 'Update SV Reward Weight',
});

export const getVoteResultStatus = (outcome: VoteRequestOutcome | undefined): VoteListingStatus => {
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

export function buildProposal(action: ActionRequiringConfirmation): Proposal {
  if (action.tag === 'ARC_DsoRules') {
    const dsoAction = action.value.dsoAction;
    switch (dsoAction.tag) {
      case 'SRARC_OffboardSv':
        return {
          memberToOffboard: dsoAction.value.sv,
        } as OffBoardMemberProposal;
      case 'SRARC_UpdateSvRewardWeight':
        return {
          svToUpdate: dsoAction.value.svParty,
          weightChange: dsoAction.value.newRewardWeight,
        } as UpdateSvRewardWeightProposal;
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
