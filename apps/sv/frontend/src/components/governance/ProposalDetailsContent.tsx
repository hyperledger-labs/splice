// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { VoteRequest } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { ContractId } from '@daml/types';
import { ArrowBack } from '@mui/icons-material';
import { Box, Button, Chip, Divider, Link, Paper, Tab, Tabs, Typography } from '@mui/material';
import React, { useState } from 'react';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import { PartyId, theme } from '@lfdecentralizedtrust/splice-common-frontend';
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

  const isEffective =
    votingInformation.voteTakesEffect && dayjs(votingInformation.voteTakesEffect).isBefore(now());
  const isClosed =
    !proposalDetails.isVoteRequest || isEffective || votingInformation.status === 'Rejected';

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
        <Box sx={{ display: 'flex', alignItems: 'center', mb: 3 }}>
          <Typography variant="h4" sx={{ flexGrow: 1 }} data-testid="proposal-details-title">
            Proposal Details
          </Typography>
          <Button
            component={RouterLink}
            to="/governance-beta/proposals"
            size="small"
            startIcon={<ArrowBack fontSize="small" />}
            sx={{ color: 'text.secondary' }}
          >
            Back to all votes
          </Button>
        </Box>

        <Paper sx={{ bgcolor: 'background.paper', p: 6 }}>
          <Box sx={{ display: 'flex', flexDirection: 'column' }}>
            <DetailItem
              label="Action"
              value={proposalDetails.actionName}
              labelId="proposal-details-action-label"
              valueId="proposal-details-action-value"
            />
            <Divider sx={{ my: 1 }} />

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

            {proposalDetails.action === 'CRARC_SetConfig' && (
              <ConfigValuesChanges changes={proposalDetails.proposal.configChanges} />
            )}

            {proposalDetails.action === 'SRARC_SetConfig' && (
              <ConfigValuesChanges changes={proposalDetails.proposal.configChanges} />
            )}

            <Divider sx={{ my: 1 }} />

            <DetailItem
              label="Summary"
              value={proposalDetails.summary}
              labelId="proposal-details-summary-label"
              valueId="proposal-details-summary-value"
            />
            <Divider sx={{ my: 1 }} />

            <DetailItem
              label="URL"
              value={
                <Link href={sanitizeUrl(proposalDetails.url)} target="_blank" color="primary">
                  {sanitizeUrl(proposalDetails.url)}
                </Link>
              }
              labelId="proposal-details-url-label"
              valueId="proposal-details-url-value"
            />
          </Box>

          <Divider sx={{ mt: 1, mb: 8 }} />

          {/* Voting Information Section */}
          <Typography variant="h6" component="h2" gutterBottom sx={{ mb: 3 }}>
            Voting Information
          </Typography>

          <Box
            sx={{ display: 'flex', flexDirection: 'column' }}
            data-testid="proposal-details-voting-information"
          >
            <Box sx={{ py: 1 }}>
              <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                Requester
              </Typography>
              <Box sx={{ display: 'flex', alignItems: 'center' }}>
                <PartyId
                  partyId={votingInformation.requester}
                  className="proposal-details-requester-party-id"
                  id="proposal-details-requester-party-id"
                />
              </Box>
            </Box>
            <Divider sx={{ my: 1 }} />

            <Box sx={{ py: 1 }}>
              <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                Threshold Deadline
              </Typography>
              <Typography
                variant="body1"
                data-testid="proposal-details-voting-closes-duration"
                gutterBottom
              >
                {dayjs(votingInformation.votingThresholdDeadline).fromNow()}
              </Typography>
              <Typography
                variant="body2"
                color="text.secondary"
                data-testid="proposal-details-voting-closes-value"
              >
                {votingInformation.votingThresholdDeadline}
              </Typography>
            </Box>
            <Divider sx={{ my: 1 }} />

            <Box sx={{ py: 1 }}>
              <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                Voting Takes Effect On
              </Typography>
              <Typography
                variant="body1"
                data-testid="proposal-details-vote-takes-effect-duration"
                gutterBottom
              >
                {votingInformation.voteTakesEffect === 'Threshold'
                  ? 'Threshold'
                  : dayjs(votingInformation.voteTakesEffect).fromNow()}
              </Typography>
              {votingInformation.voteTakesEffect !== 'Threshold' && (
                <Typography
                  variant="body2"
                  color="text.secondary"
                  data-testid="proposal-details-vote-takes-effect-value"
                >
                  {votingInformation.voteTakesEffect}
                </Typography>
              )}
            </Box>
            <Divider sx={{ my: 1 }} />

            <DetailItem
              label="Status"
              value={votingInformation.status}
              labelId="proposal-details-status-label"
              valueId="proposal-details-status-value"
            />
          </Box>

          <Divider sx={{ mt: 1, mb: 8 }} />

          {/* Votes Section */}
          <Typography variant="h6" component="h2" gutterBottom>
            Votes
          </Typography>

          <Box sx={{ borderBottom: 1, borderColor: 'divider', mb: 3 }}>
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
          </Box>

          <Box
            sx={{ display: 'flex', flexDirection: 'column', gap: 2, mb: 4 }}
            data-testid="proposal-details-votes"
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

          <Divider sx={{ my: 4 }} />

          {proposalDetails.isVoteRequest && !isClosed && (
            <ProposalVoteForm
              voteRequestContractId={contractId}
              currentSvPartyId={currentSvPartyId}
              votes={votes}
            />
          )}
        </Paper>
      </Box>
    </Box>
  );
};

