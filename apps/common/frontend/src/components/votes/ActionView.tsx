// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { QueryObserverSuccessResult } from '@tanstack/react-query';
import {
  BaseVotesHooks,
  DateDisplay,
  getAmuletConfigurationAsOfNow,
  Loading,
  PartyId,
  useVotesHooks,
  VotesHooks,
} from 'common-frontend';
import dayjs from 'dayjs';
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
  AmuletRules_AddFutureAmuletConfigSchedule,
  AmuletRules_UpdateFutureAmuletConfigSchedule,
  AmuletRules_RemoveFutureAmuletConfigSchedule,
} from '@daml.js/splice-amulet/lib/Splice/AmuletRules';
import { Schedule } from '@daml.js/splice-amulet/lib/Splice/Schedule';
import {
  ActionRequiringConfirmation,
  DsoRules_CloseVoteRequestResult,
  DsoRules_SetConfig,
  DsoRulesConfig,
  VoteRequest,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';
import { Time } from '@daml/types';

import AccordionList, { AccordionListProps } from '../AccordionList';
import { DsoInfo } from '../Dso';
import { PrettyJsonDiff } from '../PrettyJsonDiff';
import { getAction } from './ListVoteRequests';
import { VoteRequestResultTableType } from './VoteResultsFilterTable';

import ARC_DsoRules = ActionRequiringConfirmation.ARC_DsoRules;
import ARC_AmuletRules = ActionRequiringConfirmation.ARC_AmuletRules;

/*
 * This function finds the latest vote result for a given action name and time.
 * It is used to compare the current vote result with the one that was before (current -1).
 */
function findLatestVoteResult(
  time: string,
  actionName: string,
  votesHooks: VotesHooks,
  tableType?: VoteRequestResultTableType
): DsoRules_CloseVoteRequestResult | undefined {
  const voteResultsQuery = votesHooks.useListVoteRequestResult(
    1,
    actionName,
    undefined,
    undefined,
    time,
    tableType !== 'Rejected'
  );
  if (!voteResultsQuery.data || !voteResultsQuery.data[0]) {
    return undefined;
  } else {
    return DsoRules_CloseVoteRequestResult.encode(
      voteResultsQuery.data[0]
    ) as DsoRules_CloseVoteRequestResult;
  }
}

/*
 * This function filters out the in-flight vote requests of a given action name.
 */
function getInflightVoteRequests(
  actionName: string,
  voteRequests: VoteRequest[] | undefined
): VoteRequest[] | [] {
  if (!voteRequests) {
    return [];
  }
  return voteRequests.filter(voteRequest => {
    const tag = getAction(voteRequest.action);
    return tag === actionName;
  });
}

/*
 * This function finds the AmuletRules schedule item that it will replace if voted directly.
 * It is used for CRARC_AddFutureAmuletConfigSchedule and CRARC_UpdateFutureAmuletConfigSchedule actions.
 * It compares the current schedule with:
 * - the current AmuletRules contract to diff against the schedule it will replace (nth -1) [always]
 * - the latest vote result to diff against [when in Planned, Executed or Rejected tabs]
 * - the in-flight vote requests to diff against [when in Action Needed or In Progress tabs]
 */
function findAmuletRulesScheduleItemToCompareAgainst(
  schedule: Schedule<string, AmuletConfig<USD>>,
  scheduleTime: string,
  votesHooks: VotesHooks,
  defaultConfig: AmuletConfig<USD>,
  tableType?: VoteRequestResultTableType
): [string, AmuletConfig<USD>] {
  function parseAmuletRulesAction(
    action: ActionRequiringConfirmation
  ): [string, AmuletConfig<USD>] {
    if (action.tag === 'ARC_AmuletRules') {
      const amuletRulesAction = action.value.amuletRulesAction;
      switch (amuletRulesAction.tag) {
        case 'CRARC_AddFutureAmuletConfigSchedule':
          return [
            amuletRulesAction.value.newScheduleItem._1,
            amuletRulesAction.value.newScheduleItem._2,
          ];
        case 'CRARC_UpdateFutureAmuletConfigSchedule':
          return [amuletRulesAction.value.scheduleItem._1, amuletRulesAction.value.scheduleItem._2];
      }
    }
    return ['initial', defaultConfig];
  }
  const latestAddAction = findLatestVoteResult(
    scheduleTime,
    'CRARC_AddFutureAmuletConfigSchedule',
    votesHooks,
    tableType
  )?.request.action;
  const latestUpdateAction = findLatestVoteResult(
    scheduleTime,
    'CRARC_UpdateFutureAmuletConfigSchedule',
    votesHooks,
    tableType
  )?.request.action;

  const currentAmuletConfig = Schedule(Time, AmuletConfig(USD)).encode(
    getAmuletConfigurationAsOfNow(schedule)
  ) as Schedule<string, AmuletConfig<USD>>;

  const isExecutedOrRejected = tableType === 'Executed' || tableType === 'Rejected';

  if (isExecutedOrRejected) {
    if (!latestAddAction) {
      if (!latestUpdateAction) {
        //TODO(#14813): Store a copy of the initial DsoRules and AmuletRules to diff against initial configs
        return ['initial', defaultConfig];
      } else {
        return parseAmuletRulesAction(latestUpdateAction);
      }
    } else {
      if (!latestUpdateAction) {
        return parseAmuletRulesAction(latestAddAction);
      } else {
        const latestAdd = parseAmuletRulesAction(latestAddAction);
        const latestUpdate = parseAmuletRulesAction(latestUpdateAction);
        if (dayjs(latestAdd[0]).isAfter(dayjs(latestUpdate[0]))) {
          return latestAdd;
        } else {
          return latestUpdate;
        }
      }
    }
  }
  // for planned and executed sections, the values are already part of the configSchedule
  // for the action needed and in progress sections, the values are not part of the configSchedule
  if (currentAmuletConfig.futureValues.length <= 1) {
    return ['initial', currentAmuletConfig.initialValue];
  }

  const scheduleTimeDayjs = dayjs(scheduleTime);

  let currentConfigIndex = currentAmuletConfig.futureValues.findIndex(e =>
    dayjs(e._1).isSame(scheduleTimeDayjs)
  );

  if (currentConfigIndex === -1) {
    currentConfigIndex = currentAmuletConfig.futureValues.findIndex(
      e => !dayjs(e._1).isBefore(scheduleTimeDayjs)
    );
  }

  if (currentConfigIndex === -1) {
    const config = currentAmuletConfig.futureValues[currentAmuletConfig.futureValues.length - 1];
    return [dayjs(config._1).toString(), config._2];
  }

  if (currentConfigIndex === 0) {
    return ['initial', currentAmuletConfig.initialValue];
  }
  const config = currentAmuletConfig.futureValues[currentConfigIndex - 1];
  return [dayjs(config._1).toString(), config._2];
}

export const ActionView: React.FC<{
  action: ActionRequiringConfirmation;
  tableType?: VoteRequestResultTableType;
  effectiveAt?: string;
}> = ({ action, tableType, effectiveAt }) => {
  const votesHooks = useVotesHooks();
  const dsoInfosQuery = votesHooks.useDsoInfos();

  if (!action) {
    return <p>No action specified</p>;
  }

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
          <SetConfigValueTable
            votesHooks={votesHooks}
            dsoInfosQuery={dsoInfosQuery}
            actionType={actionType}
            dsoAction={dsoAction}
            effectiveAt={effectiveAt}
            tableType={tableType}
          />
        );
      }
      // TODO(#15151): implement diffs for UpdateSvRewardWeight
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
          <AddFutureConfigValueTable
            votesHooks={votesHooks}
            dsoInfosQuery={dsoInfosQuery}
            actionType={actionType}
            amuletRulesAction={amuletRulesAction}
            tableType={tableType}
          />
        );
      }
      case 'CRARC_RemoveFutureAmuletConfigSchedule': {
        return (
          <RemoveFutureConfigValueTable
            votesHooks={votesHooks}
            dsoInfosQuery={dsoInfosQuery}
            actionType={actionType}
            amuletRulesAction={amuletRulesAction}
            tableType={tableType}
          />
        );
      }
      case 'CRARC_UpdateFutureAmuletConfigSchedule': {
        return (
          <UpdateFutureConfigValueTable
            votesHooks={votesHooks}
            dsoInfosQuery={dsoInfosQuery}
            actionType={actionType}
            amuletRulesAction={amuletRulesAction}
            tableType={tableType}
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
  accordionList?: AccordionListProps;
}> = ({ actionType, actionName, valuesMap, accordionList }) => {
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
      {accordionList && (
        <AccordionList
          unfoldedAccordions={accordionList.unfoldedAccordions}
          foldedAccordions={accordionList.foldedAccordions}
        />
      )}
    </>
  );
};

