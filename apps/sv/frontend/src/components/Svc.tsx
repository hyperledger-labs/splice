import { UseQueryResult } from '@tanstack/react-query';
import { ErrorDisplay, Loading, PartyId, SvClientProvider, TitledTable } from 'common-frontend';
import React from 'react';
import JSONPretty from 'react-json-pretty';
import 'react-json-pretty/themes/monikai.css';
import { CometBftNodeDumpOrErrorResponse, NodeStatus } from 'sv-openapi';

import { Box, Tab, Table, TableBody, TableRow, Tabs, Typography } from '@mui/material';
import TableCell from '@mui/material/TableCell';

import { CoinRules } from '@daml.js/canton-coin-0.1.0/lib/CC/Coin';
import { SvcRules } from '@daml.js/svc-governance/lib/CN/SvcRules/module';

import { useSvcInfos } from '../contexts/SvContext';
import { useCometBftDebug } from '../hooks/useCometBftDebug';
import { useMediatorStatus } from '../hooks/useMediatorStatus';
import { useSequencerStatus } from '../hooks/useSequencerStatus';
import { config } from '../utils';

function getInfoTable(title: string, rows: { key: string; value: string; isParty: boolean }[]) {
  return (
    <TitledTable
      title={title}
      style={{ tableLayout: 'auto', accentColor: 'ActiveBorder', display: 'block' }}
    >
      <TableBody>
        {rows.map(row => (
          <TableRow id={row.key} key={row.key}>
            <TableCell align="left" className="general-svc-key-name">
              {row.key}
            </TableCell>
            <TableCell
              align="left"
              className="general-svc-value-name"
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
function createRow(key: string, value: string, isParty: boolean = false) {
  return { key, value, isParty };
}
const GeneralInformationView: React.FC = () => {
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
      createRow('svcEpoch', data.svcRules.payload.epoch.toString()),
    ];
    return (
      <Box>
        {getInfoTable('Super Validator Information', svInfos)}
        {getInfoTable('Super Validator Collective Members', membersInfos)}
        {getInfoTable('Super Validator Collective Information', svcInfos)}
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

interface TabPanelProps {
  children?: React.ReactNode;
  index: number;
  value: number;
}

const TabPanel = (props: TabPanelProps) => {
  const { children, value, index, ...other } = props;
  return (
    <div
      role="tabpanel"
      hidden={value !== index}
      id={`simple-tabpanel-${index}`}
      aria-labelledby={`simple-tab-${index}`}
      {...other}
    >
      {value === index && <Box sx={{ p: 3 }}>{children}</Box>}
    </div>
  );
};

function getCometBftDebugData(
  cometBftNodeDebugQuery: UseQueryResult<CometBftNodeDumpOrErrorResponse>
) {
  if (cometBftNodeDebugQuery.isLoading) {
    return <Loading />;
  }
  if (cometBftNodeDebugQuery.isError) {
    return <p>Error, something went wrong.</p>;
  }

  const data = cometBftNodeDebugQuery.data;

  if (data.error) {
    return <p>Error encountered in cometBFT node: {data.error?.error} </p>;
  }

  var JSONPrettyMon = require('react-json-pretty/dist/monikai');
  return (
    <div>
      <JSONPretty
        id="comet-bft-debug-status"
        style={{ fontSize: '10pt' }}
        data={data.response?.status}
        theme={JSONPrettyMon}
      />
      <JSONPretty
        id="comet-bft-debug-network"
        style={{ fontSize: '10pt' }}
        data={data.response?.networkInfo}
        theme={JSONPrettyMon}
      />
    </div>
  );
}

const StatusDisplay: React.FC<{ status: UseQueryResult<NodeStatus> }> = ({ status }) => {
  if (status.isLoading) {
    return <Loading />;
  }
  if (status.isError) {
    return <ErrorDisplay message={`Failed to fetch status: ${JSON.stringify(status.error)}`} />;
  }

  const data = status.data;

  if (data.notInitialized) {
    return <div>Not initialized</div>;
  }

  const success = data.success!;
  return (
    <Table>
      <TableBody>
        <TableRow>
          <TableCell>active</TableCell>
          <TableCell className="active-value">{success.active.toString()}</TableCell>
        </TableRow>
        <TableRow>
          <TableCell>uptime</TableCell>
          <TableCell className="uptime-value">{success.uptime}</TableCell>
        </TableRow>
      </TableBody>
    </Table>
  );
};

function cantonDomainTab(
  sequencerStatus: UseQueryResult<NodeStatus>,
  mediatorStatus: UseQueryResult<NodeStatus>
) {
  const sequencerSection = (
    <div>
      <Typography mt={6} variant="h4">
        Sequencer status
      </Typography>
      <StatusDisplay status={sequencerStatus} />
    </div>
  );
  const mediatorSection = (
    <div>
      <Typography mt={6} variant="h4">
        Mediator status
      </Typography>
      <StatusDisplay status={mediatorStatus} />
    </div>
  );
  return (
    <div>
      {sequencerSection}
      {mediatorSection}
    </div>
  );
}

function tabProps(info: string) {
  return {
    id: `information-tab-${info}`,
    'aria-controls': `information-panel-${info}`,
  };
}
const SvcViewPrettyJSON = () => {
  const [value, setValue] = React.useState(0);

  const handleChange = (event: React.SyntheticEvent, newValue: number) => {
    setValue(newValue);
  };

  const svcInfoResp = useSvcInfos();
  const cometBftNodeDebugQuery = useCometBftDebug();
  const sequencerStatusQuery = useSequencerStatus();
  const mediatorStatusQuery = useMediatorStatus();

  if (svcInfoResp.isLoading) {
    return <Loading />;
  }
  const svcInfoData = svcInfoResp.data;
  var JSONPrettyMon = require('react-json-pretty/dist/monikai');

  const cometBftDebugTab = getCometBftDebugData(cometBftNodeDebugQuery);

  return (
    <>
      <Box mt={4} sx={{ borderBottom: 1, borderColor: 'divider' }}>
        <Tabs value={value} onChange={handleChange} aria-label="json tabs">
          <Tab label="General" {...tabProps('general')} />
          <Tab label="SVC Configuration" {...tabProps('svc-configuration')} />
          <Tab label="Canton Coin Configuration" {...tabProps('cc-configuration')} />
          <Tab label="CometBFT Debug Info" {...tabProps('cometBft-debug')} />
          <Tab label="Domain Node Status" {...tabProps('canton-domain-status')} />
        </Tabs>
      </Box>
      <TabPanel value={value} index={0}>
        <GeneralInformationView />
      </TabPanel>
      <TabPanel value={value} index={1}>
        <JSONPretty
          id="svc-rules-information"
          style={{ fontSize: '10pt' }}
          data={SvcRules.encode(svcInfoData?.svcRules.payload!)}
          theme={JSONPrettyMon}
        />
      </TabPanel>
      <TabPanel value={value} index={2}>
        <JSONPretty
          id="coin-rules-information"
          style={{ fontSize: '10pt' }}
          data={CoinRules.encode(svcInfoData?.coinRules.payload!)}
          theme={JSONPrettyMon}
        />
      </TabPanel>
      <TabPanel value={value} index={3}>
        {cometBftDebugTab}
      </TabPanel>
      <TabPanel value={value} index={4}>
        {cantonDomainTab(sequencerStatusQuery, mediatorStatusQuery)}
      </TabPanel>
    </>
  );
};

const SvcWithContexts: React.FC = () => {
  return (
    <SvClientProvider url={config.services.sv.url}>
      <SvcViewPrettyJSON />
    </SvClientProvider>
  );
};

export default SvcWithContexts;
