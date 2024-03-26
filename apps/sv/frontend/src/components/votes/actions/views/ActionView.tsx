import { DateDisplay, Loading, PartyId } from 'common-frontend';
import React from 'react';

import {
  Chip,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableRow,
  Typography,
} from '@mui/material';

import { AmuletConfig, USD } from '@daml.js/canton-amulet/lib/CC/AmuletConfig';
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
    return <p>Error: {JSON.stringify(svcInfosQuery.error)}</p>;
  }

  if (!svcInfosQuery.data) {
    return <p>no VoteRequest contractId is specified</p>;
  }

  const actionType = action.tag;

  if (action.tag === 'ARC_SvcRules') {
    const svcAction = action.value.svcAction;
    switch (svcAction.tag) {
      case 'SRARC_OffboardMember': {
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
  } else if (action.tag === 'ARC_AmuletRules') {
    const amuletRulesAction = action.value.amuletRulesAction;
    switch (amuletRulesAction.tag) {
      case 'CRARC_AddFutureAmuletConfigSchedule': {
        return (
          <ActionValueTable
            actionType={actionType}
            actionName={amuletRulesAction.tag}
            valuesMap={{
              'Effective Time': (
                <DateDisplay format="PPp O" datetime={amuletRulesAction.value.newScheduleItem._1} />
              ),
              NewScheduleItem: (
                <PrettyJsonPrint data={amuletRulesAction.value.newScheduleItem._2} />
              ),
            }}
          />
        );
      }
      case 'CRARC_RemoveFutureAmuletConfigSchedule': {
        return (
          <ActionValueTable
            actionType={actionType}
            actionName={amuletRulesAction.tag}
            valuesMap={{
              Time: <DateDisplay format="PPp O" datetime={amuletRulesAction.value.scheduleTime} />,
              ScheduleItem: (
                <PrettyJsonPrint
                  data={
                    svcInfosQuery.data?.amuletRules.payload.configSchedule.futureValues.find(
                      e => e._1 === amuletRulesAction.value.scheduleTime
                    )?._2
                  }
                />
              ),
            }}
          />
        );
      }
      case 'CRARC_UpdateFutureAmuletConfigSchedule': {
        return (
          <ActionValueTable
            actionType={actionType}
            actionName={amuletRulesAction.tag}
            valuesMap={{
              Time: (
                <DateDisplay format="PPp O" datetime={amuletRulesAction.value.scheduleItem._1} />
              ),
              ScheduleItem: <PrettyJsonPrint data={amuletRulesAction.value.scheduleItem._2} />,
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
}> = ({ actionType, actionName, valuesMap }) => {
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
    </>
  );
};

const PrettyJsonPrint: React.FC<{
  data?: SvcRulesConfig | AmuletConfig<USD> | string;
}> = ({ data }) => {
  return (
    <pre
      id="pretty-json"
      style={{ whiteSpace: 'pre-wrap', overflow: 'hidden', textOverflow: 'ellipsis' }}
    >
      {typeof data !== 'string' ? JSON.stringify(data, null, 2) : data}
    </pre>
  );
};

export default ActionView;
