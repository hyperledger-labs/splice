// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import { z } from 'zod';
import type { EffectivityType } from '../../utils/types';
import { isValidUrl } from '../../utils/validations';
import { nextScheduledSynchronizerUpgradeFormat } from '@canton-network/splice-common-frontend-utils';

dayjs.extend(utc);

export const urlSchema = z.string().refine(url => isValidUrl(url), {
  message: 'Invalid URL',
});

export const summarySchema = z.string().min(1, { message: 'Summary is required' });

export const reasonSchema = z.string().min(1, { message: 'Reason is required' });

export const svSelectionSchema = z.string().min(1, { message: 'SV is required' });

const getExpirationSchema = (errMessage: string) => {
  return z.string().refine(date => dayjs(date).isAfter(dayjs()), {
    message: errMessage,
  });
};

export const expirationSchema = getExpirationSchema('Expiration must be in the future');

export const mintBeforeSchema = getExpirationSchema('Date must be in the future');

export const effectiveDateSchema = z.string().refine(date => dayjs(date).isAfter(dayjs()), {
  message: 'Effective Date must be in the future',
});

export const expiryEffectiveDateSchema = z
  .object({
    expiration: z.string(),
    effectiveDate: z.string(),
  })
  .refine(({ expiration, effectiveDate }) => dayjs(expiration).isBefore(dayjs(effectiveDate)), {
    message: 'Effective Date must be after expiration date',
    path: ['effectiveDate'],
  });

export const revokeFeaturedAppRightSchema = z.string().min(1, { message: 'Required' });

export const partyIdSchema = z
  .string()
  .min(1, { message: 'Required' })
  .regex(/^[a-zA-Z0-9_-]+::[a-zA-Z0-9_-]+$/, {
    message: 'Invalid PartyId format. Expected format: identifier::fingerprint',
  });

export const svWeightSchema = z
  .string()
  .min(1, { message: 'Weight is required' })
  .regex(/^\d+_\d{4}$/, {
    message: 'Weight must be expressed in basis points using fixed point notation, XX...X_XXXX',
  });

export const rewardAmountSchema = z
  .string()
  .min(1, { message: 'Amount is required' })
  .regex(/^\d+(\.\d+)?$/, { message: 'Amount must be a valid number' })
  .refine(
    v => {
      const dotIndex = v.indexOf('.');
      return dotIndex === -1 || v.length - dotIndex - 1 <= 10;
    },
    { message: 'Amount can have at most 10 decimal places' }
  );

export const requiredActivityWeightSchema = z
  .string()
  .min(1, { message: 'Weight is required' })
  .regex(/^\d+(\.\d+)?$/, { message: 'Weight must be a valid non-negative number' })
  .refine(
    v => {
      const i = v.indexOf('.');
      return i === -1 || v.length - i - 1 <= 10;
    },
    { message: 'Weight can have at most 10 decimal places' }
  );

export const activityWeightSchema = z
  .string()
  .refine(v => v === '' || /^\d+(\.\d+)?$/.test(v), {
    message: 'Weight must be a valid non-negative number',
  })
  .refine(
    v => {
      const dotIndex = v.indexOf('.');
      return dotIndex === -1 || v.length - dotIndex - 1 <= 10;
    },
    { message: 'Weight can have at most 10 decimal places' }
  );

export const validateWeight = (value: string): string | false => {
  const result = svWeightSchema.safeParse(value);
  return result.success ? false : result.error.issues[0].message;
};

export const validateRewardAmount = (value: string): string | false => {
  const result = rewardAmountSchema.safeParse(value);
  return result.success ? false : result.error.issues[0].message;
};

export const validateActivityWeight = (value: string): string | false => {
  const result = activityWeightSchema.safeParse(value);
  return result.success ? false : result.error.issues[0].message;
};

export const validateRequiredActivityWeight = (value: string): string | false => {
  const result = requiredActivityWeightSchema.safeParse(value);
  return result.success ? false : result.error.issues[0].message;
};

