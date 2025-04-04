// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  IntervalDisplay,
  DateDisplay,
  Loading,
  ErrorDisplay,
  unitStringToCurrency,
} from '@lfdecentralizedtrust/splice-common-frontend';
import React from 'react';

import { Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';

import { useEntriesWithPayData } from '../hooks';
import { useAnsConfig } from '../utils';

const AnsEntries: React.FC = () => {
  const config = useAnsConfig();
  const nameServiceAcronym = config.spliceInstanceNames.nameServiceNameAcronym;
  const { data: ownedEntries, isError, isLoading } = useEntriesWithPayData();

  return (
    <div id="ans-entries">
      <Typography variant="h5">Your {nameServiceAcronym} Entries</Typography>
      {isLoading ? (
        <Loading />
      ) : isError ? (
        <ErrorDisplay message="Error while loading entries" />
      ) : (
        <Table sx={{ marginTop: '16px' }} id="entries-table">
          <TableHead>
            <TableRow>
              <TableCell>Name</TableCell>
              <TableCell>Amount</TableCell>
              <TableCell>Currency</TableCell>
              <TableCell>Expires At</TableCell>
              <TableCell>Interval</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {ownedEntries.entries.map(entry => (
              <TableRow key={entry.contractId} className="entries-table-row">
                <TableCell className="entries-table-name">{entry.name}</TableCell>
                <TableCell className="entries-table-amount">{entry.amount}</TableCell>
                <TableCell className="entries-table-currency">
                  {unitStringToCurrency(entry.unit)}
                </TableCell>
                <TableCell className="entries-table-expires-at">
                  <DateDisplay datetime={new Date(Number(entry.expiresAt))} />
                </TableCell>
                <TableCell className="entries-table-payment-interval">
                  <IntervalDisplay microseconds={entry.paymentInterval} />
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </div>
  );
};

export default AnsEntries;
