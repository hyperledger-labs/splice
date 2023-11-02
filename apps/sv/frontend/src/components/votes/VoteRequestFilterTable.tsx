import { Contract, CopyableTypography, DateDisplay } from 'common-frontend';
import React from 'react';

import { Chip } from '@mui/material';
import { DataGrid, GridEventListener, GridRenderCellParams, GridRowParams } from '@mui/x-data-grid';

import {
  ActionRequiringConfirmation,
  SvcRules,
  VoteRequest,
} from '@daml.js/svc-governance/lib/CN/SvcRules/module';
import { ContractId } from '@daml/types';

interface ListVoteRequestsTableProps {
  voteRequests: Contract<VoteRequest>[];
  getAction: (action: ActionRequiringConfirmation, staled: boolean) => string;
  svcRules: Contract<SvcRules>;
  openModalWithVoteRequest: (
    voteRequestContractId: ContractId<VoteRequest>,
    staled: boolean
  ) => void;
  tableBodyId: string;
  staled: boolean;
}

export const ListVoteRequestsFilterTable: React.FC<ListVoteRequestsTableProps> = ({
  voteRequests,
  getAction,
  svcRules,
  openModalWithVoteRequest,
  tableBodyId,
  staled,
}) => {
  const columns = [
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
      field: 'contractId',
      headerName: 'Contract Id',
      width: 250,
      renderCell: (params: GridRenderCellParams) => {
        return <CopyableTypography text={params.value} maxWidth={'150px'} />;
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
        return <DateDisplay datetime={params.value} />;
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
    contractId: request.contractId,
    action: getAction(request.payload.action, staled),
    requester: svcRules.payload.members.get(request.payload.requester)?.name!,
    expiresAt: new Date(request.payload.expiresAt),
    createdAt: request.createdAt,
    voteStatus: request.contractId,
    idx: index,
    staled: staled,
  }));

  const handleRowClick: GridEventListener<'rowClick'> = (params: GridRowParams) => {
    openModalWithVoteRequest(params.row.contractId, staled);
  };

  return (
    <div style={{ height: 450, width: '100%' }} id={tableBodyId}>
      <DataGrid
        rows={rows}
        columns={columns}
        initialState={{
          pagination: { paginationModel: { pageSize: 5 } },
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
