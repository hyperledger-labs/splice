// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  Box,
  Typography,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Button,
  Alert,
} from '@mui/material';
import ArrowForward from '@mui/icons-material/ArrowForward';
import CheckCircleOutlineIcon from '@mui/icons-material/CheckCircleOutline';
import CancelOutlinedIcon from '@mui/icons-material/CancelOutlined';
import { YourVoteStatus } from '../../routes/governance';
import { VoteRequest } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { ContractId } from '@daml/types';
import { Link as RouterLink } from 'react-router-dom';

interface VotesListingSectionProps {
  sectionTitle: string;
  data: VoteListingData[];
  uniqueId: string;
  showVoteStats?: boolean;
  showAcceptanceThreshold?: boolean;
  showStatus?: boolean;
}

export type VoteListingStatus =
  | 'Accepted'
  | 'In Progress'
  | 'Implemented'
  | 'Rejected'
  | 'Expired'
  | 'Unknown';

export interface VoteListingData {
  contractId: ContractId<VoteRequest>;
  actionName: string;
  votingCloses: string;
  voteTakesEffect: string;
  yourVote: YourVoteStatus;
  status: VoteListingStatus;
  voteStats: Record<YourVoteStatus, number>;
  acceptanceThreshold: bigint;
}

export const VotesListingSection: React.FC<VotesListingSectionProps> = props => {
  const { sectionTitle, data, uniqueId, showVoteStats, showAcceptanceThreshold, showStatus } =
    props;

  return (
    <Box sx={{ mb: 6 }} data-testid={`${uniqueId}-section`}>
      <Typography variant="h5" sx={{ mb: 2 }} data-testid={`${uniqueId}-section-title`}>
        {sectionTitle}
      </Typography>

      {data.length === 0 ? (
        <Alert severity="info" data-testid={`${uniqueId}-section-info`}>
          No {sectionTitle} available
        </Alert>
      ) : (
        <TableContainer component={Paper} data-testid={`${uniqueId}-section-table`}>
          <Table>
            <TableHead>
              <TableRow>
                <TableCell sx={{ width: '25%' }}>Action</TableCell>
                <TableCell sx={{ width: '15%' }}>Voting Closes At</TableCell>
                <TableCell sx={{ width: '15%' }}>Takes Effect</TableCell>

                {showVoteStats && <TableCell sx={{ width: '20%' }}>Votes</TableCell>}
                {showAcceptanceThreshold && (
                  <TableCell sx={{ width: '10%' }}>Acceptance Threshold</TableCell>
                )}
                {showStatus && <TableCell sx={{ width: '10%' }}>Status</TableCell>}

                <TableCell sx={{ width: '15%' }}>Your Vote</TableCell>
                <TableCell sx={{ width: '15%' }} align="right"></TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {data.map((vote, index) => (
                <VoteRow
                  key={index}
                  actionName={vote.actionName}
                  contractId={vote.contractId}
                  uniqueId={uniqueId}
                  votingCloses={vote.votingCloses}
                  voteTakesEffect={vote.voteTakesEffect}
                  yourVote={vote.yourVote}
                  status={vote.status}
                  voteStats={vote.voteStats}
                  acceptanceThreshold={vote.acceptanceThreshold}
                  showVoteStats={showVoteStats}
                  showAcceptanceThreshold={showAcceptanceThreshold}
                  showStatus={showStatus}
                />
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </Box>
  );
};

interface VoteRowProps {
  acceptanceThreshold: bigint;
  actionName: string;
  contractId: ContractId<VoteRequest>;
  status: VoteListingStatus;
  uniqueId: string;
  voteStats: Record<YourVoteStatus, number>;
  voteTakesEffect: string;
  votingCloses: string;
  yourVote: YourVoteStatus;
  showAcceptanceThreshold?: boolean;
  showStatus?: boolean;
  showVoteStats?: boolean;
}

const VoteRow: React.FC<VoteRowProps> = props => {
  const {
    acceptanceThreshold,
    actionName,
    contractId,
    status,
    uniqueId,
    voteStats,
    voteTakesEffect,
    votingCloses,
    yourVote,
    showAcceptanceThreshold,
    showStatus,
    showVoteStats,
  } = props;

  return (
    <TableRow data-testid={`${uniqueId}-row`}>
      <TableCell data-testid={`${uniqueId}-row-action-name`}>{actionName}</TableCell>
      <TableCell data-testid={`${uniqueId}-row-voting-closes`}>{votingCloses}</TableCell>
      <TableCell data-testid={`${uniqueId}-row-vote-takes-effect`}>{voteTakesEffect}</TableCell>

      {showVoteStats && (
        <TableCell data-testid={`${uniqueId}-row-vote-stats`}>
          {voteStats['accepted']} Accepted / {voteStats['rejected']} Rejected
        </TableCell>
      )}
      {showAcceptanceThreshold && (
        <TableCell data-testid={`${uniqueId}-row-acceptance-threshold`}>
          {acceptanceThreshold.toString()}
        </TableCell>
      )}

      {showStatus && <TableCell data-testid={`${uniqueId}-row-status`}>{status}</TableCell>}
      <TableCell data-testid={`${uniqueId}-row-your-vote`}>
        {yourVote === 'accepted' ? (
          <>
            <CheckCircleOutlineIcon
              fontSize="small"
              color="success"
              data-testid={`${uniqueId}-row-your-vote-accepted-icon`}
            />
            <Typography>Accepted</Typography>
          </>
        ) : yourVote === 'rejected' ? (
          <>
            <CancelOutlinedIcon
              fontSize="small"
              color="error"
              data-testid={`${uniqueId}-row-your-vote-rejected-icon`}
            />
            <Typography>Rejected</Typography>
          </>
        ) : (
          <Typography sx={{ opacity: 0.5 }}>No Vote</Typography>
        )}
      </TableCell>

      <TableCell align="right" data-testid={`${uniqueId}-row-view-details`}>
        <Button
          component={RouterLink}
          to={`/governance-beta/vote-requests/${contractId}`}
          size="small"
          endIcon={<ArrowForward fontSize="small" />}
        >
          Details
        </Button>
      </TableCell>
    </TableRow>
  );
};
