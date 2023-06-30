import { IntervalDisplay, DateDisplay } from 'common-frontend';
import React from 'react';

import { Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';

import { useEntriesWithPayData } from '../hooks';

const DirectoryEntries: React.FC = () => {
  const ownedEntries = useEntriesWithPayData();

  return (
    <div id="directory-entries">
      <Typography variant="h5">Your Directory Entries</Typography>
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
          {ownedEntries.map(entry => (
            <TableRow key={entry.contractId} className="entries-table-row">
              <TableCell className="entries-table-name">{entry.entryName}</TableCell>
              <TableCell className="entries-table-amount">{entry.amount}</TableCell>
              <TableCell className="entries-table-currency">{entry.currency}</TableCell>
              <TableCell className="entries-table-expires-at">
                <DateDisplay datetime={entry.expiresAt} />
              </TableCell>
              <TableCell className="entries-table-payment-interval">
                <IntervalDisplay microseconds={entry.paymentInterval} />
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
};

export default DirectoryEntries;
