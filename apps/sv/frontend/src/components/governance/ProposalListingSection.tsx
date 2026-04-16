// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import {
  Box,
  CircularProgress,
  Typography,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Stack,
  TypographyProps,
} from '@mui/material';
import { VoteRequest } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { ContractId } from '@daml/types';
import { useNavigate } from 'react-router';
import { CopyableIdentifier, PageSectionHeader, VoteStats } from '../../components/beta';
import { ProposalListingData, ProposalListingStatus, YourVoteStatus } from '../../utils/types';
import { InfoOutlined } from '@mui/icons-material';
import dayjs from 'dayjs';
import React, { useEffect, useMemo, useRef } from 'react';
import { useInView } from 'react-intersection-observer';

export type ProposalSortOrder = 'effectiveAtAsc' | 'effectiveAtDesc';

interface ProposalListingSectionProps {
  sectionTitle: string;
  data: ProposalListingData[];
  noDataMessage: string;
  uniqueId: string;
  showThresholdDeadline?: boolean;
  showVoteStats?: boolean;
  showStatus?: boolean;
  sortOrder?: ProposalSortOrder;
  fetchNextPage?: () => void;
  hasNextPage?: boolean;
  isFetchingNextPage?: boolean;
  pageCount?: number;
}

const getTotalVotes = (item: ProposalListingData): number =>
  item.voteStats['accepted'] + item.voteStats['rejected'];

const getEffectiveDate = (item: ProposalListingData): dayjs.Dayjs =>
  item.voteTakesEffect === 'Threshold' ? dayjs(0) : dayjs(item.voteTakesEffect);

// Using stable sort: chain sorts from least to most significant criterion
const sortProposals = (
  data: ProposalListingData[],
  sortOrder?: ProposalSortOrder
): ProposalListingData[] => {
  if (!sortOrder) return data;

  if (sortOrder === 'effectiveAtDesc') {
    return data.toSorted((a, b) => dayjs(b.voteTakesEffect).diff(dayjs(a.voteTakesEffect)));
  }

  // For effectiveAtAsc (Inflight Votes):
  // Threshold items first (by votes desc, then deadline asc), then dated items (by effective date asc)
  return data
    .toSorted((a, b) => dayjs(a.votingThresholdDeadline).diff(dayjs(b.votingThresholdDeadline)))
    .toSorted((a, b) => getTotalVotes(b) - getTotalVotes(a))
    .toSorted((a, b) => getEffectiveDate(a).diff(getEffectiveDate(b)));
};

const getColumnsCount = (...shown: (boolean | undefined)[]) => 4 + shown.filter(Boolean).length;

const getGridTemplate = (columnsCount: number) =>
  `minmax(0, 1fr) minmax(0, 0.7fr) ${'1fr '.repeat(columnsCount - 2).trim()}`;

