import { Loading, PartyId } from 'common-frontend';
import dayjs from 'dayjs';
import React, { ReactElement, useState } from 'react';

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
import { Schedule } from '@daml.js/canton-coin-0.1.0/lib/CC/Schedule';
import { EnabledChoices } from '@daml.js/canton-coin-api-0.1.0/lib/CC/API/V1/Coin';
import {
  ActionRequiringConfirmation,
  SvcRulesConfig,
} from '@daml.js/svc-governance/lib/CN/SvcRules/module';

import { useSvcInfos } from '../../../../contexts/SvContext';

const ActionView: React.FC<{ action: ActionRequiringConfirmation }> = ({ action }) => {
  const svcInfosQuery = useSvcInfos();

  if (svcInfosQuery.isLoading) {
    return <Loading />;
  }

  if (svcInfosQuery.isError) {
    return <p>Not yet implemented.</p>;
  }

  if (!svcInfosQuery.data) {
    return <p>no VoteRequest contractId is specified</p>;
  }

  const actionType = action.tag;

  const trueElement = <Typography>True</Typography>;
  const falseElement = <Typography>False</Typography>;

  function convertEnabledChoices(booleanObject: EnabledChoices): {
    [key: string]: ReactElement<JSX.Element>;
  } {
    return Object.keys(booleanObject).reduce((result, key) => {
      // @ts-ignore
      result[key] = booleanObject[key] ? trueElement : falseElement;
      return result;
    }, {});
  }

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
            dropdownMap={coinRulesAction.value.newConfigSchedule}
          />
        );
      }
      case 'CRARC_SetEnabledChoices': {
        return (
          <ActionValueTable
            actionType={actionType}
            actionName={coinRulesAction.tag}
            valuesMap={convertEnabledChoices(coinRulesAction.value.newEnabledChoices)}
          />
        );
      }
      case 'CRARC_AddFutureCoinConfigSchedule': {
        return (
          <ActionValueTable
            actionType={actionType}
            actionName={coinRulesAction.tag}
            valuesMap={{
              Time: (
                <PrettyJsonPrint
                  data={dayjs(coinRulesAction.value.newScheduleItem._1)
                    .toString()
                    .replace('GMT', 'UTC')}
                />
              ),
              NewScheduleItem: <PrettyJsonPrint data={coinRulesAction.value.newScheduleItem._2} />,
            }}
          />
        );
      }
      case 'CRARC_RemoveFutureCoinConfigSchedule': {
        return (
          <ActionValueTable
            actionType={actionType}
            actionName={coinRulesAction.tag}
            valuesMap={{
              Time: (
                <PrettyJsonPrint
                  data={dayjs(coinRulesAction.value.scheduleTime).toString().replace('GMT', 'UTC')}
                />
              ),
              ScheduleItem: (
                <PrettyJsonPrint
                  data={
                    svcInfosQuery.data?.coinRules.payload.configSchedule.futureValues.find(
                      e => e._1 === coinRulesAction.value.scheduleTime
                    )?._2
                  }
                />
              ),
            }}
          />
        );
      }
      case 'CRARC_UpdateFutureCoinConfigSchedule': {
        return (
          <ActionValueTable
            actionType={actionType}
            actionName={coinRulesAction.tag}
            valuesMap={{
              Time: (
                <PrettyJsonPrint
                  data={dayjs(coinRulesAction.value.scheduleItem._1)
                    .toString()
                    .replace('GMT', 'UTC')}
                />
              ),
              ScheduleItem: <PrettyJsonPrint data={coinRulesAction.value.scheduleItem._2} />,
            }}
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
  dropdownMap?: Schedule<string, CoinConfig<USD>>;
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
                <TableRow key={key} id={key}>
                  <TableCell>
                    <Typography variant="h6">{key}</Typography>
                  </TableCell>
                  <TableCell>
                    {typeof valuesMap[key] == 'boolean'
                      ? valuesMap[key].toString()
                      : valuesMap[key]}
                  </TableCell>
                </TableRow>
              ))}
          </TableBody>
        </Table>
      </TableContainer>
      {dropdownMap && (
        <DropdownSchedules
          initialValue={dropdownMap.initialValue}
          futureValues={dropdownMap.futureValues}
        />
      )}
    </>
  );
};

const PrettyJsonPrint: React.FC<{
  data?: SvcRulesConfig | CoinConfig<USD> | string;
}> = ({ data }) => {
  return (
    <pre style={{ whiteSpace: 'pre-wrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
      {typeof data !== 'string' ? JSON.stringify(data, null, 2) : data}
    </pre>
  );
};

//TODO(#6930): remove it when retire setconfigschedule
const DropdownSchedules: React.FC<{
  initialValue: CoinConfig<USD>;
  futureValues: Tuple2<string, CoinConfig<USD>>[];
}> = ({ initialValue, futureValues }) => {
  interface DropdownOption {
    value: string;
    label: string;
  }

  const dropdownOptions: DropdownOption[] = [
    { value: JSON.stringify(initialValue, null, 2), label: 'Current Configuration' },
    ...futureValues.map(value => ({
      value: JSON.stringify(value._2, null, 2),
      label: `Configuration from: ${dayjs(value._1).toString().replace('GMT', 'UTC')}`,
    })),
  ];

  const [selectedOption, setSelectedOption] = useState<string>('Current Configuration');

  const handleOptionChange = (option: string) => {
    setSelectedOption(option);
  };

  return (
    <>
      <FormControl>
        <NativeSelect
          inputProps={{ id: 'dropdown-display-schedules-datetime' }}
          value={selectedOption}
          onChange={e => handleOptionChange(e.target.value)}
        >
          {dropdownOptions &&
            dropdownOptions.map((option, index) => (
              <option key={'member-option-' + index} value={option.label}>
                {option.label}
              </option>
            ))}
        </NativeSelect>
      </FormControl>
      <PrettyJsonPrint data={dropdownOptions.filter(e => e.label === selectedOption)[0].value} />
    </>
  );
};

export default ActionView;
