import { useCallback, useState } from 'react';

import { Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';

import { DirectoryEntry } from '@daml.js/directory/lib/CN/Directory';

import { useLedgerApiClient } from '../contexts/LedgerApiContext';
import { Contract, sameContracts, useInterval } from '../utils';

const DirectoryEntries: React.FC<{ primaryParty: string; provider: string }> = ({
  primaryParty,
  provider,
}) => {
  const [directoryEntries, setDirectoryEntries] = useState<Contract<DirectoryEntry>[]>([]);
  const ledgerApiClient = useLedgerApiClient();
  const fetchDirectoryEntries = useCallback(async () => {
    const current = await ledgerApiClient.queryOwnedDirectoryEntries(primaryParty, provider);
    setDirectoryEntries(prev => (sameContracts(prev, current) ? prev : current));
  }, [ledgerApiClient, primaryParty, provider]);
  useInterval(fetchDirectoryEntries, 500);

  return (
    <div>
      <Typography variant="h6">Your Directory Entries</Typography>
      <Table>
        <TableHead>
          <TableRow>
            <TableCell>Entry Name</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {directoryEntries.map(entry => (
            <TableRow key={entry.contractId} className="entries-table-row">
              <TableCell className="entries-table-name">{entry.payload.name}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
};

export default DirectoryEntries;
