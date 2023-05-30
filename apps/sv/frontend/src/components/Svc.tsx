import { Loading, PartyId, SvClientProvider, TitledTable } from 'common-frontend';
import React from 'react';
import JSONPretty from 'react-json-pretty';
import 'react-json-pretty/themes/monikai.css';

import { Box, Tab, TableBody, TableRow, Tabs } from '@mui/material';
import TableCell from '@mui/material/TableCell';

import { useSvcInfos } from '../contexts/SvContext';
import { config } from '../utils';

function getInfoTable(title: string, rows: { key: string; value: string; isParty: boolean }[]) {
  return (
    <TitledTable
      title={title}
      style={{ tableLayout: 'auto', accentColor: 'ActiveBorder', display: 'block' }}
    >
      <TableBody>
        {rows.map(row => (
          <TableRow key={row.key}>
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

  const resp = useSvcInfos();
  if (resp.isLoading) {
    return <Loading />;
  }
  const data = resp.data;
  var JSONPrettyMon = require('react-json-pretty/dist/monikai');

  return (
    <>
      <Box sx={{ borderBottom: 1, borderColor: 'divider' }}>
        <Tabs value={value} onChange={handleChange} aria-label="json tabs">
          <Tab label="General" {...tabProps('general')} />
          <Tab label="SVC Configuration" {...tabProps('svc-configuration')} />
          <Tab label="Canton Coin Configuration" {...tabProps('cc-configuration')} />
        </Tabs>
      </Box>
      <TabPanel value={value} index={0}>
        <GeneralInformationView />
      </TabPanel>
      <TabPanel value={value} index={1}>
        <JSONPretty
          id="svc-rules-information"
          style={{ fontSize: '10pt' }}
          data={data?.svcRules}
          theme={JSONPrettyMon}
        />
      </TabPanel>
      <TabPanel value={value} index={2}>
        <JSONPretty
          id="coin-rules-information"
          style={{ fontSize: '10pt' }}
          data={data?.coinRules}
          theme={JSONPrettyMon}
        />
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
