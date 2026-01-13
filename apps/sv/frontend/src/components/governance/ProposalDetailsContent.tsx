// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import {
  AmuletRules_ActionRequiringConfirmation,
  DsoRules_ActionRequiringConfirmation,
  VoteRequest,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { ContractId } from '@daml/types';
import { ChevronLeft, Edit } from '@mui/icons-material';
import { Box, Button, Divider, Stack, Tab, Tabs, Typography } from '@mui/material';
import React, { PropsWithChildren, useMemo, useState } from 'react';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import {
  getAmuletConfigToCompareWith,
  getDsoConfigToCompareWith,
  PrettyJsonDiff,
  useVotesHooks,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { Link as RouterLink } from 'react-router-dom';
import {
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
import { CopyableIdentifier, CopyableUrl, MemberIdentifier, VoteStats } from '../beta';

dayjs.extend(relativeTime);

export interface ProposalDetailsContentProps {
  currentSvPartyId: string;
  contractId: ContractId<VoteRequest>;
  proposalDetails: ProposalDetails;
  votingInformation: ProposalVotingInformation;
  votes: ProposalVote[];
}

type VoteTab = Extract<VoteStatus, 'accepted' | 'rejected' | 'no-vote'> | 'all';

const now = () => dayjs();

export const ProposalDetailsContent: React.FC<ProposalDetailsContentProps> = props => {
  const { contractId, proposalDetails, votingInformation, votes, currentSvPartyId } = props;

  const votesHooks = useVotesHooks();
  const dsoInfoQuery = useDsoInfos();

  const isEffective =
    votingInformation.voteTakesEffect && dayjs(votingInformation.voteTakesEffect).isBefore(now());
  const isClosed =
    !proposalDetails.isVoteRequest || isEffective || votingInformation.status === 'Rejected';

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
    <Box sx={{ p: 4, display: 'flex', justifyContent: 'center' }}>
      <Box sx={{ width: '100%', p: 4 }}>
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
            to="/governance-beta/proposals"
            size="small"
            color="secondary"
            startIcon={<ChevronLeft fontSize="small" />}
          >
            Back to all votes
          </Button>
        </Stack>

        <Stack sx={{ bgcolor: 'colors.neutral.10', p: 6 }} alignItems="center" gap={8}>
          <VoteSection title="Proposal Details" data-testid="proposal-details-proposal-details">
            <DetailItem
              label="Action"
              value={proposalDetails.actionName}
              labelId="proposal-details-action-label"
              valueId="proposal-details-action-value"
            />

            <DetailItem
              label="Contract ID"
              value={
                <CopyableIdentifier
                  value={contractId}
                  size="large"
                  data-testid="proposal-details-contractid-id"
                />
              }
              labelId="proposal-details-contractid-label"
            />

            {proposalDetails.action === 'SRARC_OffboardSv' && (
              <OffboardMemberSection memberPartyId={proposalDetails.proposal.memberToOffboard} />
            )}

            {proposalDetails.action === 'SRARC_GrantFeaturedAppRight' && (
              <FeatureAppSection provider={proposalDetails.proposal.provider} />
            )}

            {proposalDetails.action === 'SRARC_RevokeFeaturedAppRight' && (
              <UnfeatureAppSection rightContractId={proposalDetails.proposal.rightContractId} />
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
                <DetailItem
                  label="Proposed Changes"
                  value={<ConfigValuesChanges changes={proposalDetails.proposal.configChanges} />}
                />
                <JsonDiffAccordion>
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
                <DetailItem
                  label="Proposed Changes"
                  value={<ConfigValuesChanges changes={proposalDetails.proposal.configChanges} />}
                />
                <JsonDiffAccordion>
                  {dsoConfigToCompareWith?.[1] ? (
                    <PrettyJsonDiff
                      changes={{
                        newConfig: proposalDetails.proposal.newConfig,
                        baseConfig:
                          proposalDetails.proposal.baseConfig || dsoConfigToCompareWith[1],
                        actualConfig: dsoConfigToCompareWith[1],
                      }}
                    />
                  ) : null}
                </JsonDiffAccordion>
              </>
            )}

            <DetailItem
              label="Summary"
              value={proposalDetails.summary}
              labelId="proposal-details-summary-label"
              valueId="proposal-details-summary-value"
            />

            <DetailItem
              label="URL"
              value={
                <CopyableUrl
                  url={proposalDetails.url}
                  size="large"
                  data-testid="proposal-details-url"
                />
              }
              labelId="proposal-details-url-label"
            />
          </VoteSection>

          <VoteSection title="Voting Information" data-testid="proposal-details-voting-information">
            <DetailItem
              label="Requester"
              value={
                <MemberIdentifier
                  partyId={votingInformation.requester}
                  isYou={false}
                  size="large"
                  data-testid="proposal-details-requester-party-id"
                />
              }
            />

            <DetailItem
              label="Threshold Deadline"
              value={
                <Stack gap={3}>
                  <Box data-testid="proposal-details-voting-closes-duration">
                    {dayjs(votingInformation.votingThresholdDeadline).fromNow()}
                  </Box>
                  <Box data-testid="proposal-details-voting-closes-value">
                    {votingInformation.votingThresholdDeadline}
                  </Box>
                </Stack>
              }
              valueId="proposal-details-voting-closes-duration"
            />

            <DetailItem
              label="Voting Takes Effect On"
              value={
                <Stack gap={3}>
                  <Box data-testid="proposal-details-vote-takes-effect-duration">
                    {votingInformation.voteTakesEffect === 'Threshold'
                      ? 'Threshold'
                      : dayjs(votingInformation.voteTakesEffect).fromNow()}
                  </Box>
                  {votingInformation.voteTakesEffect !== 'Threshold' && (
                    <Box data-testid="proposal-details-vote-takes-effect-value">
                      {votingInformation.voteTakesEffect}
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
              sx={{ display: 'flex', flexDirection: 'column', gap: 3 }}
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
                    vote.isYou && hasVoted && !isClosed
                      ? () => setEditFormKey(k => k + 1)
                      : undefined
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
            >
              <ProposalVoteForm
                key={editFormKey}
                voteRequestContractId={contractId}
                currentSvPartyId={currentSvPartyId}
                onSubmissionComplete={() => setVoteSubmitted(true)}
                votes={votes}
              />
            </VoteSection>
          )}
        </Stack>
      </Box>
    </Box>
  );
};

interface VoteSectionProps extends PropsWithChildren {
  title: string;
  'data-testid': string;
  bordered?: boolean;
  centered?: boolean;
}

const VoteSection: React.FC<VoteSectionProps> = ({
  title,
  children,
  'data-testid': testId,
  bordered = false,
  centered = false,
}) => (
  <Box sx={{ width: '100%', maxWidth: '800px' }} data-testid={testId}>
    <Typography component="h2" fontSize={18} fontWeight={700} fontFamily="lato" mb={3}>
      {title}
    </Typography>
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
);

interface VoteItemProps {
  voter: string;
  url: string;
  comment: string;
  status: VoteStatus;
  isClosed?: boolean;
  isYou?: boolean;
  onEdit?: () => void;
}

const VoteItem: React.FC<VoteItemProps> = ({
  voter,
  url,
  comment,
  status,
  isClosed,
  isYou = false,
  onEdit,
}) => (
  <>
    <Box
      sx={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
      }}
      data-testid="proposal-details-vote"
    >
      <Box sx={{ flexGrow: 1 }}>
        <Box sx={{ display: 'flex', alignItems: 'center' }}>
          <MemberIdentifier
            partyId={voter}
            size="large"
            isYou={isYou}
            data-testid="proposal-details-voter-party-id"
          />
        </Box>
        {comment && (
          <Typography fontSize={16} color="text.secondary">
            {comment}
          </Typography>
        )}
        {url && <CopyableUrl url={url} size="small" data-testid="proposal-details-vote-url" />}
      </Box>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 4 }}>
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
            data-testid="proposal-details-member-party-id"
          />
        }
      />
    </Box>
  );
};

interface FeatureAppSectionProps {
  provider: string;
}

const FeatureAppSection = ({ provider }: FeatureAppSectionProps) => {
  return (
    <Box
      id="proposal-details-feature-app-section"
      data-testid="proposal-details-feature-app-section"
      sx={{ display: 'contents' }}
    >
      <DetailItem
        label="Provider ID"
        value={provider}
        labelId="proposal-details-feature-app-label"
        valueId="proposal-details-feature-app-value"
      />
    </Box>
  );
};

interface UnfeatureAppSectionProps {
  rightContractId: string;
}

const UnfeatureAppSection = ({ rightContractId }: UnfeatureAppSectionProps) => {
  return (
    <Box
      id="proposal-details-unfeature-app-section"
      data-testid="proposal-details-unfeature-app-section"
      sx={{ display: 'contents' }}
    >
      <DetailItem
        label="Proposal ID"
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
