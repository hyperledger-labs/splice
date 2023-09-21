import { CopyableTypography, DateDisplay, Loading } from 'common-frontend';
import dayjs from 'dayjs';
import React, { useEffect, useState } from 'react';

import CheckIcon from '@mui/icons-material/Check';
import ClearIcon from '@mui/icons-material/Clear';
import { Chip, Stack, Typography } from '@mui/material';
import {
  DataGrid,
  GridEventListener,
  GridFilterItem,
  GridFilterModel,
  GridRenderCellParams,
  GridRowParams,
} from '@mui/x-data-grid';

import {
  ActionRequiringConfirmation,
  VoteResult,
} from '@daml.js/svc-governance/lib/CN/SvcRules/module';

import { useListSvcRulesVoteResults } from '../../hooks/useListVoteRequests';

interface ListVoteResultsTableProps {
  getAction: (action: ActionRequiringConfirmation, staled: boolean) => string;
  tableBodyId: string;
  openModalWithVoteResult: (action: ActionRequiringConfirmation) => void;
  executed: boolean;
  effectiveFrom?: string;
  effectiveTo?: string;
  validityColumnName?: string;
}

type VoteResultRow = {
  id: number;
  actionName: string;
  requester: string;
  expiresAt: Date;
  votedAt: Date;
  idx: number;
  action: ActionRequiringConfirmation;
  executed: boolean;
  voteStatus: string[][];
};

type VoteResultQueryOptions = {
  executed?: boolean;
  effectiveTo?: string;
  effectiveFrom?: string;
  actionName?: string;
  requester?: string;
};

const QUERY_LIMIT = 50;

