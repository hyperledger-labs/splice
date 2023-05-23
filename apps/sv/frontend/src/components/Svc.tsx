import { Loading, PartyId, SvClientProvider } from 'common-frontend';
import TitledTable from 'common-frontend/lib/components/TitledTable';
import React from 'react';

import { Box } from '@mui/material';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableRow from '@mui/material/TableRow';

import { useSvcInfos } from '../contexts/SvContext';
import { config } from '../utils';

function createRow(key: string, value: string, isParty: boolean = false) {
  return { key, value, isParty };
}

function getInfoTable(title: string, rows: { key: string; value: string; isParty: boolean }[]) {
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
            <TableCell
              align="left"
              className="value-name"
              style={{ wordBreak: row.isParty ? 'normal' : 'break-all' }}
            >
              {row.isParty ? <PartyId partyId={row.value} /> : row.value}
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </TitledTable>
  );
}

const SvcView: React.FC = () => {
  const resp = useSvcInfos();
  if (!resp.isLoading) {
    const data = resp.data!;
    var cs: { key: string; value: string }[] = [];
    data.svcRules.payload.members.forEach((value, key) => cs.push(createRow(key, value.name)));
    const svInfos = [
      createRow('svUser', data.svUser),
      createRow('svPartyId', data.svPartyId, true),
    ];
    const membersInfos: { key: string; value: string; isParty: boolean }[] = [];
    for (var member of cs) {
      membersInfos.push(createRow(member.value, member.key, true));
    }
    const svcInfos = [
      createRow('svcLeaderPartyId', data.svcRules.payload.leader.toString(), true),
      createRow('svcPartyId', data.svcPartyId, true),
      createRow('coinRulesContractId', data.coinRulesContractId),
      createRow('svcRulesContractId', data.svcRules.contractId),
      createRow('isDevNet', data.svcRules.payload.isDevNet ? 'True' : 'False'),
    ];
    const configInfos = [
      createRow(
        'svOnboardingConfirmedTimeout (μs)',
        data.svcRules.payload.config.svOnboardingConfirmedTimeout.microseconds
      ),
      createRow(
        'actionConfirmationTimeout (μs)',
        data.svcRules.payload.config.actionConfirmationTimeout.microseconds
      ),
      createRow(
        'maxNumCometBftNodes',
        data.svcRules.payload.config.domainNodeConfigLimits.cometBft.maxNumCometBftNodes
      ),
      // TODO(#4902): display all fields of the config, in particular the ones related to the global domain configuration
      createRow(
        'numUnclaimedRewardsThreshold',
        data.svcRules.payload.config.numUnclaimedRewardsThreshold
      ),
    ];
    return (
      <Box>
        {getInfoTable('Super Validator Information', svInfos)}
        {getInfoTable('Super Validator Collective Members', membersInfos)}
        {getInfoTable('Super Validator Collective Information', svcInfos)}
        {getInfoTable('Super Validator Collective Configuration', configInfos)}
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
      <SvcView />
    </SvClientProvider>
  );
};

export default SvcWithContexts;
