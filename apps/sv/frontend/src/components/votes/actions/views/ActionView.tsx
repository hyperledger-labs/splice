import { PartyId } from 'common-frontend';
import React, { useState } from 'react';

import {
  Chip,
  FormControl,
  NativeSelect,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableRow,
  Typography,
} from '@mui/material';

import { Tuple2 } from '@daml.js/40f452260bef3f29dede136108fc08a88d5a5250310281067087da6f0baddff7/lib/DA/Types';
import { CoinConfig, USD } from '@daml.js/canton-coin-0.1.0/lib/CC/CoinConfig';
import { SvcRulesConfig } from '@daml.js/svc-governance/lib/CN/SvcRules/module';

import { ActionRequiringConfirmation } from '../../../../../../../common/frontend/daml.js/svc-governance-0.1.0/lib/CN/SvcRules';

const ActionView: React.FC<{ action: ActionRequiringConfirmation }> = ({ action }) => {
  const actionType = action.tag;

  if (action.tag === 'ARC_SvcRules') {
    const svcAction = action.value.svcAction;
    switch (svcAction.tag) {
      case 'SRARC_RemoveMember': {
        return (
          <ActionValueTable
            actionType={actionType}
            actionName={svcAction.tag}
            valuesMap={{
              Member: <PartyId partyId={svcAction.value.member} />,
            }}
          />
        );
      }
      case 'SRARC_GrantFeaturedAppRight': {
        return (
          <ActionValueTable
            actionType={actionType}
            actionName={svcAction.tag}
            valuesMap={{
              Provider: <PartyId partyId={svcAction.value.provider} />,
            }}
          />
        );
      }
      case 'SRARC_RevokeFeaturedAppRight': {
        return (
          <ActionValueTable
            actionType={actionType}
            actionName={svcAction.tag}
            valuesMap={{
              FeatureAppRightCid: <PartyId partyId={svcAction.value.rightCid} />,
            }}
          />
        );
      }
      case 'SRARC_SetConfig': {
        return (
          <ActionValueTable
            actionType={actionType}
            actionName={svcAction.tag}
            valuesMap={{
              NewConfig: <PrettyJsonPrint data={svcAction.value.newConfig} />,
            }}
          />
        );
      }
    }
  } else if (action.tag === 'ARC_CoinRules') {
    const coinRulesAction = action.value.coinRulesAction;
    switch (coinRulesAction.tag) {
      case 'CRARC_SetConfigSchedule': {
        return (
          <ActionValueTable
            actionType={actionType}
            actionName={coinRulesAction.tag}
            dropdownMap={coinRulesAction.value.newConfigSchedule.futureValues}
          />
        );
      }
    }
  }
  return <p>Not yet implemented for this action</p>;
};

const ActionValueTable: React.FC<{
  actionType: string;
  actionName: string;
  valuesMap?: { [key: string]: React.ReactElement };
  dropdownMap?: Tuple2<string, CoinConfig<USD>>[];
}> = ({ actionType, actionName, valuesMap, dropdownMap }) => {
  return (
    <>
      <TableContainer>
        <Table style={{ tableLayout: 'auto' }} className="sv-voting-table">
          <TableBody>
            <TableRow>
              <TableCell>
                <Typography variant="h6">Action Type</Typography>
              </TableCell>
              <TableCell>
                <Chip id="vote-request-modal-action-type" label={actionType} color="primary" />
              </TableCell>
            </TableRow>
            <TableRow>
              <TableCell>
                <Typography variant="h6">Action Name</Typography>
              </TableCell>
              <TableCell>
                <Chip id="vote-request-modal-action-name" label={actionName} color="primary" />
              </TableCell>
            </TableRow>
            {valuesMap &&
              Object.keys(valuesMap).map(key => (
                <TableRow key={key}>
                  <TableCell>
                    <Typography variant="h6">{key}</Typography>
                  </TableCell>
                  <TableCell>{valuesMap[key]}</TableCell>
                </TableRow>
              ))}
          </TableBody>
        </Table>
      </TableContainer>
      {dropdownMap && <DropdownSchedules values={dropdownMap} />}
    </>
  );
};

const PrettyJsonPrint: React.FC<{
  data: SvcRulesConfig | CoinConfig<USD> | string;
}> = ({ data }) => {
  return (
    <pre style={{ whiteSpace: 'pre-wrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
      {typeof data !== 'string' && JSON.stringify(data, null, 2)}
      {typeof data === 'string' && data}
    </pre>
  );
};

const DropdownSchedules: React.FC<{
  values: Tuple2<string, CoinConfig<USD>>[];
}> = ({ values }) => {
  interface DropdownOption {
    value: string;
    label: string;
  }

  const dropdownOptions: DropdownOption[] = values.map(value => ({
    value: JSON.stringify(value._2, null, 2),
    label: value._1,
  }));

  const [selectedOption, setSelectedOption] = useState<React.ReactElement>(
    <PrettyJsonPrint data={dropdownOptions[0].value} />
  );

  const handleOptionChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    setSelectedOption(<PrettyJsonPrint data={e.target.value} />);
  };

  return (
    <>
      <FormControl>
        <NativeSelect
          inputProps={{ id: 'dropdown-display-schedules-datetime' }}
          value={selectedOption}
          onChange={handleOptionChange}
        >
          {dropdownOptions.map((scheduleDate, index) => (
            <option key={'schedule-option-' + index} value={scheduleDate.value}>
              {scheduleDate.label}
            </option>
          ))}
        </NativeSelect>
      </FormControl>

      {selectedOption}
    </>
  );
};

export default ActionView;
