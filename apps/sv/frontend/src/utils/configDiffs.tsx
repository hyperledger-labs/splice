// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
// @ts-ignore
import * as jsondiffpatch from 'jsondiffpatch';
import { computeDiff } from 'common-frontend';
import { Contract } from 'common-frontend-utils';

import { ActionRequiringConfirmation } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules';
import { VoteRequest } from '@daml.js/splice-dso-governance/lib/Splice/DsoRules/module';

import { ActionFromForm } from '../components/votes/VoteRequest';

/** function used to parse the keys from jsondiffpatch.Delta, which has the form
 * {
 *   key1: {
 *     key2 : [newValue, oldValue]
 *   }
 *   key3: [newValue, oldValue]
 * }, into a list of flatten keys [key1.key2, key3]
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
function flattenKeys(obj: Record<string, any>, parentKey = '', separator = '.'): string[] {
  return Object.entries(obj || {}).reduce((acc, [key, value]) => {
    const newKey = parentKey ? `${parentKey}${separator}${key}` : key;
    if (value && typeof value === 'object' && !Array.isArray(value)) {
      acc.push(...flattenKeys(value, newKey, separator)); // Recurse into nested objects
    } else {
      const cleanKey = newKey
        .split(separator)
        .filter(part => !part.startsWith('_') && !/^\d+$/.test(part))
        .join(separator); // needed to avoid adding weird keys when parsing Map keys
      acc.push(cleanKey); // Add the key of primitive values or arrays
    }
    return acc;
  }, [] as string[]);
}

function filterMostSpecificKeys(keys: string[]): string[] {
  const uniqueKeys = new Set(keys); // Remove exact duplicates
  return [...uniqueKeys].filter(
    key =>
      ![...uniqueKeys].some(
        otherKey => otherKey.startsWith(key) && otherKey !== key // If another key is more specific, remove this one
      )
  );
}

function intersectConfigDiffsKeys(
  currentDiffsKeys: string[],
  voteRequestsDiffsKeys: string[]
): string[] {
  return filterMostSpecificKeys(voteRequestsDiffsKeys).filter(e => currentDiffsKeys.includes(e));
}

/**
 *
 * @param action current action to be parsed
 * @param currentActionTag Optional argument to specify which action should be parsed
 */
function parseDiffs(
  action: ActionRequiringConfirmation,
  currentActionTag?: string
): jsondiffpatch.Delta | null {
  let currentDiffsKeys = null;
  const tag = action.tag;
  if (action.tag === 'ARC_AmuletRules' && tag === (currentActionTag ?? tag)) {
    if (action.value.amuletRulesAction.tag === 'CRARC_SetConfig') {
      currentDiffsKeys = computeDiff({
        new: action.value.amuletRulesAction.value.newConfig,
        base: action.value.amuletRulesAction.value.baseConfig,
      });
    }
  } else if (action.tag === 'ARC_DsoRules' && tag === (currentActionTag ?? tag)) {
    if (action.value.dsoAction.tag === 'SRARC_SetConfig') {
      currentDiffsKeys =
        action.value.dsoAction.value.baseConfig &&
        computeDiff({
          new: action.value.dsoAction.value.newConfig,
          base: action.value.dsoAction.value.baseConfig,
        });
    }
  }
  return currentDiffsKeys;
}

export function hasConflictingFields(
  action?: ActionFromForm,
  voteRequests?: Contract<VoteRequest>[]
): { hasConflict: boolean; intersection: string[] } {
  if (!action) {
    return { hasConflict: false, intersection: [] };
  }
  if (!voteRequests) {
    return { hasConflict: false, intersection: [] };
  }
  const currentDiffs = parseDiffs(action as ActionRequiringConfirmation);
  if (!currentDiffs) {
    return { hasConflict: false, intersection: [] };
  }
  const voteRequestsDiffs: jsondiffpatch.Delta[] = voteRequests
    .map(r => parseDiffs(r.payload.action, (action as ActionRequiringConfirmation).tag))
    .filter((e): e is jsondiffpatch.Delta => e !== null);
  if (voteRequestsDiffs.length === 0) {
    return { hasConflict: false, intersection: [] };
  }
  const voteRequestsDiffsKeys = voteRequestsDiffs.flatMap(e => flattenKeys(e as object));
  const currentDiffsKeys = flattenKeys(currentDiffs);
  const intersection = intersectConfigDiffsKeys(currentDiffsKeys, voteRequestsDiffsKeys);
  return { hasConflict: intersection.length > 0, intersection };
}
