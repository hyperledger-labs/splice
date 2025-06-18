// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { VoteRequest } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { ContractId } from '@daml/types';
import { ArrowBack } from '@mui/icons-material';
import {
  Box,
  Button,
  Chip,
  Divider,
  Link,
  Paper,
  Tab,
  Tabs,
  TextField,
  Typography,
} from '@mui/material';
import React, { useState } from 'react';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import { PartyId, theme } from '@lfdecentralizedtrust/splice-common-frontend';
import { sanitizeUrl } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { Link as RouterLink } from 'react-router-dom';
import {
  ConfigChange,
  ProposalDetails,
  ProposalVote,
  ProposalVotingInformation,
  VoteStatus,
} from '../../utils/types';

dayjs.extend(relativeTime);

export interface VoteRequestDetailsContentProps {
  contractId: ContractId<VoteRequest>;
  proposalDetails: ProposalDetails;
  votingInformation: ProposalVotingInformation;
  votes: ProposalVote[];
}

type VoteTab = Extract<VoteStatus, 'accepted' | 'rejected' | 'no-vote'> | 'all';

const now = () => dayjs();

export const VoteRequestDetailsContent: React.FC<VoteRequestDetailsContentProps> = props => {
  const { proposalDetails, votingInformation, votes } = props;

  const hasExpired = dayjs(votingInformation.votingCloses).isBefore(now());
  const isEffective =
    votingInformation.voteTakesEffect && dayjs(votingInformation.voteTakesEffect).isBefore(now());
  const isClosed = hasExpired || isEffective || votingInformation.status === 'Rejected';

  const [voteTabValue, setVoteTabValue] = useState<VoteTab>('all');
  const [reason, setReason] = useState('');

  const handleVoteTabChange = (_event: React.SyntheticEvent, newValue: VoteTab) => {
    setVoteTabValue(newValue);
  };

  //TODO: Use reduce to do this on one pass or keep this for readability?
  const acceptedVotes = votes.filter(vote => vote.vote === 'accepted');
  const rejectedVotes = votes.filter(vote => vote.vote === 'rejected');
  const awaitingVotes = votes.filter(vote => vote.vote === 'no-vote');

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
          <Typography variant="h4" component="h1" sx={{ flexGrow: 1 }}>
            Proposal Details
          </Typography>
          <Button
            component={RouterLink}
            to="/governance-beta/vote-requests"
            size="small"
            startIcon={<ArrowBack fontSize="small" />}
            sx={{ color: 'text.secondary' }}
          >
            Back to all votes
          </Button>
        </Box>

        <Paper sx={{ bgcolor: 'background.paper', p: 6 }}>
          {/* Proposal Details Section */}
          <Typography variant="h6" component="h2" gutterBottom sx={{ mb: 3 }}>
            Proposal Details
          </Typography>

          <Box sx={{ display: 'flex', flexDirection: 'column' }}>
            <DetailItem label="Action" value={proposalDetails.actionName} />
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

            <DetailItem label="Summary" value={proposalDetails.summary} />
            <Divider sx={{ my: 1 }} />

            <DetailItem
              label="URL"
              value={
                <Link href={sanitizeUrl(proposalDetails.url)} target="_blank" color="primary">
                  {sanitizeUrl(proposalDetails.url)}
                </Link>
              }
            />
          </Box>

          <Divider sx={{ mt: 1, mb: 8 }} />

          {/* Voting Information Section */}
          <Typography variant="h6" component="h2" gutterBottom sx={{ mb: 3 }}>
            Voting Information
          </Typography>

          <Box sx={{ display: 'flex', flexDirection: 'column' }}>
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
                Voting Expires At
              </Typography>
              <Typography variant="body1" gutterBottom>
                {dayjs(votingInformation.votingCloses).fromNow()}
              </Typography>
              <Typography variant="body2" color="text.secondary">
                {votingInformation.votingCloses}
              </Typography>
            </Box>
            <Divider sx={{ my: 1 }} />

            <Box sx={{ py: 1 }}>
              <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                Voting Takes Effect On
              </Typography>
              <Typography variant="body1" gutterBottom>
                {dayjs(votingInformation.voteTakesEffect).fromNow()}
              </Typography>
              <Typography variant="body2" color="text.secondary">
                {votingInformation.voteTakesEffect}
              </Typography>
            </Box>
            <Divider sx={{ my: 1 }} />

            <DetailItem label="Status" value={votingInformation.status} />
          </Box>

          <Divider sx={{ mt: 1, mb: 8 }} />

          {/* Votes Section */}
          <Typography variant="h6" component="h2" gutterBottom>
            Votes
          </Typography>

          <Box sx={{ borderBottom: 1, borderColor: 'divider', mb: 3 }}>
            <Tabs value={voteTabValue} onChange={handleVoteTabChange} aria-label="vote tabs">
              <Tab label={`All (${votes.length})`} value="all" />
              <Tab label={`Accepted (${acceptedVotes.length})`} value="accepted" />
              <Tab label={`Rejected (${rejectedVotes.length})`} value="rejected" />
              <Tab
                label={
                  (isClosed ? 'Did not Vote' : 'Awaiting Response') +
                  ' (' +
                  awaitingVotes.length +
                  ')'
                }
                value="no-vote"
              />
            </Tabs>
          </Box>

          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, mb: 4 }}>
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

          {proposalDetails.isVoteRequest && (
            <>
              {/* Your Vote Section */}
              <Typography variant="h6" component="h2" gutterBottom>
                Your Vote
              </Typography>

              <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                <TextField
                  label="Your Reason"
                  multiline
                  rows={4}
                  value={reason}
                  onChange={e => setReason(e.target.value)}
                  // variant="outlined"
                  // fullWidth
                  // sx={{
                  //   '& .MuiOutlinedInput-root': {
                  //     bgcolor: 'rgba(255, 255, 255, 0.05)',
                  //   },
                  // }}
                />
                <Box sx={{ display: 'flex', gap: 2, justifyContent: 'center', mt: 2 }}>
                  <Button variant="contained" color="success" sx={{ minWidth: 100 }}>
                    Accept
                  </Button>
                  <Button variant="contained" color="error" sx={{ minWidth: 100 }}>
                    Reject
                  </Button>
                </Box>
              </Box>
            </>
          )}
        </Paper>
      </Box>
    </Box>
  );
};

