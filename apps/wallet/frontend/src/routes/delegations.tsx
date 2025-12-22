// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import React from 'react';

import {
  Box,
  Button,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import { Loading } from '@lfdecentralizedtrust/splice-common-frontend';

import { useMintingDelegations } from '../hooks/useMintingDelegations';

export const Delegations: React.FC = () => {
  const delegationsQuery = useMintingDelegations();

  if (delegationsQuery.isLoading) {
    return <Loading />;
  }

  if (delegationsQuery.isError) {
    return (
      <Typography color="error">
        Error loading delegations: {JSON.stringify(delegationsQuery.error)}
      </Typography>
    );
  }

  const delegations = delegationsQuery.data || [];

  const hasNoDelegations = delegations.length === 0;

  return (
    <Stack spacing={4} direction="column" justifyContent="center" id="delegations" marginTop={4}>
      <Typography variant="h4" id="delegations-label">
        Active
      </Typography>
      {hasNoDelegations ? (
        <Typography variant="h6" id="no-delegations-message">
          None active
        </Typography>
      ) : (
        <Table aria-label="delegations table">
        <TableHead>
          <TableRow>
            <TableCell>Beneficiary</TableCell>
            <TableCell>Max Amulets</TableCell>
            <TableCell>Expiration</TableCell>
            <TableCell>Actions</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {delegations.map(delegation => (
            <TableRow
              key={delegation.contractId}
              id={`delegation-row-${delegation.contractId}`}
              className="delegation-row"
            >
              <TableCell>
                <Typography className="delegation-beneficiary">
                  {delegation.payload.beneficiary}
                </Typography>
              </TableCell>
              <TableCell>
                <Typography className="delegation-max-amulets">
                  {delegation.payload.amuletMergeLimit}
                </Typography>
              </TableCell>
              <TableCell>
                <Typography className="delegation-expiration">
                  {delegation.payload.expiresAt}
                </Typography>
              </TableCell>
              <TableCell>
                <Button
                  variant="outlined"
                  size="small"
                  className="delegation-withdraw"
                >
                  Withdraw
                </Button>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
      )}
    </Stack>
  );
};

export default Delegations;
