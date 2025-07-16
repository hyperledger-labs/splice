// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';
import { Button, Card, CardContent, Chip, Stack } from '@mui/material';
import Typography from '@mui/material/Typography';
import { useTokenStandardAllocationRequests } from '../hooks/useTokenStandardAllocationRequests';
import { DisableConditionally, Loading } from '@lfdecentralizedtrust/splice-common-frontend';
import { AllocationRequest } from '@daml.js/splice-api-token-allocation-request/lib/Splice/Api/Token/AllocationRequestV1/module';
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { usePrimaryParty } from '../hooks';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import { useAmuletAllocations } from '../hooks/useAmuletAllocations';
import { AmuletAllocation } from '@daml.js/splice-amulet/lib/Splice/AmuletAllocation';
import MetaDisplay from './MetaDisplay';
import TransferLegsDisplay from './TransferLegsDisplay';
import { useWalletClient } from '../contexts/WalletServiceContext';
import { useMutation } from '@tanstack/react-query';
import { AllocateAmuletRequest } from 'wallet-openapi';
import {
  SettlementInfo,
  TransferLeg,
} from '@daml.js/splice-api-token-allocation/lib/Splice/Api/Token/AllocationV1/module';
import { damlTimestampToOpenApiTimestamp } from '../utils/timestampConversion';
import AllocationSettlementDisplay from './AllocationSettlementDisplay';

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
        <Stack width="100%" spacing={2}>
          <AllocationSettlementDisplay settlement={settlement} />
          {Object.keys(requestMeta.values).length > 0 ? (
            <>
              <Typography variant="h5">Request Meta</Typography>
              <MetaDisplay meta={requestMeta.values} />
            </>
          ) : null}
          <TransferLegsDisplay
            parentId={request.contractId}
            transferLegs={request.payload.transferLegs}
            getActionButton={(transferLegId, parentComponentId) => (
              <AllocationRequestActionButton
                parentComponentId={parentComponentId}
                allocationRequest={request}
                allocations={allocations}
                transferLegId={transferLegId}
                userParty={userParty}
              />
            )}
          />
        </Stack>
      </CardContent>
    </Card>
  );
};

const AllocationRequestActionButton: React.FC<{
  parentComponentId: string;
  allocationRequest: Contract<AllocationRequest>;
  allocations: Contract<AmuletAllocation>[];
  userParty: string;
  transferLegId: string;
}> = ({ parentComponentId, allocationRequest, transferLegId, userParty, allocations }) => {
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
  // return (
  //   <Button variant="pill" size="small" className="allocation-request-withdraw">
  //     Withdraw
  //   </Button>
  // );
  else
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
          id={`${parentComponentId}-accept`}
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
