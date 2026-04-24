// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';
import { Button, Card, CardContent, Chip, Stack } from '@mui/material';
import Typography from '@mui/material/Typography';
import { useTokenStandardAllocationRequests } from '../hooks/useTokenStandardAllocationRequests';
import { DisableConditionally, Loading } from '@lfdecentralizedtrust/splice-common-frontend';
import { AllocationRequest as AllocationRequestV2 } from '@daml.js/splice-api-token-allocation-request-v2/lib/Splice/Api/Token/AllocationRequestV2/module';
import { AllocationRequest as AllocationRequestV1 } from '@daml.js/splice-api-token-allocation-request/lib/Splice/Api/Token/AllocationRequestV1/module';
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { usePrimaryParty } from '../hooks';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import { useAmuletAllocations } from '../hooks/useAmuletAllocations';
import MetaDisplay from './MetaDisplay';
import TransferLegsDisplay from './TransferLegsDisplay';
import {
    useWalletClient,
    AllocationRequest,
    AmuletAllocation,
    isV2Allocation,
    isV2AllocationRequest
} from '../contexts/WalletServiceContext';
import { useMutation } from '@tanstack/react-query';
import { AllocateAmuletRequest, AllocateAmuletV2Request } from '@lfdecentralizedtrust/wallet-openapi';
import {
  SettlementInfo,
  TransferLeg,
} from '@daml.js/splice-api-token-allocation-v2/lib/Splice/Api/Token/AllocationV2/module';
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
    <Stack
      spacing={4}
      direction="column"
      justifyContent="center"
      id="allocation-requests"
      aria-labelledby="allocation-requests-label"
    >
      <Typography mt={6} variant="h4" id="allocation-requests-label">
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
  const payload = request.payload;
  const isV2 = isV2AllocationRequest(payload);
  const { settlement, transferLegs } = isV2
    ? { settlement: payload.settlement, transferLegs: payload.transferLegs }
    : v1RequestToV2Display(payload);
  const requestMeta = payload.meta;

  const { rejectAllocationRequest } = useWalletClient();
  const rejectAllocationRequestMutation = useMutation({
    mutationFn: async () => {
      return await rejectAllocationRequest(request.contractId);
    },
    onSuccess: () => {},
    onError: error => {
      console.error('Failed to reject allocation request', error);
    },
  });

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
          <Stack direction="row" width="100%" spacing={2}>
            <Chip label={isV2 ? 'V2' : 'V1'} color={isV2 ? 'primary' : 'default'} size="small" />
            <AllocationSettlementDisplay settlement={settlement} />
            <Button
              onClick={() => rejectAllocationRequestMutation.mutate()}
              color="error"
              size="medium"
              className="allocation-request-reject"
              sx={{ alignSelf: 'center', height: 'auto', minHeight: 0 }}
            >
              Reject
            </Button>
          </Stack>
          {Object.keys(requestMeta.values).length > 0 ? (
            <>
              <Typography variant="h5">Request Meta</Typography>
              <MetaDisplay meta={requestMeta.values} />
            </>
          ) : null}
          {isV2 ? (
            <>
              <TransferLegsDisplay
                parentId={request.contractId}
                transferLegs={transferLegs}
                // for v2, the action button operates over the entire request, not per transfer-leg
                getActionButton={() => null}
              />
              <V2AllocationRequestActionButton
                allocationRequest={request as Contract<AllocationRequestV2>}
                allocations={allocations}
                userParty={userParty}
              />
            </>
          ) : (
            <TransferLegsDisplay
              parentId={request.contractId}
              transferLegs={transferLegs}
              getActionButton={(transferLegId, parentComponentId) => (
                <V1AllocationRequestActionButton
                  parentComponentId={parentComponentId}
                  allocationRequest={request as Contract<AllocationRequestV1>}
                  allocations={allocations}
                  transferLegId={transferLegId}
                  userParty={userParty}
                />
              )}
            />
          )}
        </Stack>
      </CardContent>
    </Card>
  );
};