export const validateSvSelection = (value: string): string | false => {
  const result = svSelectionSchema.safeParse(value);
  return result.success ? false : result.error.issues[0].message;
};

export const validateExpiration = (value: string): string | false => {
  const result = expirationSchema.safeParse(value);
  return result.success ? false : result.error.issues[0].message;
};

export const validateMintBefore = (value: string): string | false => {
  const result = mintBeforeSchema.safeParse(value);
  return result.success ? false : result.error.issues[0].message;
};

export const validateMintedBeneficiary = (value: string): string | false => {
  const schema = z.string().min(1, { message: 'Beneficiary is required' });

  const result = schema.safeParse(value);
  return result.success ? false : result.error.issues[0].message;
};

export const validateEffectiveDate = (value: {
  type: EffectivityType;
  effectiveDate: string | undefined;
}): string | false => {
  // nothing to validate if effective at threshold
  if (value.type === 'threshold') return false;

  const result = effectiveDateSchema.safeParse(value.effectiveDate);
  return result.success ? false : result.error.issues[0].message;
};

export const validateExpiryEffectiveDate = (value: {
  expiration: string;
  effectiveDate?: string;
}): string | false => {
  if (!value.effectiveDate) return false;

  const result = expiryEffectiveDateSchema.safeParse(value);
  return result.success ? false : result.error.issues[0].message;
};

export const validateMintBeforeAndEffectiveDate = (value: {
  effectiveDate?: string;
  mintBefore: string;
}): string | false => {
  if (!value.effectiveDate) return false;

  const schema = z
    .object({
      effectiveDate: z.string(),
      mintBefore: z.string(),
    })
    .refine(
      ({ effectiveDate, mintBefore }) =>
        dayjs(mintBefore).isAfter(dayjs(effectiveDate).add(2, 'hour')),
      {
        message: 'Mint Before date must be at least 2 hours after Effective Date',
        path: ['mintBefore'],
      }
    );

  const result = schema.safeParse(value);
  return result.success ? false : result.error.issues[0].message;
};

export const validateSummary = (value: string): string | false => {
  const result = summarySchema.safeParse(value);
  return result.success ? false : result.error.issues[0].message;
};

export const validateReason = (value: string): string | false => {
  const result = reasonSchema.safeParse(value);
  return result.success ? false : result.error.issues[0].message;
};

export const validateUrl = (value: string): string | false => {
  const result = urlSchema.safeParse(value);
  return result.success ? false : result.error.issues[0].message;
};

export const validateRevokeFeaturedAppRight = (value: string): string | false => {
  const result = revokeFeaturedAppRightSchema.safeParse(value);
  return result.success ? false : result.error.issues[0].message;
};

export const validatePartyId = (value: string): string | false => {
  const result = partyIdSchema.safeParse(value);
  return result.success ? false : result.error.issues[0].message;
};

export const validateNextScheduledSynchronizerUpgrade = (
  upgradeTime: string,
  migrationId: string,
  effectiveDate: string | undefined,
  current: { upgradeTime: string; migrationId: string } = { upgradeTime: '', migrationId: '' }
): string | false => {
  if (upgradeTime === current.upgradeTime && migrationId === current.migrationId) {
    return false;
  }

  const onlyOneIsProvided = (upgradeTime === '') !== (migrationId === '');
  const bothEmpty = upgradeTime === '' && migrationId === '';

  if (bothEmpty) {
    return false;
  }

  if (onlyOneIsProvided) {
    return 'Upgrade Time and Migration ID are required for a Scheduled Synchronizer Upgrade';
  }

  const upgradeTimeDate = dayjs.utc(upgradeTime);
  const effectivity = dayjs(effectiveDate);

  const upgradeTimeIsAfterEffectiveDate = upgradeTimeDate.isAfter(effectivity.add(1, 'hour'));
  if (!upgradeTimeIsAfterEffectiveDate) {
    return 'Upgrade Time must be at least 1 hour after the Effective Date';
  }

  return false;
};

