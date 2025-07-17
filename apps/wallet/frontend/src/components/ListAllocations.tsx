// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';
import { useAmuletAllocations } from '../hooks/useAmuletAllocations';
import { Loading } from '@lfdecentralizedtrust/splice-common-frontend';
import Typography from '@mui/material/Typography';
import { Card, CardContent, Chip, Stack } from '@mui/material';
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { AmuletAllocation } from '@daml.js/splice-amulet/lib/Splice/AmuletAllocation';
import TransferLegsDisplay from './TransferLegsDisplay';
import AllocationSettlementDisplay from './AllocationSettlementDisplay';

const ListAllocations: React.FC = () => {
  const allocationsQuery = useAmuletAllocations();

  if (allocationsQuery.isLoading) {
    return <Loading />;
  }
  if (allocationsQuery.isError) {
    return (
      <Typography color="error">
        Error loading allocations: {JSON.stringify(allocationsQuery.error)}
      </Typography>
    );
  }

  const allocations = allocationsQuery.data || [];

  return (
    <Stack spacing={4} direction="column" justifyContent="center" id="allocations">
      <Typography mt={6} variant="h4">
        Allocations <Chip label={allocations.length} color="success" />
      </Typography>
      {allocations.map(allocation => (
        <AllocationDisplay key={allocation.contractId} allocation={allocation} />
      ))}
    </Stack>
  );
};

const AllocationDisplay: React.FC<{ allocation: Contract<AmuletAllocation> }> = ({
  allocation,
}) => {
  const { settlement, transferLeg, transferLegId } = allocation.payload.allocation;
  return (
    <Card className="allocation" variant="outlined">
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
          <TransferLegsDisplay
            parentId={allocation.contractId}
            transferLegs={{
              [transferLegId]: transferLeg,
            }}
            getActionButton={() => <WithdrawAllocationButton />}
          />
        </Stack>
      </CardContent>
    </Card>
  );
};

// TODO (#1503): implement
const WithdrawAllocationButton: React.FC = () => {
  return null;
  // return (
  //   <Button variant="pill" size="small" className="allocation-withdraw">
  //     Withdraw
  //   </Button>
  // );
};

export default ListAllocations;
