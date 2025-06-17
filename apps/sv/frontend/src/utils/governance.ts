import { VoteRequestOutcome } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { VoteListingStatus } from '../components/governance/VotesListingSection';
import { SupportedActionTag } from '../routes/governance';
import dayjs from 'dayjs';

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
