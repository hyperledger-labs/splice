import { Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';

import { useDirectoryUiState } from '../contexts/DirectoryContext';

const DirectoryEntries: React.FC = () => {
  const { directoryEntries } = useDirectoryUiState();

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