/** V2: one Accept button per request, filters amulet legs for userParty */
const V2AllocationRequestActionButton: React.FC<{
  allocationRequest: Contract<AllocationRequestV2>;
  allocations: Contract<AmuletAllocation>[];
  userParty: string;
}> = ({ allocationRequest, userParty, allocations }) => {
  const payload = allocationRequest.payload;
  const amuletLegsForUser = payload.transferLegs.filter(
    leg =>
      (leg.sender.owner === userParty || leg.receiver.owner === userParty) &&
      leg.instrumentId.id === 'Amulet'
  );
  const isAuthorizer =
    payload.authorizer.owner === userParty &&
    (payload.authorizer.provider === null || payload.authorizer.provider === undefined) &&
    payload.authorizer.id === '';
  // basicAccount check: authorizer matches basicAccount(userParty)
  const canAccept = amuletLegsForUser.length > 0 && isAuthorizer;

  const hasExistingAllocation = allocations.some(alloc =>
    isAllocationForRequest(alloc, allocationRequest)
  );

  const { createAllocationV2 } = useWalletClient();
  const createAllocationV2Mutation = useMutation({
    mutationFn: async () => {
      const req = openApiV2RequestFromAllocationRequest(payload.settlement, amuletLegsForUser);
      return await createAllocationV2(req);
    },
    onSuccess: () => {},
    onError: error => {
      console.error('Failed to submit allocation', error);
    },
  });


  // TODO (#4915): implement withdraw button for v2 when hasExistingAllocation
  if (!canAccept || hasExistingAllocation) return null;

  return (
    <DisableConditionally
      conditions={[
        { disabled: createAllocationV2Mutation.isPending, reason: 'Creating allocation...' },
      ]}
    >
      <Button
        variant="pill"
        size="small"
        className="allocation-request-accept"
        onClick={() => createAllocationV2Mutation.mutate()}
      >
        Accept
      </Button>
    </DisableConditionally>
  );
};