const AddFutureConfigValueTable: React.FC<{
  votesHooks: BaseVotesHooks;
  dsoInfosQuery: QueryObserverSuccessResult<DsoInfo>;
  actionType: string;
  amuletRulesAction: {
    tag: 'CRARC_AddFutureAmuletConfigSchedule';
    value: AmuletRules_AddFutureAmuletConfigSchedule;
  };
  tableType?: VoteRequestResultTableType;
}> = ({ votesHooks, dsoInfosQuery, actionType, amuletRulesAction, tableType }) => {
  const voteRequests = votesHooks.useListDsoRulesVoteRequests();

  if (voteRequests.isLoading) {
    return <Loading />;
  }

  if (voteRequests.isError) {
    return <p>Error, something went wrong.</p>;
  }

  if (!voteRequests.data) {
    return <p>no VoteRequest contractId is specified</p>;
  }

  const amuletConfigToCompareWith = findAmuletRulesScheduleItemToCompareAgainst(
    dsoInfosQuery.data?.amuletRules.payload.configSchedule,
    amuletRulesAction.value.newScheduleItem._1,
    votesHooks,
    amuletRulesAction.value.newScheduleItem._2,
    tableType
  );

  const inflightVoteRequests: [string, AmuletConfig<USD>][] = !tableType
    ? getInflightVoteRequests(
        amuletRulesAction.tag,
        voteRequests.data.map(vr => vr.payload)
      )
        .map(vr => {
          const newConfig = (vr.action.value as ARC_AmuletRules).amuletRulesAction
            ?.value as AmuletRules_AddFutureAmuletConfigSchedule;
          return [
            newConfig.newScheduleItem._1,
            AmuletConfig(USD).encode(newConfig.newScheduleItem._2),
          ] as [string, AmuletConfig<USD>];
        })
        .filter(v => v[0] !== amuletRulesAction.value.newScheduleItem._1)
    : [];

  return (
    <ActionValueTable
      actionType={actionType}
      actionName={amuletRulesAction.tag}
      valuesMap={{
        'Effective Time': (
          <DateOrTextDisplay datetime={amuletRulesAction.value.newScheduleItem._1} />
        ),
      }}
      accordionList={{
        unfoldedAccordions: [
          {
            title: <DateOrTextDisplay datetime={amuletConfigToCompareWith[0]} />,
            content: (
              <PrettyJsonDiff
                data={amuletRulesAction.value.newScheduleItem._2}
                compareWithData={amuletConfigToCompareWith[1]}
              />
            ),
          },
        ],
        foldedAccordions: inflightVoteRequests.map(vr => ({
          title: <DateOrTextDisplay datetime={vr[0]} />,
          content: (
            <PrettyJsonDiff
              data={amuletRulesAction.value.newScheduleItem._2}
              compareWithData={vr[1]}
            />
          ),
        })),
      }}
    />
  );
};

