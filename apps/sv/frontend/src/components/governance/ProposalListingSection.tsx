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
  Alert,
  Stack,
  SxProps,
} from '@mui/material';
import CheckCircleOutlineIcon from '@mui/icons-material/CheckCircleOutline';
import CancelOutlinedIcon from '@mui/icons-material/CancelOutlined';
import { VoteRequest } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { ContractId } from '@daml/types';
import { PageSectionHeader } from '../../components/beta';
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

interface YourVoteProps {
  vote: YourVoteStatus;
  'data-testid': string;
}

const YourVote: React.FC<YourVoteProps> = ({ vote, 'data-testid': testId }) => {
  if (vote === 'accepted') {
    return (
      <Stack direction="row" gap="6px" alignItems="center">
        <CheckCircleOutlineIcon
          fontSize="small"
          color="success"
          data-testid={`${testId}-accepted-icon`}
        />
        <TableBodyTypography>Accepted</TableBodyTypography>
      </Stack>
    );
  }

  if (vote === 'rejected') {
    return (
      <Stack direction="row" gap="6px" alignItems="center">
        <CancelOutlinedIcon
          fontSize="small"
          color="error"
          data-testid={`${testId}-rejected-icon`}
        />
        <TableBodyTypography>Rejected</TableBodyTypography>
      </Stack>
    );
  }

  return <TableBodyTypography sx={{ opacity: 0.5 }}>No Vote</TableBodyTypography>;
};

const getColumnsCount = (alwaysShown: number, ...sometimesShown: (boolean | undefined)[]) =>
  alwaysShown +
  sometimesShown.reduce((columnsCount, isShown) => columnsCount + (isShown ? 1 : 0), 0);

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
        <Alert severity="info" data-testid={`${uniqueId}-section-info`}>
          No {sectionTitle} available
        </Alert>
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
    // contractId,
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
    <TableRow
      sx={{
        borderRadius: '4px',
        border: '1px solid #4F4F4F',
        display: 'grid',
        gridTemplateColumns: `repeat(${columnsCount}, 1fr)`,
        alignItems: 'center',
        paddingBlock: '10px',
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
        <TableCell data-testid={`${uniqueId}-row-vote-stats`}>
          <TableBodyTypography>
            {voteStats['accepted']} Accepted / {voteStats['rejected']} Rejected
          </TableBodyTypography>
        </TableCell>
      )}
      {showAcceptanceThreshold && (
        <TableCell data-testid={`${uniqueId}-row-acceptance-threshold`}>
          <TableBodyTypography>{acceptanceThreshold.toString()}</TableBodyTypography>
        </TableCell>
      )}

      <TableCell data-testid={`${uniqueId}-row-your-vote`}>
        <YourVote vote={yourVote} data-testid={`${uniqueId}-row-your-vote`} />
      </TableCell>
    </TableRow>
  );
};

interface TableBodyTypographyProps {
  sx?: SxProps;
}

const TableBodyTypography: React.FC<React.PropsWithChildren<TableBodyTypographyProps>> = ({
  children,
  sx,
}) => (
  <Typography fontSize={14} lineHeight={2} color="text.light" sx={sx}>
    {children}
  </Typography>
);
