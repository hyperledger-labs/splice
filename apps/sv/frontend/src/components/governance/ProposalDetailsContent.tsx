// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import {
  AmuletRules_ActionRequiringConfirmation,
  DsoRules_ActionRequiringConfirmation,
  VoteRequest,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { ContractId } from '@daml/types';
import { ChevronLeft } from '@mui/icons-material';
import { Box, Button, Chip, Link, Stack, Tab, Tabs, Typography } from '@mui/material';
import React, { PropsWithChildren, useMemo, useState } from 'react';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import {
  getAmuletConfigToCompareWith,
  getDsoConfigToCompareWith,
  PartyId,
  PrettyJsonDiff,
  theme,
  useVotesHooks,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { sanitizeUrl } from '@lfdecentralizedtrust/splice-common-frontend-utils';
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
import { MemberIdentifier } from '../beta';

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

  const handleVoteTabChange = (_event: React.SyntheticEvent, newValue: VoteTab) => {
    setVoteTabValue(newValue);
  };

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

        <Stack sx={{ bgcolor: '#1b1b1b', p: 6 }} alignItems="center" gap={8}>
          <VoteSection title="Proposal Details">
            <DetailItem
              label="Action"
              value={proposalDetails.actionName}
              labelId="proposal-details-action-label"
              valueId="proposal-details-action-value"
            />

            <DetailItem
              label="Contract ID"
              value={
                <MemberIdentifier
                  partyId={contractId}
                  isYou={false}
                  size="large"
                  data-testid="proposal-details-contractid-value"
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
                <ConfigValuesChanges changes={proposalDetails.proposal.configChanges} />
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
                <ConfigValuesChanges changes={proposalDetails.proposal.configChanges} />
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
                <Link
                  href={sanitizeUrl(proposalDetails.url)}
                  target="_blank"
                  color="primary"
                  data-testid="proposal-details-url-value"
                >
                  {sanitizeUrl(proposalDetails.url)}
                </Link>
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
              sx={{ display: 'flex', flexDirection: 'column', gap: 2, mb: 4 }}
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

          <VoteSection title="Your vote" data-testid="proposal-details-your-vote">
            {proposalDetails.isVoteRequest && !isClosed && (
              <ProposalVoteForm
                voteRequestContractId={contractId}
                currentSvPartyId={currentSvPartyId}
                votes={votes}
              />
            )}
          </VoteSection>
        </Stack>
      </Box>
    </Box>
  );
};

interface VoteSectionProps extends PropsWithChildren {
  title: string;
  'data-testid': string;
}

const VoteSection: React.FC<VoteSectionProps> = ({ title, children, 'data-testid': testId }) => (
  <Box sx={{ width: '100%', maxWidth: '800px' }} data-testid={testId}>
    <VoteSectionHeader content={title} />
    <Stack gap={'24px'}>{children}</Stack>
  </Box>
);

interface VoteSectionHeaderProps {
  content: string;
}

const VoteSectionHeader: React.FC<VoteSectionHeaderProps> = ({ content }) => (
  <Typography component="h2" fontSize={18} fontWeight={700} fontFamily="lato" mb={3}>
    {content}
  </Typography>
);

interface VoteItemProps {
  voter: string;
  url: string;
  comment: string;
  status: VoteStatus;
  isClosed?: boolean;
  isYou?: boolean;
}

const VoteItem = ({ voter, url, comment, status, isClosed, isYou = false }: VoteItemProps) => {
  const getStatusColor = () => {
    switch (status) {
      case 'accepted':
        return theme.palette.success.main;
      case 'rejected':
        return theme.palette.error.main;
      case 'no-vote':
        return isClosed ? theme.palette.error.main : '#ff9800';
      default:
        return '#757575';
    }
  };

  const getStatusText = () => {
    switch (status) {
      case 'accepted':
        return 'Accepted';
      case 'rejected':
        return 'Rejected';
      case 'no-vote':
        return isClosed ? 'No Vote' : 'Awaiting Response';
    }
  };

  return (
    <Box
      sx={{
        p: 2,
        border: '1px solid rgba(81, 81, 81, 1)',
        borderRadius: 2,
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'flex-start',
      }}
      data-testid="proposal-details-vote"
    >
      <Box sx={{ flexGrow: 1 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', mb: 1 }}>
          <PartyId
            partyId={voter}
            className="proposal-details-voter-party-id"
            id="proposal-details-voter-party-id"
          />
          {isYou && (
            <Chip
              label="You"
              size="small"
              sx={{
                ml: 1,
                bgcolor: 'rgba(255, 255, 255, 0.1)',
              }}
              data-testid="proposal-details-your-vote-chip"
            />
          )}
        </Box>
        {comment && (
          <Typography variant="body2" color="text.secondary">
            {comment}
          </Typography>
        )}
        {url && (
          <Typography variant="body2" color="text.secondary">
            <Link href={sanitizeUrl(url)} target="_blank" color="primary">
              {sanitizeUrl(url)}
            </Link>
          </Typography>
        )}
      </Box>
      <Box sx={{ display: 'flex', alignItems: 'center' }}>
        <Box
          component="span"
          sx={{
            display: 'inline-block',
            width: 16,
            height: 16,
            borderRadius: '50%',
            bgcolor: getStatusColor(),
            mr: 1,
          }}
        />
        <Typography
          variant="body2"
          color={getStatusColor()}
          data-testid="proposal-details-vote-status-value"
        >
          {getStatusText()}
        </Typography>
      </Box>
    </Box>
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
    >
      <DetailItem
        label="Contract ID"
        value={rightContractId}
        labelId="proposal-details-unfeature-app-label"
        valueId="proposal-details-unfeature-app-value"
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
    </>
  );
};
