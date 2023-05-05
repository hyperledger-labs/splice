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

function getInfoTable(title: string, rows: { key: string; value: string }[]) {
  return (
    <TitledTable
      title={title}
      style={{ tableLayout: 'auto', accentColor: 'ActiveBorder', display: 'block' }}
    >
      <TableBody>
        {rows.map(row => (
          <TableRow key={row.key}>
            <TableCell align="left" className="key-name">
              {row.key}
            </TableCell>
            <TableCell align="left" className="value-name" style={{ wordBreak: 'break-all' }}>
              {row.value}
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </TitledTable>
  );
}

const SvcView: React.FC = () => {
  const resp = useSvUiState();
  if (resp) {
    var cs: { key: string; value: string }[] = [];
    resp.svcRules.payload.members.forEach((value, key) => cs.push(createRow(key, value.name)));
    const svInfos = [createRow('svUser', resp.svUser), createRow('svPartyId', resp.svPartyId)];
    const membersInfos: { key: string; value: string }[] = [];
    for (var member of cs) {
      membersInfos.push(createRow(`${member.value} PartyId`, member.key));
    }
    const svcInfos = [
      createRow(
        `${resp.svcRules.payload.members.get(resp.svcRules.payload.leader)!.name} svcLeaderPartyId`,
        resp.svcRules.payload.leader.toString()
      ),
      createRow('svcPartyId', resp.svcPartyId),
      createRow('coinRulesContractId', resp.coinRulesContractId),
      createRow('svcRulesContractId', resp.svcRules.contractId),
      createRow('isDevNet', resp.svcRules.payload.isDevNet ? 'True' : 'False'),
    ];
    const configInfos = [
      createRow(
        'svOnboardingConfirmedTimeout (μs)',
        resp.svcRules.payload.config.svOnboardingConfirmedTimeout.microseconds
      ),
      createRow(
        'actionConfirmationTimeout (μs)',
        resp.svcRules.payload.config.actionConfirmationTimeout.microseconds
      ),
      createRow(
        'maxNumCometBftNodes',
        resp.svcRules.payload.config.cometBftConfigLimits.maxNumCometBftNodes
      ),
      createRow(
        'numUnclaimedRewardsThreshold',
        resp.svcRules.payload.config.numUnclaimedRewardsThreshold
      ),
    ];
    return (
      <Box>
        {getInfoTable('Sv Infos', svInfos)}
        {getInfoTable('Svc Members', membersInfos)}
        {getInfoTable('Svc Infos', svcInfos)}
        {getInfoTable('Svc Config', configInfos)}
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

const SvcWithContexts: React.FC = () => {
  return (
    <SvClientProvider url={config.services.sv.url}>
      <SvUiStateProvider>
        <SvcView />
      </SvUiStateProvider>
    </SvClientProvider>
  );
};

export default SvcWithContexts;
