// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  ErrorDisplay,
  getAmuletConfigurationAsOfNow,
  Loading,
  PartyId,
  TitledTable,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { Contract, durationToInterval } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { UseQueryResult } from '@tanstack/react-query';
import React from 'react';
import JSONPretty from 'react-json-pretty';
import 'react-json-pretty/themes/monikai.css';
import { CometBftNodeDumpOrErrorResponse, NodeStatus } from '@lfdecentralizedtrust/sv-openapi';

import { Box, Tab, Table, TableBody, TableRow, Tabs, Typography } from '@mui/material';
import TableCell from '@mui/material/TableCell';

import { AmuletRules } from '@daml.js/splice-amulet/lib/Splice/AmuletRules/';
import { SvNodeState } from '@daml.js/splice-dso-governance/lib/Splice/DSO/SvState';
import { DsoRules } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';

export interface DsoInfo {
  svUser: string;
  svPartyId: string;
  dsoPartyId: string;
  votingThreshold: bigint;
  amuletRules: Contract<AmuletRules>;
  dsoRules: Contract<DsoRules>;
  nodeStates: Contract<SvNodeState>[];
}

function encodeSvUiState(uiState: DsoInfo): unknown {
  return {
    ...uiState,
    votingThreshold: uiState.votingThreshold.toString(),
    amuletRules: Contract.encode(AmuletRules, uiState.amuletRules),
    dsoRules: Contract.encode(DsoRules, uiState.dsoRules),
    nodeStates: uiState.nodeStates.map(c => Contract.encode(SvNodeState, c)),
  };
}

