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
import { CopyableIdentifier, PageSectionHeader, VoteStats } from '../../components/ui';
import {
  CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE,
  THRESHOLD_DEADLINE_LABEL,
  VOTE_PROPOSAL_CONTRACT_ID_LABEL,
} from '../../utils/constants';
import { ProposalListingData, ProposalListingStatus, YourVoteStatus } from '../../utils/types';
import { InfoOutlined } from '@mui/icons-material';
import dayjs from 'dayjs';
import React, { useEffect, useMemo, useRef } from 'react';
import { useInView } from 'react-intersection-observer';
import { formatDatetimeWithOffset } from '../../utils/dateFormat';

export type ProposalSortOrder = 'effectiveAtAsc' | 'effectiveAtDesc';

interface ProposalListingSectionProps {
  sectionTitle: string;
  data: ProposalListingData[];
  noDataMessage: string;
  uniqueId: string;
  badgeCount?: number;
  isLoading?: boolean;
  loadingMessage?: string;
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

const formatEffectiveDate = (item: string | dayjs.ConfigType): string =>
  item === 'Threshold' ? item : formatDatetimeWithOffset(item as dayjs.ConfigType);

// Using stable sort: chain sorts from least to most significant criterion
const sortProposals = (
  data: ProposalListingData[],
  sortOrder?: ProposalSortOrder
): ProposalListingData[] => {
  if (!sortOrder) return data;

  if (sortOrder === 'effectiveAtDesc') {
    return data.toSorted((a, b) => dayjs(b.voteTakesEffect).diff(dayjs(a.voteTakesEffect)));
  }

  // For effectiveAtAsc (In-flight Proposals):
  // Threshold items first (by votes desc, then deadline asc), then dated items (by effective date asc)
  return data
    .toSorted((a, b) => dayjs(a.votingThresholdDeadline).diff(dayjs(b.votingThresholdDeadline)))
    .toSorted((a, b) => getTotalVotes(b) - getTotalVotes(a))
    .toSorted((a, b) => getEffectiveDate(a).diff(getEffectiveDate(b)));
};

const getColumnsCount = (...shown: (boolean | undefined)[]) => 5 + shown.filter(Boolean).length;

const getGridTemplate = (columnsCount: number) =>
  `minmax(0, 1fr) minmax(0, 0.7fr) ${'1fr '.repeat(columnsCount - 2).trim()}`;

const governanceTableHeadCellSx = {
  py: '10px',
  px: '16px',
  fontSize: 12,
  fontWeight: 600,
  textTransform: 'uppercase' as const,
  color: 'colors.neutral.80',
  borderBottom: 'none',
  display: 'flex',
  alignItems: 'center',
};

const governanceTableBodyCellSx = {
  py: '15px',
  px: '16px',
  borderBottom: 'none',
  display: 'flex',
  alignItems: 'center',
  alignSelf: 'stretch',
  minWidth: 0,
};

interface SubmittedByCellProps {
  requester: string;
  uniqueId: string;
}

const identifierCellSx = {
  ...governanceTableBodyCellSx,
  overflow: 'visible',
};

const SubmittedByCell: React.FC<SubmittedByCellProps> = ({ requester, uniqueId }) => (
  <TableCell sx={identifierCellSx} data-testid={`${uniqueId}-row-submitted-by`}>
    <CopyableIdentifier
      value={requester}
      size="small"
      data-testid={`${uniqueId}-row-submitted-by-identifier`}
    />
  </TableCell>
);

interface TableHeaderProps {
  showThresholdDeadline?: boolean;
  showStatus?: boolean;
  showVoteStats?: boolean;
}

const TableHeader: React.FC<TableHeaderProps> = ({
  showThresholdDeadline,
  showStatus,
  showVoteStats,
}) => (
  <>
    <TableCell sx={governanceTableHeadCellSx}>{CREATE_PROPOSAL_LABEL_PROPOSAL_TYPE}</TableCell>
    <TableCell sx={governanceTableHeadCellSx}>{VOTE_PROPOSAL_CONTRACT_ID_LABEL}</TableCell>
    {showThresholdDeadline ? (
      <>
        <TableCell sx={governanceTableHeadCellSx}>{THRESHOLD_DEADLINE_LABEL}</TableCell>
        <TableCell sx={governanceTableHeadCellSx}>SUBMITTED BY</TableCell>
        <TableCell sx={governanceTableHeadCellSx}>EFFECTIVE AT</TableCell>
      </>
    ) : (
      <>
        <TableCell sx={governanceTableHeadCellSx}>EFFECTIVE AT</TableCell>
        <TableCell sx={governanceTableHeadCellSx}>SUBMITTED BY</TableCell>
        {showStatus && <TableCell sx={governanceTableHeadCellSx}>STATUS</TableCell>}
      </>
    )}
    {showVoteStats && <TableCell sx={governanceTableHeadCellSx}>VOTES</TableCell>}
    <TableCell sx={governanceTableHeadCellSx}>YOUR VOTE</TableCell>
  </>
);

export const ProposalListingSection: React.FC<ProposalListingSectionProps> = props => {
  const {
    sectionTitle,
    data,
    noDataMessage,
    uniqueId,
    badgeCount,
    isLoading,
    loadingMessage = 'Searching…',
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
      <PageSectionHeader
        title={sectionTitle}
        badgeCount={badgeCount}
        data-testid={`${uniqueId}-section`}
      />

      {sortedData.length === 0 && !hasNextPage ? (
        isLoading ? (
          <LoadingBox message={loadingMessage} data-testid={`${uniqueId}-section-loading`} />
        ) : (
          <InfoBox info={noDataMessage} data-testid={`${uniqueId}-section-info`} />
        )
      ) : (
        <>
          <TableContainer data-testid={`${uniqueId}-section-table`}>
            <Table>
              <TableHead>
                <TableRow sx={{ display: 'grid', gridTemplateColumns: gridTemplate }}>
                  <TableHeader
                    showThresholdDeadline={showThresholdDeadline}
                    showStatus={showStatus}
                    showVoteStats={showVoteStats}
                  />
                </TableRow>
              </TableHead>
              <TableBody sx={{ display: 'contents' }}>
                {sortedData.map((vote, index) => (
                  <VoteRow
                    key={index}
                    actionName={vote.actionName}
                    description={vote.description}
                    contractId={vote.contractId}
                    requester={vote.requester}
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

interface LoadingBoxProps {
  message: string;
  'data-testid': string;
}

const LoadingBox: React.FC<LoadingBoxProps> = ({ message, 'data-testid': testId }) => {
  return (
    <Stack
      gap={1.5}
      direction="row"
      alignItems="center"
      sx={{ width: 'max-content', p: 2 }}
      data-testid={testId}
    >
      <CircularProgress size={20} />
      <Typography fontSize={14} color="text.secondary">
        {message}
      </Typography>
    </Stack>
  );
};

interface VoteRowProps {
  actionName: string;
  description?: string;
  contractId: ContractId<VoteRequest>;
  requester: string;
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
    requester,
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
      <TableCell
        data-testid={`${uniqueId}-row-action-name`}
        sx={{
          ...governanceTableBodyCellSx,
          flexDirection: 'column',
          alignItems: 'flex-start',
          justifyContent: 'center',
          overflow: 'hidden',
        }}
      >
        <Typography
          {...tableBodyTypography}
          sx={{
            width: '100%',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          }}
        >
          {actionName}
        </Typography>
        {description && (
          <Typography
            data-testid={`${uniqueId}-row-description`}
            sx={{
              width: '100%',
              fontSize: 12,
              color: 'text.secondary',
              display: '-webkit-box',
              WebkitLineClamp: 2,
              WebkitBoxOrient: 'vertical',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              lineHeight: '20px',
            }}
          >
            {description}
          </Typography>
        )}
      </TableCell>
      <TableCell sx={identifierCellSx} data-testid={`${uniqueId}-row-contract-id`}>
        <CopyableIdentifier
          value={contractId}
          size="small"
          data-testid={`${uniqueId}-row-contract-id-value`}
        />
      </TableCell>
      {showThresholdDeadline ? (
        <>
          <TableCell
            sx={governanceTableBodyCellSx}
            data-testid={`${uniqueId}-row-voting-threshold-deadline`}
          >
            <TableBodyTypography>
              {formatDatetimeWithOffset(votingThresholdDeadline)}
            </TableBodyTypography>
          </TableCell>
          <SubmittedByCell requester={requester} uniqueId={uniqueId} />
          <TableCell
            sx={governanceTableBodyCellSx}
            data-testid={`${uniqueId}-row-vote-takes-effect`}
          >
            <TableBodyTypography>{formatEffectiveDate(voteTakesEffect)}</TableBodyTypography>
          </TableCell>
        </>
      ) : (
        <>
          <TableCell
            sx={governanceTableBodyCellSx}
            data-testid={`${uniqueId}-row-vote-takes-effect`}
          >
            <TableBodyTypography>{formatEffectiveDate(voteTakesEffect)}</TableBodyTypography>
          </TableCell>
          <SubmittedByCell requester={requester} uniqueId={uniqueId} />
          {showStatus && (
            <TableCell sx={governanceTableBodyCellSx} data-testid={`${uniqueId}-row-status`}>
              <TableBodyTypography>{status}</TableBodyTypography>
            </TableCell>
          )}
        </>
      )}
      {showVoteStats && (
        <TableCell sx={governanceTableBodyCellSx} data-testid={`${uniqueId}-row-all-votes`}>
          <AllVotes
            acceptedVotes={voteStats['accepted']}
            rejectedVotes={voteStats['rejected']}
            data-testid={`${uniqueId}-row-all-votes-stats`}
          />
        </TableCell>
      )}
      <TableCell sx={governanceTableBodyCellSx} data-testid={`${uniqueId}-row-your-vote`}>
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