interface DetailItemProps {
  label: string;
  value: string | React.ReactNode;
}

const DetailItem = ({ label, value }: DetailItemProps) => {
  return (
    <Box sx={{ py: 1 }}>
      <Typography variant="subtitle2" color="text.secondary" gutterBottom>
        {label}
      </Typography>
      <Typography variant="body1">{value}</Typography>
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
      default:
        return 'Unknown';
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
        <Typography variant="body2" color={getStatusColor()}>
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
    <Box sx={{ py: 1 }}>
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
    <Box sx={{ py: 1 }}>
      {/* <Typography variant="subtitle2" color="text.secondary" gutterBottom sx={{ mb: 2 }}> */}
      {/*   Feature Application */}
      {/* </Typography> */}

      <Box sx={{ display: 'flex', flexDirection: 'column' }}>
        <DetailItem label="Provider ID" value={provider} />
      </Box>
    </Box>
  );
};

interface UnfeatureAppSectionProps {
  rightContractId: string;
}

const UnfeatureAppSection = ({ rightContractId }: UnfeatureAppSectionProps) => {
  return (
    <Box sx={{ py: 1 }}>
      <Box sx={{ display: 'flex', flexDirection: 'column' }}>
        <DetailItem label="Contract ID" value={rightContractId} />
      </Box>
    </Box>
  );
};

interface UpdateSvRewardWeightSectionProps {
  svToUpdate: string;
  weightChange: string;
}

const UpdateSvRewardWeightSection = ({
  svToUpdate,
  weightChange,
}: UpdateSvRewardWeightSectionProps) => {
  return (
    <Box sx={{ py: 1 }}>
      <Box sx={{ display: 'flex', flexDirection: 'column' }}>
        <Typography variant="subtitle2" color="text.secondary" gutterBottom>
          Member
        </Typography>
        <Typography sx={{ mb: 1 }} variant="body1">
          <PartyId partyId={svToUpdate} id="proposal-details-member-party-id" />
        </Typography>

        <DetailItem label="Weight" value={weightChange} />
      </Box>
    </Box>
  );
};

interface ConfigRulesChangesProps {
  changes: ConfigChange[];
}

const ConfigValuesChanges = ({ changes }: ConfigRulesChangesProps) => {
  return (
    <Box sx={{ py: 1 }}>
      <Typography variant="subtitle2" color="text.secondary" gutterBottom sx={{ mb: 2 }}>
        Proposed Changes
      </Typography>

      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, mb: 3 }}>
        {changes.length === 0 && (
          <Box sx={{ py: 1 }}>
            <Typography variant="body2" color="text.secondary">
              No changes found.
            </Typography>
          </Box>
        )}

        {changes.map((change, index) => (
          <Box key={index} sx={{ display: 'flex', alignItems: 'center', gap: 2 }}>
            <Typography variant="body1" sx={{ minWidth: 200 }}>
              {change.fieldName}
            </Typography>

            <Box
              sx={{
                px: 1.5,
                py: 0.5,
                bgcolor: 'rgba(255, 255, 255, 0.1)',
                borderRadius: 1,
                minWidth: 80,
                textAlign: 'center',
              }}
            >
              {change.isId ? (
                <PartyId partyId={`${change.currentValue}`} />
              ) : (
                <Typography variant="body2" fontFamily="monospace">
                  {change.currentValue}
                </Typography>
              )}
            </Box>

            <Typography variant="body1" sx={{ mx: 1 }}>
              →
            </Typography>

            <Box
              sx={{
                px: 1.5,
                py: 0.5,
                bgcolor: 'rgba(255, 255, 255, 0.1)',
                borderRadius: 1,
                minWidth: 80,
                textAlign: 'center',
              }}
            >
              {change.isId ? (
                <PartyId partyId={`${change.newValue}`} />
              ) : (
                <Typography variant="body2" fontFamily="monospace">
                  {change.newValue}
                </Typography>
              )}
            </Box>
          </Box>
        ))}
      </Box>

      {/* TODO: Add the json View here but only if we have changes */}
    </Box>
  );
};