const RemoveFutureConfigValueTable: React.FC<{
  votesHooks: BaseVotesHooks;
  dsoInfosQuery: QueryObserverSuccessResult<DsoInfo>;
  actionType: string;
  amuletRulesAction: {
    tag: 'CRARC_RemoveFutureAmuletConfigSchedule';
    value: AmuletRules_RemoveFutureAmuletConfigSchedule;
  };
  tableType?: VoteRequestResultTableType;
}> = ({ votesHooks, dsoInfosQuery, actionType, amuletRulesAction, tableType }) => {
  const voteRequests = votesHooks.useListDsoRulesVoteRequests();

  if (voteRequests.isLoading) {
    return <Loading />;
  }

  if (voteRequests.isError) {
    return <p>Error, something went wrong.</p>;
  }

  if (!voteRequests.data) {
    return <p>no VoteRequest contractId is specified</p>;
  }
  const amuletConfigToCompareWith = findAmuletRulesScheduleItemToCompareAgainst(
    dsoInfosQuery.data?.amuletRules.payload.configSchedule,
    amuletRulesAction.value.scheduleTime,
    votesHooks,
    dsoInfosQuery.data?.amuletRules.payload.configSchedule.initialValue,
    tableType
  );
  // TODO(#15154): Implement config diffs of CRARC_RemoveFutureAmuletConfigSchedule action
  return (
    <ActionValueTable
      actionType={actionType}
      actionName={amuletRulesAction.tag}
      valuesMap={{
        Time: <DateOrTextDisplay datetime={amuletRulesAction.value.scheduleTime} />,
        'Comparing against config from': (
          <DateOrTextDisplay datetime={amuletConfigToCompareWith[0]} />
        ),
        ScheduleItem: (
          <PrettyJsonDiff
            data={amuletConfigToCompareWith[1]}
            compareWithData={amuletConfigToCompareWith[1]}
          />
        ),
      }}
    />
  );
};

