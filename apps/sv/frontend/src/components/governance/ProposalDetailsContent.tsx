// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import {
  AmuletRules_ActionRequiringConfirmation,
  DsoRules_ActionRequiringConfirmation,
  VoteRequest,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { ContractId } from '@daml/types';
import { ChevronLeft, ContentCopy, Edit } from '@mui/icons-material';
import {
  Alert,
  Box,
  Button,
  Chip,
  Divider,
  IconButton,
  Stack,
  Tab,
  Tabs,
  Typography,
} from '@mui/material';
import React, { PropsWithChildren, useEffect, useMemo, useRef, useState } from 'react';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import {
  getAmuletConfigToCompareWith,
  getDsoConfigToCompareWith,
  PrettyJsonDiff,
  useVotesHooks,
} from '@canton-network/splice-common-frontend';
import { sanitizeUrl } from '@canton-network/splice-common-frontend-utils';
import { Link as RouterLink } from 'react-router';
import {
  ConfigChange,
  ProposalDetails,
  ProposalVote,
  ProposalVotingInformation,
  VoteStatus,
} from '../../utils/types';
import { ProposalVoteForm } from './ProposalVoteForm';
import { ConfigValuesChanges } from './ConfigValuesChanges';
import { JsonDiffAccordion } from './JsonDiffAccordion';
import { useDsoInfos } from '../../contexts/SvContext';
import { DetailItem } from './proposal-details/DetailItem';
import { CreateUnallocatedUnclaimedActivityRecordSection } from './proposal-details/CreateUnallocatedUnclaimedActivityRecordSection';
import { CopyableIdentifier, CopyableUrl, MemberIdentifier, VoteStats } from '../ui';
import { useQuery } from '@tanstack/react-query';
import { useSvAdminClient } from '../../contexts/SvAdminServiceContext';
import {
  DEFAULT_APP_ACTIVITY_WEIGHT,
  EFFECTIVE_AT_LABEL,
  PROPOSAL_CREATED_LABEL,
  PROPOSAL_SUMMARY_TITLE,
  SUPPORTING_URL_LABEL,
  THRESHOLD_DEADLINE_LABEL,
  VOTE_PROPOSAL_CONTRACT_ID_LABEL,
  VOTE_REASON_SUMMARY_LABEL,
  VOTE_REASON_URL_LABEL,
} from '../../utils/constants';
import { formatDatetimeWithOffset } from '../../utils/dateFormat';

/** True when a proposal changed fields that are locked/disabled in the create UI (e.g. emergency API). */
export function hasAlteredDisabledFields(changes: ConfigChange[]): boolean {
  return changes.some(c => c.disabled && c.currentValue !== c.newValue);
}

dayjs.extend(relativeTime);

export interface ProposalDetailsContentProps {
  currentSvPartyId: string;
  contractId: ContractId<VoteRequest>;
  proposalDetails: ProposalDetails;
  votingInformation: ProposalVotingInformation;
  votes: ProposalVote[];
}

type VoteTab = Extract<VoteStatus, 'accepted' | 'rejected' | 'no-vote'> | 'all';

