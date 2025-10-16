// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  BaseVotesHooks,
  findLatestVoteResult,
  getAmuletConfigurationAsOfNow,
  getDsoConfigToCompareWith,
  filterInflightVoteRequests,
  Loading,
  PartyId,
  useVotesHooks,
  VotesHooks,
} from '@lfdecentralizedtrust/splice-common-frontend';
import { UseQueryResult } from '@tanstack/react-query';
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
  AmuletRules_SetConfig,
} from '@daml.js/splice-amulet/lib/Splice/AmuletRules';
import { Schedule } from '@daml.js/splice-amulet/lib/Splice/Schedule';
import {
  ActionRequiringConfirmation,
  DsoRules_SetConfig,
  DsoRulesConfig,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';
import { Time } from '@daml/types';

import { getAmuletConfigToCompareWith } from '../../utils';
import AccordionList, { AccordionListProps } from '../AccordionList';
import { ConfirmationDialog, ConfirmationDialogProps } from '../ConfirmationDialog';
import DateWithDurationDisplay from '../DateWithDurationDisplay';
import { DsoInfo } from '../Dso';
import { PrettyJsonDiff } from '../PrettyJsonDiff';
import { VoteRequestResultTableType } from './VoteResultsFilterTable';

import ARC_DsoRules = ActionRequiringConfirmation.ARC_DsoRules;
import ARC_AmuletRules = ActionRequiringConfirmation.ARC_AmuletRules;

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
  voteRequestResultTableType?: VoteRequestResultTableType
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
    voteRequestResultTableType
  )?.request.action;
  const latestUpdateAction = findLatestVoteResult(
    scheduleTime,
    'CRARC_UpdateFutureAmuletConfigSchedule',
    votesHooks,
    voteRequestResultTableType
  )?.request.action;

  const currentAmuletConfig = Schedule(Time, AmuletConfig(USD)).encode(
    getAmuletConfigurationAsOfNow(schedule)
  ) as Schedule<string, AmuletConfig<USD>>;

  const isExecutedOrRejected =
    voteRequestResultTableType === 'Executed' || voteRequestResultTableType === 'Rejected';

  if (isExecutedOrRejected) {
    if (!latestAddAction) {
      if (!latestUpdateAction) {
        //TODO(#934): Store a copy of the initial DsoRules and AmuletRules to diff against initial configs
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
  voteRequestResultTableType?: VoteRequestResultTableType;
  expiresAt?: Date;
  effectiveAt?: Date;
  confirmationDialogProps?: ConfirmationDialogProps;
}> = ({ action, voteRequestResultTableType, expiresAt, effectiveAt, confirmationDialogProps }) => {
  const votesHooks = useVotesHooks();
  const dsoInfosQuery = votesHooks.useDsoInfos();

  if (!action) {
    return <p>No action specified</p>;
  }

  if (dsoInfosQuery.isPending) {
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
          <>
            <ActionValueTable
              actionType={actionType}
              actionName={dsoAction.tag}
              valuesMap={{
                Member: <PartyId id="srarc_offboardsv-member" partyId={dsoAction.value.sv} />,
              }}
            />
            {getConfirmationDialog(confirmationDialogProps, expiresAt)}
          </>
        );
      }
      case 'SRARC_GrantFeaturedAppRight': {
        return (
          <>
            <ActionValueTable
              actionType={actionType}
              actionName={dsoAction.tag}
              valuesMap={{
                Provider: <PartyId partyId={dsoAction.value.provider} />,
              }}
            />
            {getConfirmationDialog(confirmationDialogProps, expiresAt)}
          </>
        );
      }
      case 'SRARC_RevokeFeaturedAppRight': {
        return (
          <>
            <ActionValueTable
              actionType={actionType}
              actionName={dsoAction.tag}
              valuesMap={{
                FeatureAppRightCid: <PartyId partyId={dsoAction.value.rightCid} />,
              }}
            />
            {getConfirmationDialog(confirmationDialogProps, expiresAt)}
          </>
        );
      }
      case 'SRARC_SetConfig': {
        return (
          <>
            <SetDsoConfigValueTable
              votesHooks={votesHooks}
              dsoInfosQuery={dsoInfosQuery}
              actionType={actionType}
              dsoAction={dsoAction}
              expiresAt={expiresAt}
              effectiveAt={effectiveAt}
              voteRequestResultTableType={voteRequestResultTableType}
            />
            {getConfirmationDialog(confirmationDialogProps, expiresAt)}
          </>
        );
      }
      // TODO(#712): implement diffs for UpdateSvRewardWeight
      case 'SRARC_UpdateSvRewardWeight': {
        return (
          <>
            <ActionValueTable
              actionType={actionType}
              actionName={dsoAction.tag}
              valuesMap={{
                Member: (
                  <PartyId
                    id="srarc_updatesvrewardweight-member"
                    partyId={dsoAction.value.svParty}
                  />
                ),
                NewWeight: (
                  <Typography id="srarc_updatesvrewardweight-weight">
                    {dsoAction.value.newRewardWeight}
                  </Typography>
                ),
              }}
            />
            {getConfirmationDialog(confirmationDialogProps, expiresAt)}
          </>
        );
      }
      case 'SRARC_CreateUnallocatedUnclaimedActivityRecord': {
        return (
          <>
            <ActionValueTable
              actionType={actionType}
              actionName={dsoAction.tag}
              valuesMap={{
                Beneficiary: <PartyId partyId={dsoAction.value.beneficiary} />,
                Amount: <Typography>{dsoAction.value.amount}</Typography>,
                'Must Mint Before': (
                  <DateWithDurationDisplay datetime={dsoAction.value.expiresAt} />
                ),
              }}
            />
            {getConfirmationDialog(confirmationDialogProps, expiresAt)}
          </>
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
            expiresAt={expiresAt}
            voteRequestResultTableType={voteRequestResultTableType}
            confirmationDialogProps={confirmationDialogProps!}
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
            expiresAt={expiresAt}
            voteRequestResultTableType={voteRequestResultTableType}
            confirmationDialogProps={confirmationDialogProps!}
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
            expiresAt={expiresAt}
            voteRequestResultTableType={voteRequestResultTableType}
            confirmationDialogProps={confirmationDialogProps!}
          />
        );
      }
      case 'CRARC_SetConfig': {
        return (
          <SetAmuletConfigValueTable
            votesHooks={votesHooks}
            dsoInfosQuery={dsoInfosQuery}
            actionType={actionType}
            amuletAction={amuletRulesAction}
            expiresAt={expiresAt}
            effectiveAt={effectiveAt}
            voteRequestResultTableType={voteRequestResultTableType}
            confirmationDialogProps={confirmationDialogProps!}
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
                    {typeof valuesMap[key] == 'boolean' ? valuesMap[key] : valuesMap[key]}
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

// Deprecated from dsoGovernance 0.1.15 (needed for past actions)
const AddFutureConfigValueTable: React.FC<{
  votesHooks: BaseVotesHooks;
  dsoInfosQuery: UseQueryResult<DsoInfo>;
  actionType: string;
  amuletRulesAction: {
    tag: 'CRARC_AddFutureAmuletConfigSchedule';
    value: AmuletRules_AddFutureAmuletConfigSchedule;
  };
  expiresAt?: Date;
  voteRequestResultTableType?: VoteRequestResultTableType;
  confirmationDialogProps?: ConfirmationDialogProps;
}> = ({
  votesHooks,
  dsoInfosQuery,
  actionType,
  amuletRulesAction,
  expiresAt,
  voteRequestResultTableType,
  confirmationDialogProps,
}) => {
  const voteRequests = votesHooks.useListDsoRulesVoteRequests();

  const amuletConfigToCompareWith = dsoInfosQuery.data
    ? findAmuletRulesScheduleItemToCompareAgainst(
        dsoInfosQuery.data?.amuletRules.payload.configSchedule,
        amuletRulesAction.value.newScheduleItem._1,
        votesHooks,
        amuletRulesAction.value.newScheduleItem._2,
        voteRequestResultTableType
      )
    : undefined;

  if (voteRequests.isPending) {
    return <Loading />;
  }

  if (voteRequests.isError) {
    return <p>Error, something went wrong.</p>;
  }

  if (!voteRequests.data) {
    return <p>no VoteRequest contractId is specified</p>;
  }

  const inflightVoteRequests: [string, AmuletConfig<USD>][] = !voteRequestResultTableType
    ? filterInflightVoteRequests(
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

  const unfoldedAccordions = amuletConfigToCompareWith
    ? [
        {
          title: <DateWithDurationDisplay datetime={amuletConfigToCompareWith[0]} />,
          content: (
            <PrettyJsonDiff
              changes={{
                newConfig: amuletRulesAction.value.newScheduleItem._2,
                actualConfig: amuletConfigToCompareWith[1],
              }}
            />
          ),
        },
      ]
    : [];

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const foldedAccordions = inflightVoteRequests.map(vr => ({
    title: <DateWithDurationDisplay datetime={vr[0]} />,
    content: (
      <PrettyJsonDiff
        changes={{ newConfig: amuletRulesAction.value.newScheduleItem._2, actualConfig: vr[1] }}
      />
    ),
  }));

  const confirmationDialogPropsWithDiffs = confirmationDialogProps
    ? {
        ...confirmationDialogProps,
        // TODO(#904): Fix inflight requests diffs or completely remove them
        children: <AccordionList unfoldedAccordions={unfoldedAccordions} foldedAccordions={[]} />,
      }
    : undefined;

  return (
    <>
      <ActionValueTable
        actionType={actionType}
        actionName={amuletRulesAction.tag}
        valuesMap={{
          'Effective Time': (
            <DateWithDurationDisplay
              datetime={amuletRulesAction.value.newScheduleItem._1}
              enableDuration
            />
          ),
        }}
        accordionList={{
          unfoldedAccordions: unfoldedAccordions,
          // TODO(#904): Fix inflight requests diffs or completely remove them
          foldedAccordions: [],
        }}
      />
      {confirmationDialogPropsWithDiffs &&
        getConfirmationDialog(confirmationDialogPropsWithDiffs, expiresAt)}
    </>
  );
};

// Deprecated from dsoGovernance 0.1.15 (needed for past actions)
const RemoveFutureConfigValueTable: React.FC<{
  votesHooks: BaseVotesHooks;
  dsoInfosQuery: UseQueryResult<DsoInfo>;
  actionType: string;
  amuletRulesAction: {
    tag: 'CRARC_RemoveFutureAmuletConfigSchedule';
    value: AmuletRules_RemoveFutureAmuletConfigSchedule;
  };
  expiresAt?: Date;
  voteRequestResultTableType?: VoteRequestResultTableType;
  confirmationDialogProps?: ConfirmationDialogProps;
}> = ({
  votesHooks,
  dsoInfosQuery,
  actionType,
  amuletRulesAction,
  expiresAt,
  voteRequestResultTableType,
  confirmationDialogProps,
}) => {
  const voteRequests = votesHooks.useListDsoRulesVoteRequests();

  const amuletConfigToCompareWith = dsoInfosQuery.data
    ? findAmuletRulesScheduleItemToCompareAgainst(
        dsoInfosQuery.data?.amuletRules.payload.configSchedule,
        amuletRulesAction.value.scheduleTime,
        votesHooks,
        dsoInfosQuery.data?.amuletRules.payload.configSchedule.initialValue,
        voteRequestResultTableType
      )
    : undefined;

  if (voteRequests.isPending) {
    return <Loading />;
  }

  if (voteRequests.isError) {
    return <p>Error, something went wrong.</p>;
  }

  if (!voteRequests.data) {
    return <p>no VoteRequest contractId is specified</p>;
  }

  // TODO(DACH-NY/canton-network-node#15154): Implement config diffs of CRARC_RemoveFutureAmuletConfigSchedule action
  return (
    <>
      {amuletConfigToCompareWith && (
        <ActionValueTable
          actionType={actionType}
          actionName={amuletRulesAction.tag}
          valuesMap={{
            Time: <DateWithDurationDisplay datetime={amuletRulesAction.value.scheduleTime} />,
            'Comparing against config from': (
              <DateWithDurationDisplay datetime={amuletConfigToCompareWith[0]} />
            ),
            ScheduleItem: (
              <PrettyJsonDiff
                changes={{
                  newConfig: amuletConfigToCompareWith[1],
                  actualConfig: amuletConfigToCompareWith[1],
                }}
              />
            ),
          }}
        />
      )}
      {getConfirmationDialog(confirmationDialogProps, expiresAt)}
    </>
  );
};

// Deprecated from dsoGovernance 0.1.15 (needed for past actions)
const UpdateFutureConfigValueTable: React.FC<{
  votesHooks: BaseVotesHooks;
  dsoInfosQuery: UseQueryResult<DsoInfo>;
  actionType: string;
  amuletRulesAction: {
    tag: 'CRARC_UpdateFutureAmuletConfigSchedule';
    value: AmuletRules_UpdateFutureAmuletConfigSchedule;
  };
  expiresAt?: Date;
  voteRequestResultTableType?: VoteRequestResultTableType;
  confirmationDialogProps?: ConfirmationDialogProps;
}> = ({
  votesHooks,
  dsoInfosQuery,
  actionType,
  amuletRulesAction,
  expiresAt,
  voteRequestResultTableType,
  confirmationDialogProps,
}) => {
  const voteRequests = votesHooks.useListDsoRulesVoteRequests();

  const amuletConfigToCompareWith = dsoInfosQuery.data
    ? findAmuletRulesScheduleItemToCompareAgainst(
        dsoInfosQuery.data?.amuletRules.payload.configSchedule,
        amuletRulesAction.value.scheduleItem._1,
        votesHooks,
        amuletRulesAction.value.scheduleItem._2,
        voteRequestResultTableType
      )
    : undefined;

  if (voteRequests.isPending) {
    return <Loading />;
  }

  if (voteRequests.isError) {
    return <p>Error, something went wrong.</p>;
  }

  if (!voteRequests.data) {
    return <p>no VoteRequest contractId is specified</p>;
  }

  const inflightVoteRequests: [string, AmuletConfig<USD>][] = !voteRequestResultTableType
    ? filterInflightVoteRequests(
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

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const foldedAccordions = inflightVoteRequests.map(vr => ({
    title: <DateWithDurationDisplay datetime={vr[0]} />,
    content: (
      <PrettyJsonDiff
        changes={{
          newConfig: amuletRulesAction.value.scheduleItem._2,
          actualConfig: vr[1],
        }}
      />
    ),
  }));

  return (
    <>
      <ActionValueTable
        actionType={actionType}
        actionName={amuletRulesAction.tag}
        valuesMap={{
          'Effective Time': (
            <DateWithDurationDisplay
              datetime={amuletRulesAction.value.scheduleItem._1}
              enableDuration
            />
          ),
        }}
        accordionList={{
          unfoldedAccordions: amuletConfigToCompareWith
            ? [
                {
                  title: <DateWithDurationDisplay datetime={amuletConfigToCompareWith[0]} />,
                  content: (
                    <PrettyJsonDiff
                      changes={{
                        newConfig: amuletRulesAction.value.scheduleItem._2,
                        actualConfig: amuletConfigToCompareWith[1],
                      }}
                    />
                  ),
                },
              ]
            : [],
          // TODO(#904): Fix inflight requests diffs or completely remove them
          foldedAccordions: [],
        }}
      />
      {getConfirmationDialog(confirmationDialogProps, expiresAt)}
    </>
  );
};

const SetAmuletConfigValueTable: React.FC<{
  votesHooks: BaseVotesHooks;
  dsoInfosQuery: UseQueryResult<DsoInfo>;
  actionType: string;
  amuletAction: { tag: 'CRARC_SetConfig'; value: AmuletRules_SetConfig };
  expiresAt?: Date;
  effectiveAt?: Date;
  voteRequestResultTableType?: VoteRequestResultTableType; // voteRequestResultTableType is only defined for the Planned, Executed and Rejected tabs
  confirmationDialogProps?: ConfirmationDialogProps;
}> = ({
  votesHooks,
  dsoInfosQuery,
  actionType,
  amuletAction,
  expiresAt,
  effectiveAt,
  voteRequestResultTableType,
  confirmationDialogProps,
}) => {
  const voteRequests = votesHooks.useListDsoRulesVoteRequests();

  const dsoConfigToCompareWith = getAmuletConfigToCompareWith(
    effectiveAt,
    voteRequestResultTableType,
    votesHooks,
    amuletAction,
    dsoInfosQuery
  );

  if (voteRequests.isPending) {
    return <Loading />;
  }
  if (voteRequests.isError) {
    return <p>Error, something went wrong.</p>;
  }
  if (!voteRequests.data) {
    return <p>no VoteRequest contractId is specified</p>;
  }

  const inflightVoteRequests: [string, AmuletConfig<USD>][] = !voteRequestResultTableType
    ? filterInflightVoteRequests(
        amuletAction.tag,
        voteRequests.data.map(vr => vr.payload)
      )
        .map(vr => {
          const newConfig = (vr.action.value as ARC_AmuletRules).amuletRulesAction
            ?.value as AmuletRules_SetConfig;
          return [vr.voteBefore, AmuletConfig(USD).encode(newConfig.newConfig)] as [
            string,
            AmuletConfig<USD>,
          ];
        })
        .filter(v => !dayjs(v[0]).isSame(dayjs(expiresAt)))
    : [];

  const unfoldedAccordions =
    dsoInfosQuery.data && dsoConfigToCompareWith
      ? [
          {
            title: <DateWithDurationDisplay datetime={dsoConfigToCompareWith[0]} />,
            content: (
              <PrettyJsonDiff
                changes={{
                  newConfig: amuletAction.value.newConfig,
                  baseConfig: amuletAction.value.baseConfig,
                  actualConfig: dsoConfigToCompareWith[1],
                }}
              />
            ),
          },
        ]
      : [];

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const foldedAccordions = inflightVoteRequests.map(vr => ({
    title: <DateWithDurationDisplay datetime={vr[0]} />,
    content: (
      <PrettyJsonDiff
        changes={{
          newConfig: amuletAction.value.newConfig,
          baseConfig: amuletAction.value.baseConfig,
          actualConfig: vr[1],
        }}
      />
    ),
  }));

  const confirmationDialogPropsWithDiffs = confirmationDialogProps
    ? {
        ...confirmationDialogProps,
        // TODO(#904): Fix inflight requests diffs or completely remove them
        children: <AccordionList unfoldedAccordions={unfoldedAccordions} foldedAccordions={[]} />,
      }
    : undefined;

  return (
    <>
      <ActionValueTable
        actionType={actionType}
        actionName={amuletAction.tag}
        accordionList={{
          unfoldedAccordions: unfoldedAccordions,
          // TODO(#904): Fix inflight requests diffs or completely remove them
          foldedAccordions: [],
        }}
      />
      {confirmationDialogPropsWithDiffs &&
        getConfirmationDialog(confirmationDialogPropsWithDiffs, expiresAt)}
    </>
  );
};

const SetDsoConfigValueTable: React.FC<{
  votesHooks: BaseVotesHooks;
  dsoInfosQuery: UseQueryResult<DsoInfo>;
  actionType: string;
  dsoAction: { tag: 'SRARC_SetConfig'; value: DsoRules_SetConfig };
  expiresAt?: Date;
  effectiveAt?: Date;
  voteRequestResultTableType?: VoteRequestResultTableType; // voteRequestResultTableType is only defined for the Planned, Executed and Rejected tabs
  confirmationDialogProps?: ConfirmationDialogProps;
}> = ({
  votesHooks,
  dsoInfosQuery,
  actionType,
  dsoAction,
  expiresAt,
  effectiveAt,
  voteRequestResultTableType,
  confirmationDialogProps,
}) => {
  const voteRequests = votesHooks.useListDsoRulesVoteRequests();

  const dsoConfigToCompareWith = getDsoConfigToCompareWith(
    effectiveAt,
    voteRequestResultTableType,
    votesHooks,
    dsoAction,
    dsoInfosQuery
  );

  if (voteRequests.isPending) {
    return <Loading />;
  }
  if (voteRequests.isError) {
    return <p>Error, something went wrong.</p>;
  }
  if (!voteRequests.data) {
    return <p>no VoteRequest contractId is specified</p>;
  }

  const inflightVoteRequests: [string, DsoRulesConfig][] = !voteRequestResultTableType
    ? filterInflightVoteRequests(
        dsoAction.tag,
        voteRequests.data.map(vr => vr.payload)
      )
        .map(vr => {
          const newConfig = (vr.action.value as ARC_DsoRules).dsoAction
            ?.value as DsoRules_SetConfig;
          return [vr.voteBefore, DsoRulesConfig.encode(newConfig.newConfig)] as [
            string,
            DsoRulesConfig,
          ];
        })
        .filter(v => !dayjs(v[0]).isSame(dayjs(expiresAt)))
    : [];

  const unfoldedAccordions = dsoConfigToCompareWith[1]
    ? [
        {
          title: <DateWithDurationDisplay datetime={dsoConfigToCompareWith[0]} />,
          content: (
            <PrettyJsonDiff
              changes={{
                newConfig: dsoAction.value.newConfig,
                baseConfig: dsoAction.value.baseConfig || dsoConfigToCompareWith[1],
                actualConfig: dsoConfigToCompareWith[1],
              }}
            />
          ),
        },
      ]
    : [];

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const foldedAccordions = inflightVoteRequests.map(vr => ({
    title: <DateWithDurationDisplay datetime={vr[0]} />,
    content: (
      <PrettyJsonDiff
        changes={{
          newConfig: dsoAction.value.newConfig,
          baseConfig: dsoAction.value.baseConfig || undefined,
          actualConfig: vr[1],
        }}
      />
    ),
  }));

  const confirmationDialogPropsWithDiffs = confirmationDialogProps
    ? {
        ...confirmationDialogProps,
        // TODO(#904): Fix inflight requests diffs or completely remove them
        children: <AccordionList unfoldedAccordions={unfoldedAccordions} foldedAccordions={[]} />,
      }
    : undefined;

  return (
    <>
      <ActionValueTable
        actionType={actionType}
        actionName={dsoAction.tag}
        accordionList={{
          unfoldedAccordions: unfoldedAccordions,
          // TODO(#904): Fix inflight requests diffs or completely remove them
          foldedAccordions: [],
        }}
      />
      {confirmationDialogPropsWithDiffs &&
        getConfirmationDialog(confirmationDialogPropsWithDiffs, expiresAt)}
    </>
  );
};

const getConfirmationDialog = (
  confirmationDialogProps?: ConfirmationDialogProps,
  expiresAt?: Date
) => {
  if (!confirmationDialogProps) {
    return <></>;
  }

  const expireDuration = dayjs(expiresAt).fromNow();

  return (
    <ConfirmationDialog
      showDialog={confirmationDialogProps.showDialog}
      onAccept={confirmationDialogProps.onAccept}
      onClose={confirmationDialogProps.onClose}
      title="Confirm Your Vote Request"
      attributePrefix="vote"
      disableProceed={confirmationDialogProps.disableProceed}
    >
      <Typography variant="h6">Are you sure you want to create this vote request?</Typography>
      <br />
      Please note:
      <ul>
        <li>This action cannot be undone.</li>
        <li>You will not be able to edit this request afterwards.</li>
        <li>You may only edit your vote after creation.</li>
        <li>The vote request will expire {expireDuration}.</li>
      </ul>
      {confirmationDialogProps.children}
    </ConfirmationDialog>
  );
};

export default ActionView;
