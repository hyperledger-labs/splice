import { Loading, SvClientProvider } from 'common-frontend';
import TitledTable from 'common-frontend/lib/components/TitledTable';
import React from 'react';

import { Box } from '@mui/material';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableRow from '@mui/material/TableRow';

import { SvUiStateProvider, useSvUiState } from '../contexts/SvContext';
import { config } from '../utils';

function createRow(key: string, value: string) {
  return { key, value };
}

const DebugView: React.FC = () => {
  const resp = useSvUiState();
  if (resp) {
    const rows = [
      createRow('Sv Name', resp.svUser),
      createRow('Sv PartyId', resp.svPartyId),
      createRow('Svc PartyId', resp.svcPartyId),
      createRow('Svc Rules ContractId', resp.svcRulesContractId),
      createRow('Coin Rules ContractId', resp.coinRulesContractId),
    ];
    return (
      <Box>
        <TitledTable title={'Debug Infos'}>
          <TableBody>
            {rows.map(row => (
              <TableRow key={row.key} sx={{ '&:last-child td, &:last-child th': { border: 0 } }}>
                <TableCell align="left" className="key-name">
                  {row.key}
                </TableCell>
                <TableCell align="left" className="value-name">
                  {row.value}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </TitledTable>
      </Box>
    );
  } else {
    return (
      <Box>
        <Loading />
      </Box>
    );
  }
};

const DebugWithContexts: React.FC = () => {
  return (
    <SvClientProvider url={config.services.sv.url}>
      <SvUiStateProvider>
        <DebugView />
      </SvUiStateProvider>
    </SvClientProvider>
  );
};

export default DebugWithContexts;