export const ProposalDetailsContent: React.FC<ProposalDetailsContentProps> = props => {
  const { contractId, proposalDetails, votingInformation, votes, currentSvPartyId } = props;

  const votesHooks = useVotesHooks();
  const dsoInfoQuery = useDsoInfos();

  const isClosed = !proposalDetails.isVoteRequest || votingInformation.status === 'Rejected';

  const dsoConfigToCompareWith = useMemo(() => {
    if (proposalDetails.action === 'SRARC_SetConfig') {
      const dsoAction: DsoRules_ActionRequiringConfirmation = {
        tag: 'SRARC_SetConfig',
        value: {
          baseConfig: proposalDetails.proposal.baseConfig,
          newConfig: proposalDetails.proposal.newConfig,
        },
      };
      return getDsoConfigToCompareWith(
        dayjs(votingInformation.voteTakesEffect).toDate(),
        undefined,
        votesHooks,
        dsoAction,
        dsoInfoQuery
      );
    }
    return undefined;
  }, [
    proposalDetails.action,
    proposalDetails.proposal,
    votingInformation.voteTakesEffect,
    votesHooks,
    dsoInfoQuery,
  ]);

  const amuletConfigToCompareWith = useMemo(() => {
    if (proposalDetails.action === 'CRARC_SetConfig') {
      const dsoAction: AmuletRules_ActionRequiringConfirmation = {
        tag: 'CRARC_SetConfig',
        value: {
          baseConfig: proposalDetails.proposal.baseConfig,
          newConfig: proposalDetails.proposal.newConfig,
        },
      };
      return getAmuletConfigToCompareWith(
        dayjs(votingInformation.voteTakesEffect).toDate(),
        undefined,
        votesHooks,
        dsoAction,
        dsoInfoQuery
      );
    }
    return undefined;
  }, [
    proposalDetails.action,
    proposalDetails.proposal,
    votingInformation.voteTakesEffect,
    votesHooks,
    dsoInfoQuery,
  ]);

  const [voteTabValue, setVoteTabValue] = useState<VoteTab>('all');
  const [editFormKey, setEditFormKey] = useState(0);
  const [voteSubmitted, setVoteSubmitted] = useState(false);
  const yourVoteSectionRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (editFormKey > 0) {
      yourVoteSectionRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  }, [editFormKey]);

  const handleVoteTabChange = (_event: React.SyntheticEvent, newValue: VoteTab) => {
    setVoteTabValue(newValue);
  };

  const yourVote = votes.find(vote => vote.sv === currentSvPartyId);
  const hasVoted = yourVote?.vote === 'accepted' || yourVote?.vote === 'rejected';
  const isEditingVote = editFormKey > 0;
  const showVoteForm =
    proposalDetails.isVoteRequest && !isClosed && (!hasVoted || isEditingVote || voteSubmitted);

  const { acceptedVotes, rejectedVotes, awaitingVotes } = votes.reduce(
    (acc, vote) => {
      switch (vote.vote) {
        case 'accepted':
          acc.acceptedVotes.push(vote);
          break;
        case 'rejected':
          acc.rejectedVotes.push(vote);
          break;
        case 'no-vote':
          acc.awaitingVotes.push(vote);
          break;
      }
      return acc;
    },
    {
      acceptedVotes: [] as typeof votes,
      rejectedVotes: [] as typeof votes,
      awaitingVotes: [] as typeof votes,
    }
  );

  // Filter votes based on selected tab
  const getFilteredVotes = () => {
    switch (voteTabValue) {
      case 'accepted':
        return acceptedVotes;
      case 'rejected':
        return rejectedVotes;
      case 'no-vote':
        return awaitingVotes;
      case 'all':
      default:
        return votes;
    }
  };

  return (
    <Box sx={{ width: '100%' }}>
      <Stack direction="row" alignItems="center" justifyContent="space-between" mb="14px">
        <Typography
          variant="h4"
          fontSize={20}
          fontWeight={700}
          data-testid="proposal-details-title"
        >
          Proposal Details
        </Typography>
        <Button
          component={RouterLink}
          to="/governance/proposals"
          size="small"
          color="secondary"
          startIcon={<ChevronLeft fontSize="small" />}
          data-testid="proposal-details-back-to-all-votes"
        >
          Back to all votes
        </Button>
      </Stack>

      <Stack sx={{ bgcolor: 'colors.neutral.10', p: 6 }} alignItems="center" gap={8}>
        {/* Figma details content starts at Action — no inner section title. */}
        <VoteSection data-testid="proposal-details-proposal-details">
          <DetailItem
            label="Action"
            value={proposalDetails.actionName}
            labelId="proposal-details-action-label"
            valueId="proposal-details-action-value"
          />

          {proposalDetails.action === 'SRARC_OffboardSv' && (
            <OffboardMemberSection memberPartyId={proposalDetails.proposal.memberToOffboard} />
          )}

          {proposalDetails.action === 'SRARC_GrantFeaturedAppRight' && (
            <FeatureAppSection
              provider={proposalDetails.proposal.provider}
              activityWeight={proposalDetails.proposal.activityWeight}
            />
          )}

          {proposalDetails.action === 'SRARC_RevokeFeaturedAppRight' && (
            <UnfeatureAppSection rightContractId={proposalDetails.proposal.rightContractId} />
          )}

          {proposalDetails.action === 'SRARC_UpdateFeaturedAppRight' && (
            <UpdateFeatureAppSection
              rightContractId={proposalDetails.proposal.rightContractId}
              newActivityWeight={proposalDetails.proposal.newActivityWeight}
            />
          )}

          {proposalDetails.action === 'SRARC_UpdateSvRewardWeight' && (
            <UpdateSvRewardWeightSection
              svToUpdate={proposalDetails.proposal.svToUpdate}
              currentWeight={proposalDetails.proposal.currentWeight}
              weightChange={proposalDetails.proposal.weightChange}
            />
          )}

          {proposalDetails.action === 'SRARC_CreateUnallocatedUnclaimedActivityRecord' && (
            <CreateUnallocatedUnclaimedActivityRecordSection
              beneficiary={proposalDetails.proposal.beneficiary}
              amount={proposalDetails.proposal.amount}
              mintBefore={proposalDetails.proposal.mintBefore}
            />
          )}

          {proposalDetails.action === 'CRARC_SetConfig' && (
            <>
              {hasAlteredDisabledFields(proposalDetails.proposal.configChanges) && (
                <Alert
                  severity="warning"
                  variant="outlined"
                  data-testid="proposal-details-disabled-fields-warning"
                >
                  Disabled fields have been altered in this vote proposal.
                </Alert>
              )}
              <DetailItem
                label="Proposed Changes"
                value={<ConfigValuesChanges changes={proposalDetails.proposal.configChanges} />}
              />
              <JsonDiffAccordion variant="review">
                {amuletConfigToCompareWith ? (
                  <PrettyJsonDiff
                    changes={{
                      newConfig: proposalDetails.proposal.newConfig,
                      baseConfig:
                        proposalDetails.proposal.baseConfig || amuletConfigToCompareWith[1],
                      actualConfig: amuletConfigToCompareWith[1],
                    }}
                  />
                ) : null}
              </JsonDiffAccordion>
            </>
          )}

          {proposalDetails.action === 'SRARC_SetConfig' && (
            <>
              {hasAlteredDisabledFields(proposalDetails.proposal.configChanges) && (
                <Alert
                  severity="warning"
                  variant="outlined"
                  data-testid="proposal-details-disabled-fields-warning"
                >
                  Disabled fields have been altered in this vote proposal.
                </Alert>
              )}
              <DetailItem
                label="Proposed Changes"
                value={<ConfigValuesChanges changes={proposalDetails.proposal.configChanges} />}
              />
              <JsonDiffAccordion variant="review">
                {dsoConfigToCompareWith?.[1] ? (
                  <PrettyJsonDiff
                    changes={{
                      newConfig: proposalDetails.proposal.newConfig,
                      baseConfig: proposalDetails.proposal.baseConfig || dsoConfigToCompareWith[1],
                      actualConfig: dsoConfigToCompareWith[1],
                    }}
                  />
                ) : null}
              </JsonDiffAccordion>
            </>
          )}

          <DetailItem
            label={PROPOSAL_SUMMARY_TITLE}
            value={proposalDetails.summary}
            labelId="proposal-details-summary-label"
            valueId="proposal-details-summary-value"
          />

          <DetailItem
            label={SUPPORTING_URL_LABEL}
            value={
              <CopyableUrl
                url={proposalDetails.url}
                size="large"
                fullWidth
                data-testid="proposal-details-url"
              />
            }
            labelId="proposal-details-url-label"
          />

          <DetailItem
            label={VOTE_PROPOSAL_CONTRACT_ID_LABEL}
            value={
              <CopyableIdentifier
                value={contractId}
                size="large"
                fullWidth
                data-testid="proposal-details-contractid-id"
              />
            }
            labelId="proposal-details-contractid-label"
          />
        </VoteSection>

        <VoteSection title="Proposal Information" data-testid="proposal-details-voting-information">
          <DetailItem
            label="Requester"
            value={
              <MemberIdentifier
                partyId={votingInformation.requester}
                isYou={false}
                size="large"
                fullWidth
                data-testid="proposal-details-requester-party-id"
              />
            }
          />

          {proposalDetails.createdAt && (
            <DetailItem
              label={PROPOSAL_CREATED_LABEL}
              value={formatDatetimeWithOffset(proposalDetails.createdAt)}
              labelId="proposal-details-created-at-label"
              valueId="proposal-details-created-at-value"
            />
          )}

          <DetailItem
            label={THRESHOLD_DEADLINE_LABEL}
            labelId="proposal-details-threshold-deadline-label"
            value={
              <Stack gap={3}>
                <Box data-testid="proposal-details-voting-closes-duration">
                  {dayjs(votingInformation.votingThresholdDeadline).fromNow()}
                </Box>
                <Box data-testid="proposal-details-voting-closes-value">
                  {formatDatetimeWithOffset(votingInformation.votingThresholdDeadline)}
                </Box>
              </Stack>
            }
            valueId="proposal-details-voting-closes-duration"
          />

          <DetailItem
            label={EFFECTIVE_AT_LABEL}
            labelId="proposal-details-effective-at-label"
            value={
              <Stack gap={3}>
                <Box data-testid="proposal-details-vote-takes-effect-duration">
                  {votingInformation.voteTakesEffect === 'Threshold'
                    ? 'Threshold'
                    : dayjs(votingInformation.voteTakesEffect).fromNow()}
                </Box>
                {votingInformation.voteTakesEffect !== 'Threshold' && (
                  <Box data-testid="proposal-details-vote-takes-effect-value">
                    {formatDatetimeWithOffset(votingInformation.voteTakesEffect)}
                  </Box>
                )}
              </Stack>
            }
            valueId="proposal-details-vote-takes-effect-duration"
          />

          <DetailItem
            label="Status"
            value={votingInformation.status}
            labelId="proposal-details-status-label"
            valueId="proposal-details-status-value"
          />
        </VoteSection>

        <VoteSection title="Votes" data-testid="proposal-details-votes">
          <Tabs
            value={voteTabValue}
            onChange={handleVoteTabChange}
            aria-label="vote tabs"
            data-testid="votes-tabs"
            sx={{
              // after experimenting with it a little, this is probably the best way to put something akin to borderBottom
              // inside of the <Tab> components so it doesn't interfere with <Tabs> overflow: hidden property
              boxShadow: 'inset 0 -2px 0 0 rgba(255, 255, 255, 0.12)',
              '& .MuiTabs-indicator': {
                backgroundColor: 'colors.tertiary',
                height: '2px',
              },
            }}
          >
            <Tab label={`All (${votes.length})`} value="all" data-testid="all-votes-tab" />
            <Tab
              label={`Accepted (${acceptedVotes.length})`}
              value="accepted"
              data-testid="accepted-votes-tab"
            />
            <Tab
              label={`Rejected (${rejectedVotes.length})`}
              value="rejected"
              data-testid="rejected-votes-tab"
            />
            <Tab
              label={
                (isClosed ? 'Did not Vote' : 'Awaiting Response') +
                ' (' +
                awaitingVotes.length +
                ')'
              }
              value="no-vote"
              data-testid="no-vote-votes-tab"
            />
          </Tabs>

          <Box
            sx={{ display: 'flex', flexDirection: 'column', gap: 3, width: '100%', minWidth: 0 }}
            data-testid="proposal-details-votes-list"
          >
            {getFilteredVotes().map((vote, index) => (
              <VoteItem
                key={`${vote.vote}-${index}`}
                voter={vote.sv}
                url={vote.reason?.url || ''}
                comment={vote.reason?.body || ''}
                status={vote.vote}
                isYou={vote.isYou}
                isClosed={isClosed}
                onEdit={
                  vote.isYou && hasVoted && !isClosed ? () => setEditFormKey(k => k + 1) : undefined
                }
              />
            ))}
            {getFilteredVotes().length === 0 && (
              <Box sx={{ textAlign: 'center', py: 4 }}>
                <Typography variant="body2" color="text.secondary">
                  No votes found for this category.
                </Typography>
              </Box>
            )}
          </Box>
        </VoteSection>

        {showVoteForm && (
          <VoteSection
            title="Your Vote"
            data-testid="proposal-details-your-vote"
            bordered
            centered
            ref={yourVoteSectionRef}
          >
            <ProposalVoteForm
              key={editFormKey}
              voteRequestContractId={contractId}
              currentSvPartyId={currentSvPartyId}
              onSubmissionStart={() => setVoteSubmitted(true)}
              votes={votes}
            />
          </VoteSection>
        )}
      </Stack>
    </Box>
  );
};