export type LogicalSynchronizerUpgradeValues = {
  topologyFreezeTime: string;
  upgradeTime: string;
  newPhysicalSynchronizerSerial: string;
  newPhysicalSynchronizerProtocolVersion: string;
};

export const validateNextScheduledLogicalSynchronizerUpgrade = (
  topologyFreezeTime: string,
  upgradeTime: string,
  newPhyiscalSynchronizerSerial: string,
  newPhyiscalSynchronizerProtocolVersion: string,
  effectiveDate: string | undefined,
  current?: LogicalSynchronizerUpgradeValues
): string | false => {
  const all = [
    topologyFreezeTime,
    upgradeTime,
    newPhyiscalSynchronizerSerial,
    newPhyiscalSynchronizerProtocolVersion,
  ];
  const currentValues = [
    current?.topologyFreezeTime ?? '',
    current?.upgradeTime ?? '',
    current?.newPhysicalSynchronizerSerial ?? '',
    current?.newPhysicalSynchronizerProtocolVersion ?? '',
  ];

  if (all.every((value, i) => value === currentValues[i])) {
    return false;
  }

  if (all.every(value => value === '')) {
    return false;
  }

  if (!all.every(value => value !== '')) {
    return 'Topology freeze time, upgrade time, new physical synchronizer serial, and new physical synchronizer protocol version are required for a Scheduled Logical Synchronizer Upgrade';
  }

  const freezeTimeDate = dayjs.utc(topologyFreezeTime);
  const effectivity = dayjs(effectiveDate);

  const freezeTimeIsAfterEffectiveDate = freezeTimeDate.isAfter(effectivity.add(1, 'hour'));
  if (!freezeTimeIsAfterEffectiveDate) {
    return 'Topology Freeze Time must be at least 1 hour after the Effective Date';
  }

  const upgradeTimeDate = dayjs.utc(upgradeTime);
  if (!upgradeTimeDate.isAfter(freezeTimeDate)) {
    return 'Upgrade Time must be after Topology Freeze Time';
  }

  return false;
};

export type SwitchOverEntry = { key: string; time: string };

// Trim keys, drop empty-key entries, normalize times to the DAML `Time` format; null when empty.
export const serializeSwitchOverTimes = (
  entries: SwitchOverEntry[]
): Record<string, string> | null => {
  const trimmed = entries.map(e => ({ key: e.key.trim(), time: e.time })).filter(e => e.key !== '');

  return trimmed.length === 0
    ? null
    : Object.fromEntries(
        trimmed.map(e => [
          e.key,
          dayjs(e.time).utc().format(nextScheduledSynchronizerUpgradeFormat),
        ])
      );
};

