// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';
import { Card, CardContent, Chip, Stack } from '@mui/material';
import Typography from '@mui/material/Typography';
import { useTokenStandardAllocationRequests } from '../hooks/useTokenStandardAllocationRequests';
import { AmountDisplay, Loading } from '@lfdecentralizedtrust/splice-common-frontend';
import { AllocationRequest } from '@daml.js/splice-api-token-transfer-instruction/lib/Splice/Api/Token/AllocationRequestV1/module';
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { usePrimaryParty } from '../hooks';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import BigNumber from 'bignumber.js';
import BftAnsEntry from './BftAnsEntry';
import { ArrowCircleLeftOutlined } from '@mui/icons-material';

dayjs.extend(relativeTime);

const ListAllocationRequests: React.FC = () => {
  const allocationRequestsQuery = useTokenStandardAllocationRequests();
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

  return (
    <Stack spacing={4} direction="column" justifyContent="center" id="allocation-requests">
      <Typography mt={6} variant="h4">
        Allocation Requests <Chip label={allocationRequests.length} color="success" />
      </Typography>
      {allocationRequests.map(ar => (
        <AllocationRequestDisplay key={ar.contractId} request={ar} userParty={primaryPartyId} />
      ))}
    </Stack>
  );
};

const AllocationRequestDisplay: React.FC<{
  request: Contract<AllocationRequest>;
  userParty: string;
}> = ({ request, userParty }) => {
  const { transferLegs, settlement } = request.payload;

  const transferLegId = Object.keys(transferLegs).find(
    transferLegId => transferLegs[transferLegId].sender === userParty
  );
  const transferLeg = transferLegs[transferLegId || ''];
  if (!transferLeg) return null;

  const { amount, receiver } = transferLeg;
  const { allocateBefore, settlementRef, executor } = settlement;
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
          <ArrowCircleLeftOutlined fontSize="large" width="10%" />
          <Stack width="60%">
            {settlementRef.id ? (
              <Stack maxWidth="md">
                <Typography className="allocation-request-id" variant="body2" noWrap>
                  id: {settlementRef.id}
                </Typography>
              </Stack>
            ) : null}
            {settlementRef.cid ? (
              <Stack maxWidth="md">
                <Typography className="allocation-request-cid" variant="body2" noWrap>
                  cid: {settlementRef.cid}
                </Typography>
              </Stack>
            ) : null}
            <Stack direction="row" alignItems="center" spacing={1}>
              <Typography className="allocation-request-amount-to" variant="body2">
                <AmountDisplay amount={BigNumber(amount)} currency="AmuletUnit" /> to
              </Typography>
              <BftAnsEntry partyId={receiver} className="allocation-receiver" />
            </Stack>
            <Stack direction="row" alignItems="center" spacing={1}>
              <Typography variant="body2">via</Typography>
              <BftAnsEntry partyId={executor} className="allocation-executor" />
            </Stack>
            <Typography variant="body2">{dayjs(allocateBefore).fromNow(true)} left</Typography>
          </Stack>
          {/*TODO (#1100): uncomment and implement callback*/}
          {/*<Button variant="pill" size="small" className="transfer-offer-accept">*/}
          {/*  Accept*/}
          {/*</Button>*/}
          {/*TODO (#1413): uncomment and implement callback*/}
          {/*<Button variant="pill" color="warning" size="small" className="transfer-offer-accept">*/}
          {/*  Reject*/}
          {/*</Button>*/}
        </Stack>
      </CardContent>
    </Card>
  );
};

export default ListAllocationRequests;
