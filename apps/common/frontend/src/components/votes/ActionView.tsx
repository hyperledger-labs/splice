// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { DateDisplay, Loading, PartyId, useVotesHooks } from 'common-frontend';
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

import { AmuletConfig, USD } from '@daml.js/splice-amulet/lib/Splice/AmuletConfig';
import {
  ActionRequiringConfirmation,
  DsoRulesConfig,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';

const ActionView: React.FC<{ action: ActionRequiringConfirmation }> = ({ action }) => {
  const votesHooks = useVotesHooks();
  const dsoInfosQuery = votesHooks.useDsoInfos();

  if (dsoInfosQuery.isLoading) {
    return <Loading />;
  }

  if (dsoInfosQuery.isError) {
    return <p>Error: {JSON.stringify(dsoInfosQuery.error)}</p>;
  }

  if (!dsoInfosQuery.data) {
    return <p>no VoteRequest contractId is specified</p>;
  }

  const actionType = action.tag;

  if (action.tag === 'ARC_DsoRules') {
    const dsoAction = action.value.dsoAction;
    switch (dsoAction.tag) {
      case 'SRARC_OffboardSv': {
        return (
          <ActionValueTable
            actionType={actionType}
            actionName={dsoAction.tag}
            valuesMap={{
              Member: <PartyId id="srarc_offboardsv-member" partyId={dsoAction.value.sv} />,
            }}
          />
        );
      }
      case 'SRARC_GrantFeaturedAppRight': {
        return (
          <ActionValueTable
            actionType={actionType}
            actionName={dsoAction.tag}
            valuesMap={{
              Provider: <PartyId partyId={dsoAction.value.provider} />,
            }}
          />
        );
      }
      case 'SRARC_RevokeFeaturedAppRight': {
        return (
          <ActionValueTable
            actionType={actionType}
            actionName={dsoAction.tag}
            valuesMap={{
              FeatureAppRightCid: <PartyId partyId={dsoAction.value.rightCid} />,
            }}
          />
        );
      }
      case 'SRARC_SetConfig': {
        return (
          <ActionValueTable
            actionType={actionType}
            actionName={dsoAction.tag}
            valuesMap={{
              NewConfig: <PrettyJsonPrint data={dsoAction.value.newConfig} />,
            }}
          />
        );
      }
      case 'SRARC_UpdateSvRewardWeight': {
        return (
          <ActionValueTable
            actionType={actionType}
            actionName={dsoAction.tag}
            valuesMap={{
              Member: (
                <PartyId id="srarc_updatesvrewardweight-member" partyId={dsoAction.value.svParty} />
              ),
              NewWeight: (
                <Typography id="srarc_updatesvrewardweight-weight">
                  {dsoAction.value.newRewardWeight}
                </Typography>
              ),
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
                    dsoInfosQuery.data?.amuletRules.payload.configSchedule.futureValues.find(
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
                  <TableCell id={`${key}-value`}>
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
  data?: DsoRulesConfig | AmuletConfig<USD> | string;
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