export const ProposalListingSection: React.FC<ProposalListingSectionProps> = props => {
  const {
    sectionTitle,
    data,
    noDataMessage,
    uniqueId,
    showThresholdDeadline,
    showVoteStats,
    showStatus,
    sortOrder,
    fetchNextPage,
    hasNextPage,
    isFetchingNextPage,
    pageCount,
  } = props;

  const sectionRef = useRef<HTMLDivElement>(null);
  const { ref, inView } = useInView();

  useEffect(() => {
    if (inView && hasNextPage && !isFetchingNextPage && fetchNextPage) {
      fetchNextPage();
    }
  }, [inView, hasNextPage, isFetchingNextPage, fetchNextPage]);

  const sortedData = useMemo(() => sortProposals(data, sortOrder), [data, sortOrder]);

  const columnsCount = getColumnsCount(showThresholdDeadline, showStatus, showVoteStats);
  const gridTemplate = getGridTemplate(columnsCount);

  const supportsInfiniteScroll = fetchNextPage !== undefined;

  return (
    <Box ref={sectionRef} sx={{ mb: 6 }} data-testid={`${uniqueId}-section`}>
      <PageSectionHeader title={sectionTitle} data-testid={`${uniqueId}-section`} />

      {sortedData.length === 0 && !hasNextPage ? (
        <InfoBox info={noDataMessage} data-testid={`${uniqueId}-section-info`} />
      ) : (
        <>
          <TableContainer data-testid={`${uniqueId}-section-table`}>
            <Table>
              <TableHead>
                <TableRow sx={{ display: 'grid', gridTemplateColumns: gridTemplate }}>
                  <TableCell>ACTION</TableCell>
                  <TableCell>VOTE PROPOSAL CONTRACT ID</TableCell>
                  {showThresholdDeadline && <TableCell>THRESHOLD DEADLINE</TableCell>}
                  <TableCell>EFFECTIVE AT</TableCell>
                  {showStatus && <TableCell>STATUS</TableCell>}
                  {showVoteStats && <TableCell>VOTES</TableCell>}
                  <TableCell>YOUR VOTE</TableCell>
                </TableRow>
              </TableHead>
              <TableBody sx={{ display: 'contents' }}>
                {sortedData.map((vote, index) => (
                  <VoteRow
                    key={index}
                    actionName={vote.actionName}
                    description={vote.description}
                    contractId={vote.contractId}
                    uniqueId={uniqueId}
                    votingThresholdDeadline={vote.votingThresholdDeadline}
                    voteTakesEffect={vote.voteTakesEffect}
                    yourVote={vote.yourVote}
                    status={vote.status}
                    voteStats={vote.voteStats}
                    gridTemplate={gridTemplate}
                    showVoteStats={showVoteStats}
                    showThresholdDeadline={showThresholdDeadline}
                    showStatus={showStatus}
                  />
                ))}
              </TableBody>
            </Table>
          </TableContainer>
          {supportsInfiniteScroll && (
            <Box
              ref={ref}
              sx={{
                display: 'flex',
                justifyContent: 'center',
                alignItems: 'center',
                pt: 2,
                pb: 0,
                minHeight: 32,
              }}
            >
              {isFetchingNextPage || (inView && hasNextPage) ? (
                <CircularProgress size={24} />
              ) : hasNextPage ? (
                <Typography fontSize={14} color="text.secondary">
                  More results available
                </Typography>
              ) : (pageCount ?? 0) > 1 ? (
                <Stack alignItems="center" gap={0.5}>
                  <Typography fontSize={14} color="text.secondary">
                    You've reached the end
                  </Typography>
                  <Typography
                    fontSize={13}
                    color="primary.main"
                    sx={{ cursor: 'pointer', '&:hover': { textDecoration: 'underline' } }}
                    onClick={() => sectionRef.current?.scrollIntoView({ behavior: 'smooth' })}
                  >
                    Back to top
                  </Typography>
                </Stack>
              ) : null}
            </Box>
          )}
        </>
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
  actionName: string;
  description?: string;
  contractId: ContractId<VoteRequest>;
  status: ProposalListingStatus;
  uniqueId: string;
  voteStats: Record<YourVoteStatus, number>;
  voteTakesEffect: string;
  votingThresholdDeadline: string;
  yourVote: YourVoteStatus;
  gridTemplate: string;
  showThresholdDeadline?: boolean;
  showStatus?: boolean;
  showVoteStats?: boolean;
}

const VoteRow: React.FC<VoteRowProps> = React.memo(props => {
  const {
    actionName,
    description,
    contractId,
    status,
    uniqueId,
    voteStats,
    voteTakesEffect,
    votingThresholdDeadline,
    yourVote,
    gridTemplate,
    showThresholdDeadline,
    showStatus,
    showVoteStats,
  } = props;

  const navigate = useNavigate();

  return (
    <TableRow
      onClick={() => navigate(`/governance/proposals/${contractId}`)}
      sx={{
        display: 'grid',
        gridTemplateColumns: gridTemplate,
        alignItems: 'center',
        borderRadius: '4px',
        border: '1px solid #4F4F4F',
        paddingBlock: '10px',
        cursor: 'pointer',
        '&:hover': { backgroundColor: '#363636' },
      }}
      data-testid={`${uniqueId}-row`}
    >
      <TableCell data-testid={`${uniqueId}-row-action-name`} sx={{ overflow: 'hidden' }}>
        <Typography
          {...tableBodyTypography}
          sx={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
        >
          {actionName}
        </Typography>
        {description && (
          <Typography
            data-testid={`${uniqueId}-row-description`}
            sx={{
              fontSize: 12,
              color: 'text.secondary',
              display: '-webkit-box',
              WebkitLineClamp: 2,
              WebkitBoxOrient: 'vertical',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              lineHeight: 1.4,
            }}
          >
            {description}
          </Typography>
        )}
      </TableCell>
      <TableCell data-testid={`${uniqueId}-row-contract-id`}>
        <CopyableIdentifier
          value={contractId}
          size="small"
          data-testid={`${uniqueId}-row-contract-id-value`}
        />
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
          <AllVotes
            acceptedVotes={voteStats['accepted']}
            rejectedVotes={voteStats['rejected']}
            data-testid={`${uniqueId}-row-all-votes-stats`}
          />
        </TableCell>
      )}
      <TableCell data-testid={`${uniqueId}-row-your-vote`}>
        <VoteStats
          vote={yourVote}
          typography={tableBodyTypography}
          data-testid={`${uniqueId}-row-your-vote-stats`}
        />
      </TableCell>
    </TableRow>
  );
});

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
      <VoteStats
        vote="accepted"
        count={acceptedVotes}
        typography={tableBodyTypography}
        data-testid={`${testId}-accepted`}
      />
      <VoteStats
        vote="rejected"
        count={rejectedVotes}
        typography={tableBodyTypography}
        data-testid={`${testId}-rejected`}
      />
    </Stack>
  );
};

const tableBodyTypography: TypographyProps = {
  fontSize: 14,
  lineHeight: 2,
  color: 'text.light',
};

const TableBodyTypography: React.FC<React.PropsWithChildren> = ({ children }) => (
  <Typography {...tableBodyTypography}>{children}</Typography>
);