interface VoteSectionProps extends PropsWithChildren {
  /** Section heading (e.g. Proposal Information). Omit when Figma has no heading above the fields. */
  title?: string;
  'data-testid': string;
  bordered?: boolean;
  centered?: boolean;
}

const VoteSection = React.forwardRef<HTMLDivElement, VoteSectionProps>(
  ({ title, children, 'data-testid': testId, bordered = false, centered = false }, ref) => (
    <Box sx={{ width: '100%', maxWidth: '800px' }} data-testid={testId} ref={ref}>
      {title !== undefined && (
        <Typography component="h2" fontSize={18} fontWeight={700} mb={3}>
          {title}
        </Typography>
      )}
      <Box
        sx={{
          ...(bordered && {
            border: '2px solid',
            borderColor: 'divider',
            borderRadius: 2,
            py: 5,
            px: 12,
          }),
        }}
      >
        <Stack gap={3} alignItems={centered ? 'center' : undefined}>
          {children}
        </Stack>
      </Box>
    </Box>
  )
);
VoteSection.displayName = 'VoteSection';

interface VoteItemProps {
  voter: string;
  url: string;
  comment: string;
  status: VoteStatus;
  isClosed?: boolean;
  isYou?: boolean;
  onEdit?: () => void;
}

/** Gap between party-ID / You and the copy icon. */
const VOTE_ROW_ACCESSORY_GAP_PX = 8;
/** Gap between copy icon and status column. */
const VOTE_ROW_STATUS_GAP_PX = 40;
/** Fixed copy column so every row’s copy icon shares one vertical edge. */
const VOTE_ROW_COPY_COL_WIDTH_PX = 40;
/** Fixed status column so Accepted / Awaiting Response share the right edge. */
const VOTE_ROW_STATUS_COL_WIDTH_PX = 170;
/** Trailing tracks (8 + copy + 40 + status) — party-ID (+ You) width is calc(100% − this). */
const VOTE_ROW_FIXED_TRAILING_PX =
  VOTE_ROW_ACCESSORY_GAP_PX +
  VOTE_ROW_COPY_COL_WIDTH_PX +
  VOTE_ROW_STATUS_GAP_PX +
  VOTE_ROW_STATUS_COL_WIDTH_PX;