export const VoteResultsFilterTable: React.FC<ListVoteResultsTableProps> = ({
  getAction,
  tableBodyId,
  openModalWithVoteResult,
  executed,
  effectiveFrom,
  effectiveTo,
  validityColumnName,
}) => {
  const [queryOptions, setQueryOptions] = useState<VoteResultQueryOptions>({
    executed: executed,
    effectiveTo: effectiveTo ? effectiveTo : undefined,
    effectiveFrom: effectiveFrom ? effectiveFrom : undefined,
  });
  const voteResultsQuery = useListSvcRulesVoteResults(queryOptions, QUERY_LIMIT);

  const [rows, setRows] = useState<VoteResultRow[]>([]);

  useEffect(() => {
    const rows: VoteResultRow[] = getVoteResultRows(voteResultsQuery.data);
    setRows(rows);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [voteResultsQuery.data]);

  const onFilterChange = (filterModel: GridFilterModel) => {
    if (filterModel.items.length > 0) {
      if (filterModel.items[0].field === 'actionName') {
        setQueryOptions({
          executed: executed,
          effectiveTo: effectiveTo ? effectiveTo : undefined,
          effectiveFrom: effectiveFrom ? effectiveFrom : undefined,
          actionName: filterModel.items[0].value,
        });
      } else if (filterModel.items[0].field === 'requester') {
        setQueryOptions({
          executed: executed,
          effectiveTo: effectiveTo ? effectiveTo : undefined,
          effectiveFrom: effectiveFrom ? effectiveFrom : undefined,
          requester: filterModel.items[0].value,
        });
      } else if (filterModel.items[0].field === 'expiresAt') {
        setQueryOptions(getQueryOptionsFromDateFilter(filterModel.items[0]));
      }
    }
  };

  if (voteResultsQuery.isLoading) {
    return <Loading />;
  }

  if (voteResultsQuery.isError) {
    return <p>Error, something went wrong.</p>;
  }

  const columns = [
    {
      field: 'actionName',
      headerName: 'Action Name',
      width: 300,
      renderCell: (params: GridRenderCellParams) => {
        return (
          <Chip
            id="vote-request-modal-action-name"
            label={params.value}
            color="secondary"
            className={'vote-row-action'}
          />
        );
      },
    },
    { field: 'executed', headerName: 'Executed', width: 120, filterable: false },
    {
      field: 'requester',
      headerName: 'Requester',
      width: 250,
      renderCell: (params: GridRenderCellParams) => {
        return (
          <CopyableTypography
            text={params.value}
            maxWidth={'150px'}
            className={'vote-row-requester'}
          />
        );
      },
    },
    {
      field: 'expiresAt',
      headerName: validityColumnName ? validityColumnName : 'Executed At',
      width: 200,
      type: 'date',
      renderCell: (params: GridRenderCellParams) => {
        return <DateDisplay datetime={params.value} />;
      },
    },
    {
      field: 'votedAt',
      headerName: 'Voted At',
      width: 200,
      type: 'date',
      renderCell: (params: GridRenderCellParams) => {
        return <DateDisplay datetime={params.value} />;
      },
      filterable: false,
    },
    {
      field: 'voteStatus',
      headerName: 'Vote Status',
      width: 200,
      renderCell: (params: GridRenderCellParams) => {
        const rejectedVotes = params.value[0];
        const acceptedVotes = params.value[1];
        return (
          <Stack spacing={4} direction="row">
            <Typography id={'vote-request-modal-rejected-count'} variant="h6">
              <ClearIcon color="error" fontSize="inherit" /> {rejectedVotes.length}
            </Typography>
            <Typography id={'vote-request-modal-accepted-count'} variant="h6">
              <CheckIcon color="success" fontSize="inherit" /> {acceptedVotes.length}
            </Typography>
          </Stack>
        );
      },
      filterable: false,
    },
  ];

  function getVoteResultRows(voteResults: VoteResult[] | undefined) {
    return voteResults
      ? voteResults.map((result, index) => ({
          id: index,
          actionName: getAction(result.action, false),
          requester: result.requester,
          expiresAt: new Date(result.effectiveAt),
          votedAt: new Date(result.votedAt),
          idx: index,
          action: result.action,
          executed: result.executed,
          voteStatus: [result.rejectedBy, result.acceptedBy],
        }))
      : [];
  }

  function getQueryOptionsFromDateFilter(item: GridFilterItem): VoteResultQueryOptions {
    if (item.operator.includes('after')) {
      return {
        executed: executed,
        effectiveTo: effectiveTo ? effectiveTo : undefined,
        effectiveFrom: effectiveFrom
          ? dayjs(effectiveFrom).isBefore(dayjs(item.value))
            ? dayjs(item.value).format('YYYY-MM-DDTHH:mm:ss[Z]')
            : effectiveFrom
          : dayjs(item.value).format('YYYY-MM-DDTHH:mm:ss[Z]'),
      };
    } else if (item.operator.includes('before')) {
      return {
        executed: executed,
        effectiveTo: effectiveTo
          ? dayjs(effectiveTo).isAfter(dayjs(item.value))
            ? dayjs(item.value).format('YYYY-MM-DDTHH:mm:ss[Z]')
            : effectiveTo
          : dayjs(item.value).format('YYYY-MM-DDTHH:mm:ss[Z]'),
        effectiveFrom: effectiveFrom ? effectiveFrom : undefined,
      };
    } else {
      return {
        executed: executed,
        effectiveTo: effectiveTo ? effectiveTo : undefined,
        effectiveFrom: effectiveFrom ? effectiveFrom : undefined,
      };
    }
  }

  const handleRowClick: GridEventListener<'rowClick'> = (params: GridRowParams) => {
    openModalWithVoteResult(params.row.action);
  };

  return (
    <div style={{ height: 450, width: '100%' }} id={tableBodyId}>
      <DataGrid
        rows={rows}
        columns={columns}
        initialState={{
          pagination: { paginationModel: { pageSize: 5 } },
          columns: {
            columnVisibilityModel: { votedAt: false, executed: false },
          },
        }}
        pageSizeOptions={[5, 10, 25]}
        onRowClick={handleRowClick}
        onFilterModelChange={onFilterChange}
        filterMode={'server'}
        loading={voteResultsQuery.isLoading}
      />
    </div>
  );
};
