// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { UseQueryResult } from '@tanstack/react-query';
import dayjs from 'dayjs';

import { AmuletConfig, USD } from '@daml.js/splice-amulet/lib/Splice/AmuletConfig';
import { AmuletRules_SetConfig } from '@daml.js/splice-amulet/lib/Splice/AmuletRules';
import {
  ActionRequiringConfirmation,
  DsoRules_CloseVoteRequestResult,
  DsoRules_SetConfig,
  DsoRulesConfig,
  VoteRequest,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';

import { BaseVotesHooks, VotesHooks } from '../components';
import { getAction } from '../components/votes/ListVoteRequests';
import { VoteRequestResultTableType } from '../components/votes/VoteResultsFilterTable';
import { DsoInfo } from '../index';

import ARC_DsoRules = ActionRequiringConfirmation.ARC_DsoRules;

import ARC_AmuletRules = ActionRequiringConfirmation.ARC_AmuletRules;

/*
 * This function finds the latest vote result for a given action name and time.
 * It is used to compare the current vote result with the one that was before (current -1).
 */
export function findLatestVoteResult(
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

export function filterInflightVoteRequests(
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

export function getInflightVoteRequests(
  effectiveAt: string | undefined,
  tableType: VoteRequestResultTableType | undefined,
  dsoAction: { tag: 'SRARC_SetConfig'; value: DsoRules_SetConfig },
  voteRequests: VoteRequest[] | undefined
): [string, DsoRulesConfig][] {
  return !tableType
    ? filterInflightVoteRequests(dsoAction.tag, voteRequests)
        .map(vr => {
          const newConfig = (vr.action.value as ARC_DsoRules).dsoAction
            ?.value as DsoRules_SetConfig;
          return [vr.voteBefore, DsoRulesConfig.encode(newConfig.newConfig)] as [
            string,
            DsoRulesConfig,
          ];
        })
        .filter(v => !dayjs(v[0]).isSame(dayjs(effectiveAt)))
    : [];
}

export function getDsoConfigToCompareWith(
  effectiveAt: Date | undefined,
  tableType: VoteRequestResultTableType | undefined,
  votesHooks: BaseVotesHooks,
  dsoAction: { tag: 'SRARC_SetConfig'; value: DsoRules_SetConfig },
  dsoInfosQuery: UseQueryResult<DsoInfo>
): [string, DsoRulesConfig | undefined] {
  // TODO(DACH-NY/canton-network-node#15180): Implement effectivity on all actions
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

  const dsoConfigToCompareWith: [string, DsoRulesConfig | undefined] = !latestConfig
    ? [
        'initial',
        tableType
          ? dsoAction.value.baseConfig || dsoAction.value.newConfig
          : dsoInfosQuery.data
            ? (DsoRulesConfig.encode(dsoInfosQuery.data.dsoRules.payload.config) as DsoRulesConfig)
            : undefined,
      ]
    : [
        latestConfig.request.voteBefore,
        ((latestConfig.request.action.value as ARC_DsoRules).dsoAction.value as DsoRules_SetConfig)
          .newConfig,
      ];

  return dsoConfigToCompareWith;
}

export function getAmuletConfigToCompareWith(
  effectiveAt: Date | undefined,
  tableType: VoteRequestResultTableType | undefined,
  votesHooks: BaseVotesHooks,
  amuetAction: { tag: 'CRARC_SetConfig'; value: AmuletRules_SetConfig },
  dsoInfosQuery: UseQueryResult<DsoInfo>
): [string, AmuletConfig<USD>] | undefined {
  // TODO(DACH-NY/canton-network-node#15180): Implement effectivity on all actions
  // we need to subtract 1 second because the effectiveAt differs slightly from the completedAt in DsoRules-based actions
  const latestConfig =
    effectiveAt && tableType
      ? findLatestVoteResult(
          dayjs(effectiveAt).subtract(1, 'seconds').toISOString(),
          'CRARC_SetConfig',
          votesHooks,
          tableType
        )
      : undefined;

  let dsoConfigToCompareWith: [string, AmuletConfig<USD>] | undefined;

  if (!latestConfig) {
    dsoConfigToCompareWith = dsoInfosQuery.data
      ? [
          'initial',
          tableType
            ? amuetAction.value.baseConfig
            : (AmuletConfig(USD).encode(
                dsoInfosQuery.data?.amuletRules.payload.configSchedule.initialValue
              ) as AmuletConfig<USD>),
        ]
      : undefined;
  } else {
    dsoConfigToCompareWith = [
      latestConfig.request.voteBefore,
      (
        (latestConfig.request.action.value as ARC_AmuletRules).amuletRulesAction
          .value as AmuletRules_SetConfig
      ).newConfig,
    ];
  }

  return dsoConfigToCompareWith;
}