const VoteItem: React.FC<VoteItemProps> = ({
  voter,
  url,
  comment,
  status,
  isClosed,
  isYou = false,
  onEdit,
}) => {
  const urlGridRow = comment ? 3 : 2;

  return (
    <>
      <Box
        sx={{
          display: 'grid',
          // Copy + status are fixed tracks. "You" lives in the party-ID track (before the
          // 8px gap) so it never shifts the copy icon.
          gridTemplateColumns: `minmax(0, calc(100% - ${VOTE_ROW_FIXED_TRAILING_PX}px)) ${VOTE_ROW_ACCESSORY_GAP_PX}px ${VOTE_ROW_COPY_COL_WIDTH_PX}px ${VOTE_ROW_STATUS_GAP_PX}px ${VOTE_ROW_STATUS_COL_WIDTH_PX}px`,
          alignItems: 'start',
          width: '100%',
          maxWidth: '100%',
          minWidth: 0,
          overflow: 'hidden',
          boxSizing: 'border-box',
        }}
        data-testid="proposal-details-vote"
      >
        <Box
          sx={{
            gridColumn: 1,
            gridRow: 1,
            minWidth: 0,
            maxWidth: '100%',
            overflow: 'hidden',
          }}
        >
          <Stack
            direction="row"
            alignItems="center"
            spacing={1}
            sx={{ minWidth: 0, maxWidth: '100%', width: '100%' }}
          >
            <Box sx={{ flex: '1 1 0%', minWidth: 0, overflow: 'hidden' }}>
              <CopyableIdentifier
                value={voter}
                copyValue={voter}
                size="large"
                fullWidth
                hideCopy
                data-testid="proposal-details-voter-party-id"
              />
            </Box>
            {isYou && (
              <Chip
                label="You"
                size="small"
                data-testid="proposal-details-voter-party-id-badge"
                sx={{ flexShrink: 0 }}
              />
            )}
          </Stack>
        </Box>

        {comment && (
          <Box
            sx={{
              gridColumn: 1,
              gridRow: 2,
              mt: 1,
              minWidth: 0,
              maxWidth: '100%',
              overflow: 'hidden',
            }}
          >
            <Typography variant="caption" color="text.secondary" display="block" mb={1}>
              {VOTE_REASON_SUMMARY_LABEL}
            </Typography>
            <Typography fontSize={16} color="text.secondary" sx={{ overflowWrap: 'anywhere' }}>
              {comment}
            </Typography>
          </Box>
        )}

        {url && (
          <Box
            sx={{
              gridColumn: 1,
              gridRow: urlGridRow,
              mt: 1,
              minWidth: 0,
              maxWidth: '100%',
              overflow: 'hidden',
            }}
          >
            <Typography variant="caption" color="text.secondary" display="block" mb={1}>
              {VOTE_REASON_URL_LABEL}
            </Typography>
            <CopyableUrl
              url={url}
              size="large"
              fullWidth
              hideCopy
              data-testid="proposal-details-vote-url"
            />
          </Box>
        )}

        <Box
          sx={{
            gridColumn: 3,
            gridRow: 1,
            display: 'flex',
            justifyContent: 'center',
            pt: '2px',
          }}
        >
          <IconButton
            color="secondary"
            data-testid="proposal-details-voter-party-id-copy-button"
            sx={{ flexShrink: 0, p: 0.5 }}
            onClick={e => {
              e.stopPropagation();
              e.preventDefault();
              navigator.clipboard.writeText(voter);
            }}
          >
            <ContentCopy sx={{ fontSize: '16px' }} />
          </IconButton>
        </Box>

        {url && (
          <Box
            sx={{
              gridColumn: 3,
              gridRow: urlGridRow,
              display: 'flex',
              justifyContent: 'center',
              // Align with the URL value row (below the caption + mb).
              mt: 1,
              pt: 'calc(1em + 8px + 2px)',
            }}
          >
            <IconButton
              color="secondary"
              data-testid="proposal-details-vote-url-copy-button"
              sx={{ flexShrink: 0, p: 0.5 }}
              onClick={e => {
                e.stopPropagation();
                e.preventDefault();
                navigator.clipboard.writeText(sanitizeUrl(url));
              }}
            >
              <ContentCopy sx={{ fontSize: '16px' }} />
            </IconButton>
          </Box>
        )}

        <Box
          sx={{
            gridColumn: 5,
            gridRow: 1,
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'flex-end',
            gap: 1,
            pt: '2px',
          }}
        >
          <VoteStats
            vote={status}
            noVoteMessage={isClosed ? 'No Vote' : 'Awaiting Response'}
            data-testid="proposal-details-vote-status"
          />
          {onEdit && (
            <Button
              color="secondary"
              startIcon={<Edit fontSize="small" />}
              onClick={onEdit}
              data-testid="your-vote-edit-button"
              sx={{
                fontSize: 16,
                minWidth: 0,
                px: 0,
              }}
            >
              Edit
            </Button>
          )}
        </Box>
      </Box>
      <Divider sx={{ borderBottomWidth: 2 }} />
    </>
  );
};

