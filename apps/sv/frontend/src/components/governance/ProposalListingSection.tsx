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
import { VoteRequest } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { ContractId } from '@daml/types';
import { Link as RouterLink } from 'react-router-dom';
import { ProposalListingData, ProposalListingStatus, YourVoteStatus } from '../../utils/types';

interface ProposalListingSectionProps {
  sectionTitle: string;
  data: ProposalListingData[];
  uniqueId: string;
  showThresholdDeadline?: boolean;
  showVoteStats?: boolean;
  showAcceptanceThreshold?: boolean;
  showStatus?: boolean;
}

export const ProposalListingSection: React.FC<ProposalListingSectionProps> = props => {
  const {
    sectionTitle,
    data,
    uniqueId,
    showThresholdDeadline,
    showVoteStats,
    showAcceptanceThreshold,
    showStatus,
  } = props;

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
                <TableCell sx={{ width: '15%' }}>Action</TableCell>
                {showThresholdDeadline && (
                  <TableCell sx={{ width: '15%' }}>Threshold Deadline</TableCell>
                )}
                <TableCell sx={{ width: '15%' }}>Effective At</TableCell>
                {showStatus && <TableCell sx={{ width: '10%' }}>Status</TableCell>}

                {showVoteStats && <TableCell sx={{ width: '20%' }}>Votes</TableCell>}
                {showAcceptanceThreshold && (
                  <TableCell sx={{ width: '10%' }}>Acceptance Threshold</TableCell>
                )}

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
                  votingThresholdDeadline={vote.votingThresholdDeadline}
                  voteTakesEffect={vote.voteTakesEffect}
                  yourVote={vote.yourVote}
                  status={vote.status}
                  voteStats={vote.voteStats}
                  acceptanceThreshold={vote.acceptanceThreshold}
                  showVoteStats={showVoteStats}
                  showAcceptanceThreshold={showAcceptanceThreshold}
                  showThresholdDeadline={showThresholdDeadline}
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
  status: ProposalListingStatus;
  uniqueId: string;
  voteStats: Record<YourVoteStatus, number>;
  voteTakesEffect: string;
  votingThresholdDeadline: string;
  yourVote: YourVoteStatus;
  showThresholdDeadline?: boolean;
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
    votingThresholdDeadline,
    yourVote,
    showThresholdDeadline,
    showAcceptanceThreshold,
    showStatus,
    showVoteStats,
  } = props;

  return (
    <TableRow data-testid={`${uniqueId}-row`}>
      <TableCell data-testid={`${uniqueId}-row-action-name`}>{actionName}</TableCell>
      {showThresholdDeadline && (
        <TableCell data-testid={`${uniqueId}-row-voting-threshold-deadline`}>
          {votingThresholdDeadline}
        </TableCell>
      )}
      <TableCell data-testid={`${uniqueId}-row-vote-takes-effect`}>{voteTakesEffect}</TableCell>

      {showStatus && <TableCell data-testid={`${uniqueId}-row-status`}>{status}</TableCell>}
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
          to={`/governance-beta/proposals/${contractId}`}
          size="small"
          endIcon={<ArrowForward fontSize="small" />}
        >
          Details
        </Button>
      </TableCell>
    </TableRow>
  );
};
