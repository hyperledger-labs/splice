// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';
import {
  Button,
  Card,
  CardContent,
  Chip,
  Container,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
} from '@mui/material';
import Typography from '@mui/material/Typography';
import { useTokenStandardAllocationRequests } from '../hooks/useTokenStandardAllocationRequests';
import {
  DateWithDurationDisplay,
  DisableConditionally,
  Loading,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { AllocationRequest } from '@daml.js/splice-api-token-transfer-instruction/lib/Splice/Api/Token/AllocationRequestV1/module';
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { usePrimaryParty } from '../hooks';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import BftAnsEntry from './BftAnsEntry';
import { useAmuletAllocations } from '../hooks/useAmuletAllocations';
import { AmuletAllocation } from '@daml.js/splice-amulet/lib/Splice/AmuletAllocation';
import { useMutation } from '@tanstack/react-query';
import { useWalletClient } from '../contexts/WalletServiceContext';
import { AllocateAmuletRequest } from 'wallet-openapi';
import { damlTimestampToOpenApiTimestamp } from '../utils/timestampConversion';
import {
  SettlementInfo,
  TransferLeg,
} from '@daml.js/splice-api-token-allocation/lib/Splice/Api/Token/AllocationV1/module';

dayjs.extend(relativeTime);

const ListAllocationRequests: React.FC = () => {
  const allocationRequestsQuery = useTokenStandardAllocationRequests();
  const allocationsQuery = useAmuletAllocations();
  const primaryPartyId = usePrimaryParty();

  if (allocationRequestsQuery.isLoading || !primaryPartyId) {
    return <Loading />;
  }
  if (allocationRequestsQuery.isError) {
    return (
      <Typography color="error">
        Error loading allocation requests: {JSON.stringify(allocationRequestsQuery.error)}
      </Typography>
    );
  }

  const allocationRequests = allocationRequestsQuery.data || [];
  const allocations = allocationsQuery.data || [];

  return (
    <Stack spacing={4} direction="column" justifyContent="center" id="allocation-requests">
      <Typography mt={6} variant="h4">
        Allocation Requests <Chip label={allocationRequests.length} color="success" />
      </Typography>
      {allocationRequests.map(ar => (
        <AllocationRequestDisplay
          key={ar.contractId}
          request={ar}
          userParty={primaryPartyId}
          allocations={allocations}
        />
      ))}
    </Stack>
  );
};

const AllocationRequestDisplay: React.FC<{
  request: Contract<AllocationRequest>;
  allocations: Contract<AmuletAllocation>[];
  userParty: string;
}> = ({ request, userParty, allocations }) => {
  const { settlement, meta: requestMeta } = request.payload;
  const {
    settleBefore,
    requestedAt,
    allocateBefore,
    settlementRef,
    executor,
    meta: settlementMeta,
  } = settlement;
  return (
    <Card className="allocation-request" variant="outlined">
      <CardContent
        sx={{
          display: 'flex',
          direction: 'row',
          justifyContent: 'space-between',
          alignItems: 'center',
        }}
      >
        <Stack direction="row" alignItems="center" spacing={4}>
          <Stack width="100%">
            <Stack direction="row">
              <Stack width="100%">
                {settlementRef.id ? (
                  <Stack maxWidth="md">
                    <Typography className="allocation-request-id" variant="body2" noWrap>
                      SettlementRef id: {settlementRef.id}
                    </Typography>
                  </Stack>
                ) : null}
                {settlementRef.cid ? (
                  <Stack maxWidth="md">
                    <Typography className="allocation-request-cid" variant="body2" noWrap>
                      SettlementRef cid: {settlementRef.cid}
                    </Typography>
                  </Stack>
                ) : null}
              </Stack>
              {/*TODO (#1413): uncomment and implement callback*/}
              {/*<Button variant="pill" size="small" className="allocation-request-reject">*/}
              {/*  Reject*/}
              {/*</Button>*/}
            </Stack>
            <Stack direction="row" alignItems="center" spacing={1}>
              <Typography variant="body2">Executor:</Typography>
              <BftAnsEntry partyId={executor} className="allocation-executor" />
            </Stack>
            <Typography variant="body2">
              Requested at: <DateWithDurationDisplay datetime={requestedAt} enableDuration />
            </Typography>
            <Typography variant="body2">
              Allocate before: <DateWithDurationDisplay datetime={allocateBefore} enableDuration />
            </Typography>
            <Typography variant="body2">
              Settle before: <DateWithDurationDisplay datetime={settleBefore} enableDuration />
            </Typography>
            {Object.keys(requestMeta.values).length > 0 ? (
              <>
                <Typography variant="h5">Request Meta</Typography>
                <MetaDisplay meta={requestMeta.values} />
              </>
            ) : null}
            {Object.keys(settlementMeta.values).length > 0 ? (
              <>
                <Typography variant="h5">Settlement Meta</Typography>
                <MetaDisplay meta={settlementMeta.values} />
              </>
            ) : null}
            <Container>
              <TransferLegsDisplay
                allocationRequest={request}
                userParty={userParty}
                allocations={allocations}
              />
            </Container>
          </Stack>
        </Stack>
      </CardContent>
    </Card>
  );
};

const TransferLegsDisplay: React.FC<{
  allocationRequest: Contract<AllocationRequest>;
  allocations: Contract<AmuletAllocation>[];
  userParty: string;
}> = ({ allocationRequest, userParty, allocations }) => {
  const transferLegs = allocationRequest.payload.transferLegs;
  const ids = Object.keys(transferLegs).toSorted();
  return (
    <Table>
      <TableHead>
        <TableRow>
          <TableCell>Id</TableCell>
          <TableCell>Sender</TableCell>
          <TableCell>Receiver</TableCell>
          <TableCell align="right">Amount</TableCell>
          <TableCell>Meta</TableCell>
          <TableCell />
        </TableRow>
      </TableHead>
      <TableBody>
        {ids.map(transferLegId => {
          const { meta, sender, receiver, instrumentId, amount } = transferLegs[transferLegId];
          const id = `transfer-leg-${allocationRequest.contractId}-${transferLegId}`;
          return (
            <TableRow key={transferLegId} id={id} className="allocation-row">
              <TableCell>
                <Typography variant="body2" className="allocation-legid">
                  {transferLegId}
                </Typography>
              </TableCell>
              <TableCell>
                <BftAnsEntry partyId={sender} className="allocation-sender" />
              </TableCell>
              <TableCell>
                <BftAnsEntry partyId={receiver} className="allocation-receiver" />
              </TableCell>
              <TableCell>
                <Typography variant="body2" className="allocation-amount-instrument">
                  {amount} {instrumentId.id}
                </Typography>
              </TableCell>
              <TableCell>
                <MetaDisplay meta={meta.values} />
              </TableCell>
              <TableCell>
                <AllocationRequestActionButton
                  id={id}
                  allocationRequest={allocationRequest}
                  allocations={allocations}
                  transferLegId={transferLegId}
                  userParty={userParty}
                />
              </TableCell>
            </TableRow>
          );
        })}
      </TableBody>
    </Table>
  );
};

const AllocationRequestActionButton: React.FC<{
  id: string;
  allocationRequest: Contract<AllocationRequest>;
  allocations: Contract<AmuletAllocation>[];
  userParty: string;
  transferLegId: string;
}> = ({ id, allocationRequest, transferLegId, userParty, allocations }) => {
  const transferLeg = allocationRequest.payload.transferLegs[transferLegId];
  const actionAllowed =
    transferLeg.sender === userParty && transferLeg.instrumentId.id === 'Amulet';
  const settlement = allocationRequest.payload.settlement;
  const alreadyAccepted = !!allocations.find(alloc =>
    isAllocationForTransferLeg(alloc, allocationRequest, transferLegId)
  );

  const { createAllocation } = useWalletClient();
  const createAllocationMutation = useMutation({
    mutationFn: async () => {
      const payload: AllocateAmuletRequest = openApiRequestFromTransferLeg(
        settlement,
        transferLeg,
        transferLegId
      );
      return await createAllocation(payload);
    },
    onSuccess: () => {},
    onError: error => {
      console.error('Failed to submit allocation', error);
    },
  });

  if (!actionAllowed) return null;
  // TODO (#1413): show the withdraw button and implement the callback, instead of showing nothing
  if (alreadyAccepted) return null;
  //   return (
  //     <Button variant="pill" size="small" className="allocation-request-withdraw">
  //       Withdraw
  //     </Button>
  //   );
  // else
  return (
    <DisableConditionally
      conditions={[
        {
          disabled: createAllocationMutation.isPending,
          reason: 'Creating allocation...',
        },
      ]}
    >
      <Button
        id={`${id}-accept`}
        variant="pill"
        size="small"
        className="allocation-request-accept"
        onClick={() => createAllocationMutation.mutate()}
      >
        Accept
      </Button>
    </DisableConditionally>
  );
};

const MetaDisplay: React.FC<{ meta: { [key: string]: string } }> = ({ meta }) => {
  return (
    <Stack spacing={2}>
      {Object.keys(meta)
        .toSorted()
        .map(key => {
          const value = meta[key];
          return (
            <Stack key={`meta-${key}`} overflow="hidden" textOverflow="ellipsis" maxWidth="150px">
              <Typography variant="body2" noWrap>
                {key}:
              </Typography>
              <Typography variant="body2" noWrap>
                {value}
              </Typography>
            </Stack>
          );
        })}
    </Stack>
  );
};

function isAllocationForTransferLeg(
  allocation: Contract<AmuletAllocation>,
  allocationRequest: Contract<AllocationRequest>,
  legId: string
): boolean {
  return (
    allocation.payload.allocation.settlement.executor ===
      allocationRequest.payload.settlement.executor &&
    allocation.payload.allocation.settlement.settlementRef.id ===
      allocationRequest.payload.settlement.settlementRef.id &&
    allocation.payload.allocation.settlement.settlementRef.cid ===
      allocationRequest.payload.settlement.settlementRef.cid &&
    allocation.payload.allocation.transferLegId === legId
  );
}

export function openApiRequestFromTransferLeg(
  settlement: SettlementInfo,
  transferLeg: TransferLeg,
  transferLegId: string
): AllocateAmuletRequest {
  return {
    settlement: {
      executor: settlement.executor,
      settlement_ref: {
        id: settlement.settlementRef.id,
        cid: settlement.settlementRef.cid as string,
      },
      requested_at: damlTimestampToOpenApiTimestamp(settlement.requestedAt),
      allocate_before: damlTimestampToOpenApiTimestamp(settlement.allocateBefore),
      settle_before: damlTimestampToOpenApiTimestamp(settlement.settleBefore),
      meta: settlement.meta.values,
    },
    transfer_leg_id: transferLegId,
    transfer_leg: {
      receiver: transferLeg.receiver,
      amount: transferLeg.amount,
      meta: transferLeg.meta.values,
    },
  };
}

export default ListAllocationRequests;