const UpdateFutureConfigValueTable: React.FC<{
  votesHooks: BaseVotesHooks;
  dsoInfosQuery: QueryObserverSuccessResult<DsoInfo>;
  actionType: string;
  amuletRulesAction: {
    tag: 'CRARC_UpdateFutureAmuletConfigSchedule';
    value: AmuletRules_UpdateFutureAmuletConfigSchedule;
  };
  tableType?: VoteRequestResultTableType;
}> = ({ votesHooks, dsoInfosQuery, actionType, amuletRulesAction, tableType }) => {
  const voteRequests = votesHooks.useListDsoRulesVoteRequests();

  if (voteRequests.isLoading) {
    return <Loading />;
  }

  if (voteRequests.isError) {
    return <p>Error, something went wrong.</p>;
  }

  if (!voteRequests.data) {
    return <p>no VoteRequest contractId is specified</p>;
  }

  const amuletConfigToCompareWith = findAmuletRulesScheduleItemToCompareAgainst(
    dsoInfosQuery.data?.amuletRules.payload.configSchedule,
    amuletRulesAction.value.scheduleItem._1,
    votesHooks,
    amuletRulesAction.value.scheduleItem._2,
    tableType
  );

  const inflightVoteRequests: [string, AmuletConfig<USD>][] = !tableType
    ? getInflightVoteRequests(
        amuletRulesAction.tag,
        voteRequests.data.map(vr => vr.payload)
      )
        .map(vr => {
          const newConfig = (vr.action.value as ARC_AmuletRules).amuletRulesAction
            ?.value as AmuletRules_UpdateFutureAmuletConfigSchedule;
          return [
            newConfig.scheduleItem._1,
            AmuletConfig(USD).encode(newConfig.scheduleItem._2),
          ] as [string, AmuletConfig<USD>];
        })
        .filter(v => v[0] !== amuletRulesAction.value.scheduleItem._1)
    : [];

  return (
    <ActionValueTable
      actionType={actionType}
      actionName={amuletRulesAction.tag}
      valuesMap={{
        'Effective Time': <DateOrTextDisplay datetime={amuletRulesAction.value.scheduleItem._1} />,
      }}
      accordionList={{
        unfoldedAccordions: [
          {
            title: <DateOrTextDisplay datetime={amuletConfigToCompareWith[0]} />,
            content: (
              <PrettyJsonDiff
                data={amuletRulesAction.value.scheduleItem._2}
                compareWithData={amuletConfigToCompareWith[1]}
              />
            ),
          },
        ],
        foldedAccordions: inflightVoteRequests.map(vr => ({
          title: <DateOrTextDisplay datetime={vr[0]} />,
          content: (
            <PrettyJsonDiff
              data={amuletRulesAction.value.scheduleItem._2}
              compareWithData={vr[1]}
            />
          ),
        })),
      }}
    />
  );
};