interface OffboardMemberSectionProps {
  memberPartyId: string;
}

const OffboardMemberSection = ({ memberPartyId }: OffboardMemberSectionProps) => {
  return (
    <Box
      id="proposal-details-offboard-member-section"
      data-testid="proposal-details-offboard-member-section"
      sx={{ display: 'contents' }}
    >
      <DetailItem
        label="Member"
        value={
          <MemberIdentifier
            partyId={memberPartyId}
            isYou={false}
            size="large"
            fullWidth
            data-testid="proposal-details-member-party-id"
          />
        }
      />
    </Box>
  );
};

interface FeatureAppSectionProps {
  provider: string;
  activityWeight: string;
}

const FeatureAppSection = ({ provider, activityWeight }: FeatureAppSectionProps) => {
  return (
    <Box
      id="proposal-details-feature-app-section"
      data-testid="proposal-details-feature-app-section"
      sx={{ display: 'contents' }}
    >
      <DetailItem
        label="Provider Party ID"
        value={
          <CopyableIdentifier
            value={provider}
            size="large"
            data-testid="proposal-details-feature-app-value"
          />
        }
        labelId="proposal-details-feature-app-label"
      />
      <DetailItem
        label="Activity Weight"
        value={activityWeight}
        labelId="proposal-details-feature-app-activity-weight-label"
        valueId="proposal-details-feature-app-activity-weight-value"
      />
    </Box>
  );
};

