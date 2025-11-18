// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import {
  Box,
  Typography,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Stack,
} from '@mui/material';
import CheckCircleOutlineIcon from '@mui/icons-material/CheckCircleOutline';
import CancelOutlinedIcon from '@mui/icons-material/CancelOutlined';
import { VoteRequest } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { ContractId } from '@daml/types';
import { Link as RouterLink } from 'react-router-dom';
import { PageSectionHeader } from '../../components/beta';
import { ProposalListingData, ProposalListingStatus, YourVoteStatus } from '../../utils/types';
import { InfoOutlined } from '@mui/icons-material';

interface ProposalListingSectionProps {
  sectionTitle: string;
  data: ProposalListingData[];
  noDataMessage: string;
  uniqueId: string;
  showThresholdDeadline?: boolean;
  showVoteStats?: boolean;
  showAcceptanceThreshold?: boolean;
  showStatus?: boolean;
}

const getColumnsCount = (alwaysShown: number, ...sometimesShown: (boolean | undefined)[]) =>
  alwaysShown +
  sometimesShown.reduce((columnsCount, isShown) => columnsCount + (isShown ? 1 : 0), 0);

export const ProposalListingSection: React.FC<ProposalListingSectionProps> = props => {
  const {
    sectionTitle,
    data,
    noDataMessage,
    uniqueId,
    showThresholdDeadline,
    showVoteStats,
    showAcceptanceThreshold,
    showStatus,
  } = props;

  const columnsCount = getColumnsCount(
    3,
    showThresholdDeadline,
    showStatus,
    showVoteStats,
    showAcceptanceThreshold
  );

  return (
    <Box sx={{ mb: 6 }} data-testid={`${uniqueId}-section`}>
      <PageSectionHeader title={sectionTitle} data-testid={`${uniqueId}-section`} />

      {data.length === 0 ? (
        <InfoBox info={noDataMessage} data-testid={`${uniqueId}-section-info`} />
      ) : (
        <TableContainer data-testid={`${uniqueId}-section-table`}>
          <Table>
            <TableHead>
              <TableRow
                sx={{ display: 'grid', gridTemplateColumns: `repeat(${columnsCount}, 1fr)` }}
              >
                <TableCell>ACTION</TableCell>
                {showThresholdDeadline && <TableCell>THRESHOLD DEADLINE</TableCell>}
                <TableCell>EFFECTIVE AT</TableCell>
                {showStatus && <TableCell>STATUS</TableCell>}

                {showVoteStats && <TableCell>VOTES</TableCell>}
                {showAcceptanceThreshold && <TableCell>ACCEPTANCE THRESHOLD</TableCell>}
                <TableCell>YOUR VOTE</TableCell>
              </TableRow>
            </TableHead>
            <TableBody sx={{ display: 'contents' }}>
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

interface InfoBoxProps {
  info: string;
  'data-testid': string;
}

const InfoBox: React.FC<InfoBoxProps> = ({ info, 'data-testid': testId }) => {
  return (
    <Stack
      gap={1}
      direction="row"
      alignItems="center"
      sx={{
        width: 'max-content',
        borderColor: 'secondary.main',
        borderWidth: '2px',
        borderStyle: 'solid',
        borderRadius: '4px',
        p: 2,
      }}
      data-testid={testId}
    >
      <InfoOutlined color="secondary" fontSize="small" />
      <Typography fontWeight="bold" fontSize={14}>
        {info}
      </Typography>
    </Stack>
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

  const columnsCount = getColumnsCount(
    3,
    showThresholdDeadline,
    showStatus,
    showVoteStats,
    showAcceptanceThreshold
  );

  return (
    <RouterLink to={`/governance-beta/proposals/${contractId}`} style={{ textDecoration: 'none' }}>
      <TableRow
        sx={{
          display: 'grid',
          gridTemplateColumns: `repeat(${columnsCount}, 1fr)`,
          alignItems: 'center',
          borderRadius: '4px',
          border: '1px solid #4F4F4F',
          paddingBlock: '10px',
          '&:hover': { backgroundColor: '#363636' },
        }}
        data-testid={`${uniqueId}-row`}
      >
        <TableCell data-testid={`${uniqueId}-row-action-name`}>
          <TableBodyTypography>{actionName}</TableBodyTypography>
        </TableCell>
        {showThresholdDeadline && (
          <TableCell data-testid={`${uniqueId}-row-voting-threshold-deadline`}>
            <TableBodyTypography>{votingThresholdDeadline}</TableBodyTypography>
          </TableCell>
        )}
        <TableCell data-testid={`${uniqueId}-row-vote-takes-effect`}>
          <TableBodyTypography>{voteTakesEffect}</TableBodyTypography>
        </TableCell>

        {showStatus && (
          <TableCell data-testid={`${uniqueId}-row-status`}>
            <TableBodyTypography>{status}</TableBodyTypography>
          </TableCell>
        )}
        {showVoteStats && (
          <TableCell data-testid={`${uniqueId}-row-all-votes`}>
            <TableBodyTypography>
              <AllVotes
                acceptedVotes={voteStats['accepted']}
                rejectedVotes={voteStats['rejected']}
                data-testid={`${uniqueId}-row-all-votes-stats`}
              />
            </TableBodyTypography>
          </TableCell>
        )}
        {showAcceptanceThreshold && (
          <TableCell data-testid={`${uniqueId}-row-acceptance-threshold`}>
            <TableBodyTypography>{acceptanceThreshold.toString()}</TableBodyTypography>
          </TableCell>
        )}

        <TableCell data-testid={`${uniqueId}-row-your-vote`}>
          <VoteStats vote={yourVote} data-testid={`${uniqueId}-row-your-vote-stats`} />
        </TableCell>
      </TableRow>
    </RouterLink>
  );
};

interface AllVotesProps {
  acceptedVotes: number;
  rejectedVotes: number;
  'data-testid': string;
}

const AllVotes: React.FC<AllVotesProps> = ({
  acceptedVotes,
  rejectedVotes,
  'data-testid': testId,
}) => {
  return (
    <Stack>
      <VoteStats vote="accepted" count={acceptedVotes} data-testid={`${testId}-accepted`} />
      <VoteStats vote="rejected" count={rejectedVotes} data-testid={`${testId}-rejected`} />
    </Stack>
  );
};

interface VoteStatsProps {
  vote: YourVoteStatus;
  count?: number;
  'data-testid': string;
}

const VoteStats: React.FC<VoteStatsProps> = ({ vote, count, 'data-testid': testId }) => {
  if (vote === 'accepted') {
    return (
      <Stack direction="row" gap="4px" alignItems="center" data-testid={testId}>
        <CheckCircleOutlineIcon
          fontSize="small"
          color="success"
          data-testid={`${testId}-accepted-icon`}
        />
        <TableBodyTypography>{count} Accepted</TableBodyTypography>
      </Stack>
    );
  }

  if (vote === 'rejected') {
    return (
      <Stack direction="row" gap="4px" alignItems="center" data-testid={testId}>
        <CancelOutlinedIcon
          fontSize="small"
          color="error"
          data-testid={`${testId}-rejected-icon`}
        />
        <TableBodyTypography>{count} Rejected</TableBodyTypography>
      </Stack>
    );
  }

  return <TableBodyTypography data-testid={testId}>No Vote</TableBodyTypography>;
};

const TableBodyTypography: React.FC<React.PropsWithChildren> = ({ children }) => (
  <Typography fontFamily="lato" fontSize={14} lineHeight={2} color="text.light">
    {children}
  </Typography>
);
