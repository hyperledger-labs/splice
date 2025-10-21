// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { useVotesHooks } from '@lfdecentralizedtrust/splice-common-frontend';
import {
  CopyableTypography,
  DateDisplay,
  Loading,
} from '@lfdecentralizedtrust/splice-common-frontend';
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import React, { useEffect, useState } from 'react';

import CheckIcon from '@mui/icons-material/Check';
import ClearIcon from '@mui/icons-material/Clear';
import { Chip, Stack, Typography } from '@mui/material';
import {
  DataGrid,
  GridColDef,
  GridEventListener,
  GridFilterItem,
  GridFilterModel,
  GridRenderCellParams,
  GridRowParams,
} from '@mui/x-data-grid';

import * as damlTypes from '@daml/types';
import {
  ActionRequiringConfirmation,
  Vote,
  DsoRules_CloseVoteRequestResult,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';

import { VoteResultModalState } from './ListVoteRequests';

dayjs.extend(utc);

export type VoteRequestResultTableType = 'Executed' | 'Rejected';

interface ListVoteResultsTableProps {
  getAction: (action: ActionRequiringConfirmation, staled: boolean) => string;
  tableBodyId: string;
  tableType: VoteRequestResultTableType;
  openModalWithVoteResult: (voteResultModalState: VoteResultModalState) => void;
  accepted: boolean;
  effectiveFrom?: string;
  effectiveTo?: string;
  validityColumnName?: string;
}

type VoteRequestResultRow = {
  id: number;
  actionName: string;
  requester: string;
  expiresAt: Date;
  effectiveAt: Date;
  idx: number;
  voteResult: DsoRules_CloseVoteRequestResult;
  expired: boolean;
  voteStatus: string[][];
};

type VoteResultQueryOptions = {
  accepted?: boolean;
  effectiveTo?: string;
  effectiveFrom?: string;
  actionName?: string;
  requester?: string;
};

const QUERY_LIMIT = 500;

export const VoteResultsFilterTable: React.FC<ListVoteResultsTableProps> = ({
  getAction,
  tableBodyId,
  tableType,
  openModalWithVoteResult,
  accepted,
  effectiveFrom,
  effectiveTo,
  validityColumnName,
}) => {
  const votesHooks = useVotesHooks();

  const [queryOptions, setQueryOptions] = useState<VoteResultQueryOptions>({
    accepted: accepted,
    effectiveTo: effectiveTo,
    effectiveFrom: effectiveFrom,
  });
  const voteResultsQuery = votesHooks.useListVoteRequestResult(
    QUERY_LIMIT,
    queryOptions.actionName,
    queryOptions.requester,
    queryOptions.effectiveFrom,
    queryOptions.effectiveTo,
    queryOptions.accepted
  );

  const [rows, setRows] = useState<VoteRequestResultRow[]>([]);

  useEffect(() => {
    const rows: VoteRequestResultRow[] = getVoteRequestResultRowsByCategory(
      voteResultsQuery.data,
      tableType
    );

    if (tableType === 'Rejected') {
      rows.sort((a, b) => (dayjs(a.effectiveAt).isAfter(dayjs(b.effectiveAt)) ? -1 : 1));
    }

    setRows(rows);
    setQueryOptions({
      accepted: accepted,
      effectiveTo: effectiveTo,
      effectiveFrom: effectiveFrom,
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [voteResultsQuery.data, effectiveTo, effectiveFrom, accepted]);

  const onFilterChange = (filterModel: GridFilterModel) => {
    if (filterModel.items.length > 0) {
      if (filterModel.items[0].field === 'actionName') {
        setQueryOptions({
          accepted: accepted,
          effectiveTo: effectiveTo,
          effectiveFrom: effectiveFrom,
          actionName: filterModel.items[0].value,
        });
      } else if (filterModel.items[0].field === 'requester') {
        setQueryOptions({
          accepted: accepted,
          effectiveTo: effectiveTo,
          effectiveFrom: effectiveFrom,
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

  const columns: GridColDef[] = [
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
    { field: 'accepted', headerName: 'Accepted', width: 120, filterable: false },
    { field: 'expired', headerName: 'Expired', width: 120, filterable: false },
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
      field: 'effectiveAt',
      headerName: validityColumnName ? validityColumnName : 'Executed At',
      width: 200,
      type: 'date',
      renderCell: (params: GridRenderCellParams) => {
        return <DateDisplay datetime={params.value} />;
      },
    },
    {
      field: 'expiresAt',
      headerName: 'Expired At',
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

  function getVoteStatus(votes: damlTypes.Map<string, Vote>) {
    const allVotes: Vote[] = votes.entriesArray().map(v => v[1]);
    const rejectedBy: string[] = allVotes.filter(v => !v.accept).map(sv => sv.sv);
    const acceptedBy: string[] = allVotes.filter(v => v.accept).map(sv => sv.sv);
    return [rejectedBy, acceptedBy];
  }

  function getVoteRequestResultRowsByCategory(
    voteResults: DsoRules_CloseVoteRequestResult[] | undefined,
    tableType: VoteRequestResultTableType
  ): VoteRequestResultRow[] {
    const now = dayjs();
    const parsedVoteResults = voteResults
      ? voteResults.filter((result, _) => {
          if (
            result.outcome.tag === 'VRO_Accepted' &&
            dayjs(result.outcome.value.effectiveAt).isBefore(now) &&
            tableType === 'Executed'
          ) {
            return true;
          } else {
            return (
              ['VRO_Rejected', 'VRO_Expired'].includes(result.outcome.tag) &&
              tableType === 'Rejected'
            );
          }
        })
      : [];
    return parsedVoteResults
      ? parsedVoteResults.map((result: DsoRules_CloseVoteRequestResult, index) => ({
          id: index,
          actionName: getAction(result.request.action, false),
          requester: result.request.requester,
          expiresAt: new Date(result.request.voteBefore),
          effectiveAt: new Date(
            (result.outcome.tag === 'VRO_Accepted' && result.outcome.value.effectiveAt) ||
              result.completedAt
          ),
          idx: index,
          voteResult: result,
          expired: result.outcome.tag === 'VRO_Expired',
          voteStatus: getVoteStatus(result.request.votes),
        }))
      : [];
  }

  function getQueryOptionsFromDateFilter(item: GridFilterItem): VoteResultQueryOptions {
    if (item.operator.includes('after')) {
      return {
        accepted: accepted,
        effectiveTo: effectiveTo ? effectiveTo : undefined,
        effectiveFrom: effectiveFrom
          ? dayjs(effectiveFrom).isBefore(dayjs(item.value))
            ? dayjs(item.value).format('YYYY-MM-DDTHH:mm:ss[Z]')
            : effectiveFrom
          : dayjs(item.value).format('YYYY-MM-DDTHH:mm:ss[Z]'),
      };
    } else if (item.operator.includes('before')) {
      return {
        accepted: accepted,
        effectiveTo: effectiveTo
          ? dayjs(effectiveTo).isAfter(dayjs(item.value))
            ? dayjs(item.value).format('YYYY-MM-DDTHH:mm:ss[Z]')
            : effectiveTo
          : dayjs(item.value).format('YYYY-MM-DDTHH:mm:ss[Z]'),
        effectiveFrom: effectiveFrom ? effectiveFrom : undefined,
      };
    } else {
      return {
        accepted: accepted,
        effectiveTo: effectiveTo ? effectiveTo : undefined,
        effectiveFrom: effectiveFrom ? effectiveFrom : undefined,
      };
    }
  }

  const handleRowClick: GridEventListener<'rowClick'> = (params: GridRowParams) => {
    openModalWithVoteResult({
      open: true,
      voteResult: params.row.voteResult,
      tableType: tableType,
      effectiveAt: params.row.effectiveAt,
    });
  };

  return (
    <div style={{ height: 450, width: '100%' }} id={tableBodyId}>
      <DataGrid
        rows={rows}
        columns={columns}
        initialState={{
          pagination: { paginationModel: { pageSize: 25 } },
          columns: {
            columnVisibilityModel: { votedAt: false, accepted: false },
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
