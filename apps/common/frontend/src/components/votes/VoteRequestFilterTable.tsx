// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { CopyableTypography, DateDisplay } from '@lfdecentralizedtrust/splice-common-frontend';
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import dayjs from 'dayjs';
import React from 'react';

import { Chip, Typography } from '@mui/material';
import type { GridColDef } from '@mui/x-data-grid';
import { DataGrid, GridEventListener, GridRenderCellParams, GridRowParams } from '@mui/x-data-grid';

import {
  ActionRequiringConfirmation,
  VoteRequest,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';

import { VoteRequestModalState } from './ListVoteRequests';

interface ListVoteRequestsTableProps {
  voteRequests: Contract<VoteRequest>[];
  getAction: (action: ActionRequiringConfirmation) => string;
  openModalWithVoteRequest: (voteRequestModalState: VoteRequestModalState) => void;
  tableBodyId: string;
}

export const VoteRequestsFilterTable: React.FC<ListVoteRequestsTableProps> = ({
  voteRequests,
  getAction,
  openModalWithVoteRequest,
  tableBodyId,
}) => {
  const columns: GridColDef[] = [
    {
      field: 'action',
      headerName: 'Action',
      width: 350,
      renderCell: (params: GridRenderCellParams) => {
        return (
          <Chip
            id="vote-request-modal-action-name"
            label={params.value}
            color="primary"
            className={'vote-row-action'}
          />
        );
      },
    },
    {
      field: 'trackingCid',
      headerName: 'Tracking Id',
      width: 250,
      renderCell: (params: GridRenderCellParams) => {
        return (
          <CopyableTypography
            text={params.value}
            maxWidth={'150px'}
            className={'vote-row-tracking-id'}
          />
        );
      },
    },
    {
      field: 'requester',
      headerName: 'Requester',
      width: 200,
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
      headerName: 'Expires At',
      type: 'date',
      width: 250,
      renderCell: (params: GridRenderCellParams) => {
        const now = dayjs();
        return dayjs(params.value).isBefore(now) ? (
          <Typography variant="h6" data-testid="vote-row-expiry-date">
            Did not expire
          </Typography>
        ) : (
          <DateDisplay datetime={params.value} id="vote-row-expiry-date" />
        );
      },
    },
    {
      field: 'effectiveAt',
      headerName: 'Effective At',
      type: 'date',
      width: 250,
      renderCell: (params: GridRenderCellParams) => {
        return typeof params.value === 'object' ? (
          <DateDisplay datetime={params.value} id="vote-row-effective-at" />
        ) : (
          'threshold'
        );
      },
    },
    {
      field: 'createdAt',
      headerName: 'Created At',
      type: 'date',
      width: 250,
      renderCell: (params: GridRenderCellParams) => {
        return <DateDisplay datetime={params.value} />;
      },
    },
  ];

  const rows = voteRequests.map((request, index) => ({
    id: index,
    trackingCid: request.payload.trackingCid || request.contractId,
    action: getAction(request.payload.action),
    requester: request.payload.requester,
    expiresAt: new Date(request.payload.voteBefore),
    effectiveAt: request.payload?.targetEffectiveAt
      ? new Date(request.payload.targetEffectiveAt)
      : undefined,
    createdAt: request.createdAt,
    voteStatus: request.contractId,
    idx: index,
  }));

  const handleRowClick: GridEventListener<'rowClick'> = (params: GridRowParams) => {
    openModalWithVoteRequest({
      open: true,
      voteRequestContractId: params.row.trackingCid,
      expiresAt: params.row.expiresAt,
      effectiveAt: params.row.effectiveAt,
    });
  };

  return (
    <div style={{ height: 450, width: '100%' }} id={tableBodyId} data-testid={tableBodyId}>
      <DataGrid
        rows={rows}
        columns={columns}
        initialState={{
          pagination: { paginationModel: { pageSize: 25 } },
          columns: {
            columnVisibilityModel: { createdAt: false },
          },
        }}
        pageSizeOptions={[5, 10, 25]}
        onRowClick={handleRowClick}
      />
    </div>
  );
};