interface DetailItemProps {
  label: string;
  value: string | React.ReactNode;
  labelId?: string;
  valueId?: string;
}

const DetailItem = ({ label, value, labelId, valueId }: DetailItemProps) => {
  return (
    <Box sx={{ py: 1 }}>
      <Typography
        variant="subtitle2"
        color="text.secondary"
        id={labelId}
        data-testid={labelId}
        gutterBottom
      >
        {label}
      </Typography>
      <Typography variant="body1" id={valueId} data-testid={valueId}>
        {value}
      </Typography>
    </Box>
  );
};

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
      sx={{ py: 1 }}
      id="proposal-details-offboard-member-section"
      data-testid="proposal-details-offboard-member-section"
    >
      <Typography variant="subtitle2" color="text.secondary" gutterBottom>
        Member
      </Typography>
      <Box sx={{ display: 'flex', alignItems: 'center' }}>
        <PartyId
          partyId={memberPartyId}
          className="proposal-details-member-party-id"
          id="proposal-details-member-party-id"
        />
      </Box>
    </Box>
  );
};

interface FeatureAppSectionProps {
  provider: string;
}

const FeatureAppSection = ({ provider }: FeatureAppSectionProps) => {
  return (
    <Box
      sx={{ py: 1 }}
      id="proposal-details-feature-app-section"
      data-testid="proposal-details-feature-app-section"
    >
      <Box sx={{ display: 'flex', flexDirection: 'column' }}>
        <DetailItem
          label="Provider ID"
          value={provider}
          labelId="proposal-details-feature-app-label"
          valueId="proposal-details-feature-app-value"
        />
      </Box>
    </Box>
  );
};

interface UnfeatureAppSectionProps {
  rightContractId: string;
}

const UnfeatureAppSection = ({ rightContractId }: UnfeatureAppSectionProps) => {
  return (
    <Box
      sx={{ py: 1 }}
      id="proposal-details-unfeature-app-section"
      data-testid="proposal-details-unfeature-app-section"
    >
      <Box sx={{ display: 'flex', flexDirection: 'column' }}>
        <DetailItem
          label="Contract ID"
          value={rightContractId}
          labelId="proposal-details-unfeature-app-label"
          valueId="proposal-details-unfeature-app-value"
        />
      </Box>
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
        sx={{ py: 1 }}
        id="proposal-details-update-sv-reward-weight-section"
        data-testid="proposal-details-update-sv-reward-weight-section"
      >
        <Box sx={{ display: 'flex', flexDirection: 'column' }}>
          <Typography variant="subtitle2" color="text.secondary" gutterBottom>
            Member
          </Typography>
          <Box sx={{ mb: 1 }}>
            <PartyId partyId={svToUpdate} id="proposal-details-member-party-id" />
          </Box>
        </Box>
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