function getInfoTable(title: string, rows: { key: string; value: string; isParty: boolean }[]) {
  return (
    <TitledTable
      title={title}
      style={{ tableLayout: 'auto', accentColor: 'ActiveBorder', display: 'block' }}
    >
      <TableBody>
        {rows.map(row => (
          <TableRow id={row.key} key={row.key}>
            <TableCell align="left" className="general-dso-key-name">
              {row.key}
            </TableCell>
            <TableCell
              align="left"
              className="general-dso-value-name"
              data-selenium-text={row.value}
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
const GeneralInformationView: React.FC<{ dsoInfo: DsoInfo }> = ({ dsoInfo }) => {
  const cs: { key: string; value: string }[] = [];
  dsoInfo.dsoRules.payload.svs.forEach((value, key) => cs.push(createRow(key, value.name)));
  const svInfos = [
    createRow('svUser', dsoInfo.svUser),
    createRow('svPartyId', dsoInfo.svPartyId, true),
  ];
  const membersInfos: { key: string; value: string; isParty: boolean }[] = [];
  for (const member of cs) {
    membersInfos.push(createRow(member.value, member.key, true));
  }
  const dsoInfos = [
    createRow('dsoPartyId', dsoInfo.dsoPartyId, true),
    createRow('dsoEpoch', dsoInfo.dsoRules.payload.epoch.toString()),
  ];
  return (
    <Box>
      {getInfoTable('Super Validator Information', svInfos)}
      {getInfoTable('Active Super Validators', membersInfos)}
      {getInfoTable('Decentralized Synchronizer Operations', dsoInfos)}
    </Box>
  );
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
    console.error(cometBftNodeDebugQuery.error);
    return <p>Error encountered in cometBFT node: {cometBftNodeDebugQuery.error.message} </p>;
  }

  const data = cometBftNodeDebugQuery.data!;

  return (
    <div>
      <JSONPretty id="comet-bft-debug-abci" style={{ fontSize: '10pt' }} data={data.abci_info} />
      <JSONPretty
        id="comet-bft-debug-validators"
        style={{ fontSize: '10pt' }}
        data={data.validators}
      />
      <JSONPretty id="comet-bft-debug-status" style={{ fontSize: '10pt' }} data={data.status} />
      <JSONPretty
        id="comet-bft-debug-network"
        style={{ fontSize: '10pt' }}
        data={data.network_info}
      />
    </div>
  );
}

const StatusDisplay: React.FC<{ status: UseQueryResult<NodeStatus> }> = ({ status }) => {
  if (status.isPending) {
    return <Loading />;
  }
  if (status.isError) {
    return <ErrorDisplay message={`Failed to fetch status: ${JSON.stringify(status.error)}`} />;
  }

  const data = status.data;

  if (data.failed) {
    return <ErrorDisplay message={`Failed to fetch status: ${data.failed.error}`} />;
  } else if (data.not_initialized) {
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
          <TableCell className="uptime-value">{durationToInterval(success.uptime)}</TableCell>
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
interface DsoViewPrettyJSONProps {
  dsoInfoQuery: UseQueryResult<DsoInfo>;
  cometBftNodeDebugQuery?: UseQueryResult<CometBftNodeDumpOrErrorResponse>;
  sequencerStatusQuery?: UseQueryResult<NodeStatus>;
  mediatorStatusQuery?: UseQueryResult<NodeStatus>;
}
const DsoViewPrettyJSON: React.FC<DsoViewPrettyJSONProps> = ({
  dsoInfoQuery,
  cometBftNodeDebugQuery,
  sequencerStatusQuery,
  mediatorStatusQuery,
}) => {
  const [value, setValue] = React.useState(0);

  const handleChange = (_event: React.SyntheticEvent, newValue: number) => {
    setValue(newValue);
  };

  if (dsoInfoQuery.isPending) {
    return <Loading />;
  }
  if (dsoInfoQuery.isError) {
    return <ErrorDisplay message={'Failed to fetch DSO info.'} />;
  }
  const dsoInfoData = dsoInfoQuery.data;

  const cometBftDebugTab = cometBftNodeDebugQuery && getCometBftDebugData(cometBftNodeDebugQuery);

  const updatedAmuletRules: AmuletRules = {
    dso: dsoInfoData?.amuletRules.payload.dso!,
    isDevNet: dsoInfoData?.amuletRules.payload.isDevNet!,
    configSchedule: getAmuletConfigurationAsOfNow(dsoInfoData?.amuletRules.payload.configSchedule!),
  };

  return (
    <>
      <Box mt={4} sx={{ borderBottom: 1, borderColor: 'divider' }}>
        <Tabs value={value} onChange={handleChange} aria-label="json tabs">
          <Tab label="General" {...tabProps('general')} />
          <Tab label="DSO Info" {...tabProps('dso-info')} />
          <Tab
            label={`${window.splice_config.spliceInstanceNames?.amuletName} Info`}
            {...tabProps('amulet-info')}
          />
          {cometBftDebugTab && <Tab label="CometBFT Debug Info" {...tabProps('cometBft-debug')} />}
          {sequencerStatusQuery && mediatorStatusQuery && (
            <Tab label="Domain Node Status" {...tabProps('canton-domain-status')} />
          )}
        </Tabs>
      </Box>
      <TabPanel value={value} index={0}>
        <GeneralInformationView dsoInfo={dsoInfoData} />
      </TabPanel>
      <TabPanel value={value} index={1}>
        <JSONPretty
          id="dso-rules-information"
          style={{ fontSize: '10pt' }}
          data={encodeSvUiState(dsoInfoData)}
        />
      </TabPanel>
      <TabPanel value={value} index={2}>
        <JSONPretty
          id="amulet-rules-information"
          style={{ fontSize: '10pt' }}
          data={AmuletRules.encode(updatedAmuletRules)}
        />
      </TabPanel>
      {cometBftDebugTab && (
        <TabPanel value={value} index={3}>
          {cometBftDebugTab}
        </TabPanel>
      )}
      {sequencerStatusQuery && mediatorStatusQuery && (
        <TabPanel value={value} index={4}>
          {cantonDomainTab(sequencerStatusQuery, mediatorStatusQuery)}
        </TabPanel>
      )}
    </>
  );
};

export default DsoViewPrettyJSON;