// Stable sorted-key JSON string used as the ConfigChange value; '' for an empty map.
export const switchOverEntriesToConfigValue = (entries: SwitchOverEntry[]): string => {
  const normalized = serializeSwitchOverTimes(entries) ?? {};
  const sorted = Object.fromEntries(
    Object.entries(normalized).toSorted(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
  );
  return Object.keys(sorted).length === 0 ? '' : JSON.stringify(sorted);
};

// Same config value as switchOverEntriesToConfigValue, from a baseline DAML map.
export const switchOverMapToConfigValue = (
  map: Record<string, string> | null | undefined
): string =>
  switchOverEntriesToConfigValue(Object.entries(map ?? {}).map(([key, time]) => ({ key, time })));

// Parse the config value back into a DAML switch-over map (null when empty).
export const configValueToSwitchOverMap = (
  value: string | null | undefined
): Record<string, string> | null => {
  if (!value) return null;
  const parsed = JSON.parse(value) as Record<string, string>;
  return Object.keys(parsed).length === 0 ? null : parsed;
};

// Config field names whose value is a serialized switch-over map.
export const SWITCH_OVER_FIELD_NAMES = [
  'svOperationsSwitchOverTimes',
  'amuletSwitchOverTimes',
] as const;

export const isSwitchOverField = (fieldName: string): boolean =>
  (SWITCH_OVER_FIELD_NAMES as readonly string[]).includes(fieldName);

// Shown in place of a switch-over time that is empty or an unset placeholder.
export const SWITCH_OVER_UNSET_LABEL = 'Not set';

// A DAML `Time` min-bound (year 1, i.e. 0001-01-01) is used as an "unset" placeholder for
// switch-over times; it is not a real, user-meaningful timepoint.
export const isDamlMinBoundTime = (time: string | null | undefined): boolean => {
  if (!time) return false;
  const t = dayjs.utc(time);
  return t.isValid() && t.year() <= 1;
};

// Parse a config value into sorted key -> human-readable UTC rows for display. Min-bound
// placeholders are dropped entirely; [] is returned when nothing meaningful is left.
export const switchOverConfigValueToDisplayEntries = (
  value: string | null | undefined
): { key: string; time: string }[] => {
  const map = configValueToSwitchOverMap(value);
  if (!map) return [];
  return Object.entries(map)
    .filter(([, time]) => !isDamlMinBoundTime(time))
    .toSorted(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
    .map(([key, time]) => {
      const t = dayjs.utc(time);
      return { key, time: t.isValid() ? `${t.format('YYYY-MM-DD HH:mm')} UTC` : time };
    });
};

// Editor rows to render for the switch-over field: min-bound placeholders are hidden but
// kept in form state so they round-trip on submit. Original array indices are preserved
// for field names and row removal.
export const visibleSwitchOverRows = (
  entries: SwitchOverEntry[]
): { entry: SwitchOverEntry; index: number }[] =>
  entries
    .map((entry, index) => ({ entry, index }))
    .filter(({ entry }) => !isDamlMinBoundTime(entry.time));

const isUnchangedSwitchOverEntry = (entry: SwitchOverEntry, current: SwitchOverEntry[]): boolean =>
  current.some(c => c.key.trim() === entry.key.trim() && dayjs(c.time).isSame(dayjs(entry.time)));

export const validateSwitchOverTimes = (
  entries: SwitchOverEntry[],
  allowNonFutureDated: boolean,
  effectiveDate: string | undefined,
  current: SwitchOverEntry[] = []
): string | false => {
  // Min-bound placeholders are treated as unset and excluded from validation entirely
  // (they are preserved in form state but not shown as editable rows).
  const active = entries.filter(e => !isDamlMinBoundTime(e.time));
  if (active.length === 0) return false;

  const keys = active.map(e => e.key.trim());

  if (keys.some(k => k === '')) {
    return 'Switch-over key is required';
  }

  if (new Set(keys).size !== keys.length) {
    return 'Switch-over keys must be unique';
  }

  for (const { key, time } of active) {
    // Times are stored as local wall-clock strings (dateTimeFormatISO), matching the
    // DateField picker and the effective date; parse them in the same (local) frame.
    // The builder converts to a UTC DAML Time on submit.
    const t = dayjs(time);
    if (!t.isValid()) {
      return `Invalid time for switch-over "${key.trim()}"`;
    }
    // Skip the ">= 1 day after effectivity" check at threshold (no effective date),
    // when the operator has opted into non-future-dated times, or for an entry left
    // unchanged from the current config.
    if (
      !allowNonFutureDated &&
      effectiveDate &&
      !isUnchangedSwitchOverEntry({ key, time }, current)
    ) {
      const minTime = dayjs(effectiveDate).add(1, 'day');
      if (t.isBefore(minTime)) {
        return `Switch-over "${key.trim()}" must be at least 1 day after the Effective Date`;
      }
    }
  }
  return false;
};