interface UnfeatureAppSectionProps {
  rightContractId: string;
}

const UnfeatureAppSection = ({ rightContractId }: UnfeatureAppSectionProps) => {
  const svAdminClient = useSvAdminClient();
  const providerQuery = useQuery({
    queryKey: ['featuredAppRightProvider', rightContractId],
    queryFn: async () => {
      const response = await svAdminClient.lookupFeaturedAppRightByContractId(rightContractId);
      const contract = response.featured_app_right;
      return (contract?.payload as { provider?: string } | undefined)?.provider ?? null;
    },
  });

  return (
    <Box
      id="proposal-details-unfeature-app-section"
      data-testid="proposal-details-unfeature-app-section"
      sx={{ display: 'contents' }}
    >
      {providerQuery.data && (
        <DetailItem
          label="Provider Party ID"
          value={
            <CopyableIdentifier
              value={providerQuery.data}
              size="large"
              data-testid="proposal-details-unfeature-provider-value"
            />
          }
          labelId="proposal-details-unfeature-provider-label"
        />
      )}
      <DetailItem
        label="Featured Application Contract ID"
        value={
          <CopyableIdentifier
            value={rightContractId}
            size="large"
            data-testid="proposal-details-unfeature-app-value"
          />
        }
        labelId="proposal-details-unfeature-app-label"
      />
    </Box>
  );
};