const DateOrTextDisplay = (props: { datetime: string | Date | undefined }) => {
  if (props.datetime && (props.datetime instanceof Date || props.datetime !== 'initial')) {
    return <DateDisplay format={'PPp O'} datetime={props.datetime} />;
  } else {
    return <>initial</>;
  }
};

const SetConfigValueTable: React.FC<{
  votesHooks: BaseVotesHooks;
  dsoInfosQuery: QueryObserverSuccessResult<DsoInfo>;
  actionType: string;
  dsoAction: { tag: 'SRARC_SetConfig'; value: DsoRules_SetConfig };
  effectiveAt?: string;
  tableType?: VoteRequestResultTableType; // tableType is only defined for the Planned, Executed and Rejected tabs
}> = ({ votesHooks, dsoInfosQuery, actionType, dsoAction, effectiveAt, tableType }) => {
  const voteRequests = votesHooks.useListDsoRulesVoteRequests();

  if (voteRequests.isLoading) {
    return <Loading />;
  }

  if (voteRequests.isError) {
    return <p>Error, something went wrong.</p>;
  }

  if (!voteRequests.data) {
    return <p>no VoteRequest contractId is specified</p>;
  }

  // TODO(#15180): Implement effectivity on all actions
  // we need to subtract 1 second because the effectiveAt differs slightly from the completedAt in DsoRules-based actions
  const latestConfig =
    effectiveAt && tableType
      ? findLatestVoteResult(
          dayjs(effectiveAt).subtract(1, 'seconds').toISOString(),
          'SRARC_SetConfig',
          votesHooks,
          tableType
        )
      : undefined;

  const dsoConfigToCompareWith: [string, DsoRulesConfig] = !latestConfig
    ? [
        'initial',
        tableType
          ? dsoAction.value.newConfig
          : (DsoRulesConfig.encode(dsoInfosQuery.data.dsoRules.payload.config) as DsoRulesConfig),
      ]
    : [
        latestConfig.request.voteBefore,
        ((latestConfig.request.action.value as ARC_DsoRules).dsoAction.value as DsoRules_SetConfig)
          .newConfig,
      ];

  const inflightVoteRequests: [string, DsoRulesConfig][] = !tableType
    ? getInflightVoteRequests(
        dsoAction.tag,
        voteRequests.data.map(vr => vr.payload)
      )
        .map(vr => {
          const newConfig = (vr.action.value as ARC_DsoRules).dsoAction
            ?.value as DsoRules_SetConfig;
          return [vr.voteBefore, DsoRulesConfig.encode(newConfig.newConfig)] as [
            string,
            DsoRulesConfig
          ];
        })
        .filter(v => !dayjs(v[0]).isSame(dayjs(effectiveAt)))
    : [];

  return (
    <ActionValueTable
      actionType={actionType}
      actionName={dsoAction.tag}
      valuesMap={{
        'Effective Time': <DateOrTextDisplay datetime={effectiveAt} />,
      }}
      accordionList={{
        unfoldedAccordions: [
          {
            title: <DateOrTextDisplay datetime={dsoConfigToCompareWith[0]} />,
            content: (
              <PrettyJsonDiff
                data={dsoAction.value.newConfig}
                compareWithData={dsoConfigToCompareWith[1]}
              />
            ),
          },
        ],
        foldedAccordions: inflightVoteRequests.map(vr => ({
          title: <DateOrTextDisplay datetime={vr[0]} />,
          content: <PrettyJsonDiff data={dsoAction.value.newConfig} compareWithData={vr[1]} />,
        })),
      }}
    />
  );
};

export default ActionView;
