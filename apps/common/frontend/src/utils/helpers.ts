// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  AssignedContract,
  Contract,
} from '@lfdecentralizedtrust/splice-common-frontend-utils/interfaces';
import { QueryObserverSuccessResult } from '@tanstack/react-query';
import dayjs from 'dayjs';

import {
  ActionRequiringConfirmation,
  DsoRules_CloseVoteRequestResult,
  DsoRules_SetConfig,
  DsoRulesConfig,
  VoteRequest,
} from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';
import { Unit } from '@daml.js/splice-wallet-payments/lib/Splice/Wallet/Payment';

import { getAction } from '../components/votes/ListVoteRequests';
import { VoteRequestResultTableType } from '../components/votes/VoteResultsFilterTable';
import { BaseVotesHooks, VotesHooks } from '../components/votes/VotesHooksProvider';
import { DsoInfo } from '../index';

import ARC_DsoRules = ActionRequiringConfirmation.ARC_DsoRules;

function equalWith<T>(a: T[], b: T[], p: (a: T, b: T) => boolean) {
  if (a.length !== b.length) {
    return false;
  }
  for (let i = 0; i < a.length; i++) {
    if (!p(a[i], b[i])) {
      return false;
    }
  }
  return true;
}

export const sameContracts = <T>(a: Contract<T>[], b: Contract<T>[]): boolean => {
  return equalWith(a, b, (l, r) => l.contractId === r.contractId);
};

export function sameAssignedContracts<T>(
  a: AssignedContract<T>[],
  b: AssignedContract<T>[]
): boolean {
  return equalWith(
    a,
    b,
    (l, r) => l.contract.contractId === r.contract.contractId && l.domainId === r.domainId
  );
}

export const unitStringToCurrency = (unit: string): string => {
  switch (unit) {
    case 'AMULETUNIT':
      return window.splice_config.spliceInstanceNames?.amuletNameAcronym;
    case 'USDUNIT':
      return 'USD';
    case 'EXTUNIT':
      throw new Error('ExtUnit must not be present at runtime');
    default:
      console.log(`unexpected unit: ${unit}`);
      throw new Error(`Unexpected unit: ${unit}`);
  }
};

export const unitToCurrency = (unit: Unit): string => {
  return unitStringToCurrency(unit.toUpperCase());
};

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
            DsoRulesConfig
          ];
        })
        .filter(v => !dayjs(v[0]).isSame(dayjs(effectiveAt)))
    : [];
}

export function getDsoConfigToCompareWith(
  effectiveAt: string | undefined,
  tableType: VoteRequestResultTableType | undefined,
  votesHooks: BaseVotesHooks,
  dsoAction: { tag: 'SRARC_SetConfig'; value: DsoRules_SetConfig },
  dsoInfosQuery: QueryObserverSuccessResult<DsoInfo>
): [string, DsoRulesConfig] {
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

  return dsoConfigToCompareWith;
}