interface UpdateFeatureAppSectionProps {
  rightContractId: string;
  newActivityWeight: string;
}

const UpdateFeatureAppSection = ({
  rightContractId,
  newActivityWeight,
}: UpdateFeatureAppSectionProps) => {
  const svAdminClient = useSvAdminClient();
  const providerQuery = useQuery({
    queryKey: ['featuredAppRightProviderAndWeight', rightContractId],
    queryFn: async () => {
      const response = await svAdminClient.lookupFeaturedAppRightByContractId(rightContractId);
      const contract = response.featured_app_right;
      const payload = contract?.payload as
        | { provider?: string; activityWeight?: string | null }
        | undefined;
      return {
        provider: payload?.provider ?? null,
        currentWeight: contract ? (payload?.activityWeight ?? DEFAULT_APP_ACTIVITY_WEIGHT) : '',
      };
    },
  });
  return (
    <Box
      id="proposal-details-update-feature-app-section"
      data-testid="proposal-details-update-feature-app-section"
      sx={{ display: 'contents' }}
    >
      {providerQuery?.data?.provider && (
        <DetailItem
          label="Provider Party ID"
          value={
            <CopyableIdentifier
              value={providerQuery.data?.provider}
              size="large"
              data-testid="proposal-details-update-feature-value"
            />
          }
          labelId="proposal-details-update-feature-label"
        />
      )}
      <DetailItem
        label="Featured Application Contract ID"
        value={
          <CopyableIdentifier
            value={rightContractId}
            size="large"
            data-testid="proposal-details-update-feature-app-value"
          />
        }
        labelId="proposal-details-update-feature-app-label"
      />
      <DetailItem
        label="Proposed Changes"
        value={
          <ConfigValuesChanges
            changes={[
              {
                label: 'Activity Weight',
                fieldName: 'newActivityWeight',
                currentValue: providerQuery.data?.currentWeight ?? '',
                newValue: newActivityWeight,
              },
            ]}
          />
        }
      />
    </Box>
  );
};

interface UpdateSvRewardWeightSectionProps {
  svToUpdate: string;
  currentWeight: string;
  weightChange: string;
}

const UpdateSvRewardWeightSection = ({
  svToUpdate,
  currentWeight,
  weightChange,
}: UpdateSvRewardWeightSectionProps) => {
  return (
    <>
      <Box
        id="proposal-details-update-sv-reward-weight-section"
        data-testid="proposal-details-update-sv-reward-weight-section"
      >
        <DetailItem
          label="Member"
          value={
            <MemberIdentifier
              partyId={svToUpdate}
              isYou={false}
              size="large"
              fullWidth
              data-testid="proposal-details-member-party-id"
            />
          }
        />
      </Box>

      <DetailItem
        label="Proposed Changes"
        value={
          <ConfigValuesChanges
            changes={[
              {
                label: 'Weight',
                fieldName: 'svRewardWeight',
                currentValue: currentWeight,
                newValue: weightChange,
              },
            ]}
          />
        }
      />
    </>
  );
};