/** V1: one Accept/Withdraw button per transfer leg */
const V1AllocationRequestActionButton: React.FC<{
  parentComponentId: string;
  allocationRequest: Contract<AllocationRequestV1>;
  allocations: Contract<AmuletAllocation>[];
  userParty: string;
  transferLegId: string;
}> = ({ parentComponentId, allocationRequest, transferLegId, userParty, allocations }) => {
  const transferLeg = allocationRequest.payload.transferLegs[transferLegId];
  if (!transferLeg) return null;
  const actionAllowed =
    transferLeg.sender === userParty && transferLeg.instrumentId.id === 'Amulet';
  const correspondingAllocation = allocations.find(alloc =>
    isAllocationForTransferLeg(alloc, allocationRequest, transferLegId)
  );
  const alreadyAccepted = !!correspondingAllocation;

  const { createAllocation, withdrawAllocation } = useWalletClient();
  const createAllocationMutation = useMutation({
    mutationFn: async () => {
      const payload: AllocateAmuletRequest = openApiV1RequestFromTransferLeg(
        allocationRequest.payload.settlement,
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
  const withdrawAllocationMutation = useMutation({
    mutationFn: async () => {
      if (correspondingAllocation) {
        return await withdrawAllocation(correspondingAllocation.contractId);
      } else {
        throw new Error("This mutation shouldn't be called without a corresponding allocation");
      }
    },
    onSuccess: () => {},
    onError: error => {
      console.error('Failed to withdraw allocation', error);
    },
  });

  if (!actionAllowed) return null;
  if (alreadyAccepted) {
    return (
      <DisableConditionally
        conditions={[
          {
            disabled: withdrawAllocationMutation.isPending,
            reason: 'Withdrawing allocation...',
          },
        ]}
      >
        <Button
          id={`${parentComponentId}-withdraw`}
          variant="pill"
          size="small"
          className="allocation-withdraw"
          onClick={() => withdrawAllocationMutation.mutate()}
        >
          Withdraw
        </Button>
      </DisableConditionally>
    );
  }
  return (
    <DisableConditionally
      conditions={[
        { disabled: createAllocationMutation.isPending, reason: 'Creating allocation...' },
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

function isAllocationForRequest(
  allocation: Contract<AmuletAllocation>,
  allocationRequest: Contract<AllocationRequestV2>
): boolean {
  const payload = allocation.payload;
  return (
    payload.allocation.settlement.settlementRef.id ===
      allocationRequest.payload.settlement.settlementRef.id &&
    payload.allocation.settlement.settlementRef.cid ===
      allocationRequest.payload.settlement.settlementRef.cid
  );
}

function isAllocationForTransferLeg(
  allocation: Contract<AmuletAllocation>,
  allocationRequest: Contract<AllocationRequestV1>,
  legId: string
): boolean {
    let sameExecutor: boolean;
    let sameLegId: boolean;
    if (isV2Allocation(allocation.payload)) {
        sameExecutor = allocation.payload.allocation.settlement.executors.some(e => e === allocationRequest.payload.settlement.executor);
        sameLegId = allocation.payload.allocation.transferLegs.some(leg => leg.transferLegId === legId);
    } else {
        sameExecutor = allocation.payload.allocation.settlement.executor === allocationRequest.payload.settlement.executor;
        sameLegId = allocation.payload.allocation.transferLegId === legId;
    }
  return (
    sameExecutor &&
    allocation.payload.allocation.settlement.settlementRef.id ===
      allocationRequest.payload.settlement.settlementRef.id &&
    allocation.payload.allocation.settlement.settlementRef.cid ===
      allocationRequest.payload.settlement.settlementRef.cid &&
    sameLegId
  );
}

/** V1: build AllocateAmuletRequest from a single transfer leg, copying metadata */
export function openApiV1RequestFromTransferLeg(
  settlement: AllocationRequestV1['settlement'],
  transferLeg: AllocationRequestV1['transferLegs'][string],
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
      meta: { ...settlement.meta.values, ...transferLeg.meta.values },
    },
    transfer_leg_id: transferLegId,
    transfer_leg: {
      receiver: transferLeg.receiver,
      amount: transferLeg.amount,
      meta: transferLeg.meta.values,
    },
  };
}

/** V2: build AllocateAmuletV2Request from settlement + filtered transfer legs */
export function openApiV2RequestFromAllocationRequest(
  settlement: SettlementInfo,
  transferLegs: TransferLeg[]
): AllocateAmuletV2Request {
  return {
    settlement: {
      executors: settlement.executors,
      settlement_ref: {
        id: settlement.settlementRef.id,
        cid: settlement.settlementRef.cid as string,
      },
      requested_at: damlTimestampToOpenApiTimestamp(settlement.requestedAt),
      settle_at: damlTimestampToOpenApiTimestamp(settlement.settleAt),
      settlement_deadline: settlement.settlementDeadline
        ? damlTimestampToOpenApiTimestamp(settlement.settlementDeadline)
        : undefined,
      meta: settlement.meta.values,
    },
    transfer_legs: transferLegs.map(leg => ({
      transfer_leg_id: leg.transferLegId,
      sender: leg.sender.owner,
      receiver: leg.receiver.owner,
      amount: leg.amount,
      meta: leg.meta.values,
    })),
  };
}

/** Convert V1 AllocationRequest fields to V2 shapes for display */
function v1RequestToV2Display(payload: AllocationRequestV1): {
    settlement: SettlementInfo;
    transferLegs: TransferLeg[];
} {
    return {
        settlement: {
            executors: [payload.settlement.executor],
            settlementRef: payload.settlement.settlementRef,
            requestedAt: payload.settlement.requestedAt,
            settleAt: payload.settlement.settleBefore,
            settlementDeadline: null,
            meta: payload.settlement.meta,
        },
        transferLegs: Object.entries(payload.transferLegs).map(([legId, leg]) => ({
            transferLegId: legId,
            sender: { owner: leg.sender, provider: null, id: '' },
            receiver: { owner: leg.receiver, provider: null, id: '' },
            amount: leg.amount,
            instrumentId: leg.instrumentId,
            meta: leg.meta,
        })),
    };
}

export default ListAllocationRequests;
