// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { z } from 'zod';
import { isValidUrl } from '../../utils/validations';
import dayjs from 'dayjs';
import { EffectivityType } from '../../utils/types';

export const urlSchema = z.string().refine(url => isValidUrl(url), {
  message: 'Invalid URL',
});

export const summarySchema = z.string().min(1, { message: 'Summary is required' });

export const svSelectionSchema = z.string().min(1, { message: 'SV is required' });

export const expirationSchema = z.string().refine(date => dayjs(date).isAfter(dayjs()), {
  message: 'Expiration must be in the future',
});

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

export const grantRevokeFeaturedAppRightSchema = z.string().min(1, { message: 'Required' });

export const svWeightSchema = z
  .string()
  .min(1, { message: 'Weight is required' })
  .regex(/^\d+$/, { message: 'Weight must be a valid number' });

export const validateWeight = (value: string): string | false => {
  const result = svWeightSchema.safeParse(value);
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

export const validateSummary = (value: string): string | false => {
  const result = summarySchema.safeParse(value);
  return result.success ? false : result.error.issues[0].message;
};

export const validateUrl = (value: string): string | false => {
  const result = urlSchema.safeParse(value);
  return result.success ? false : result.error.issues[0].message;
};

export const validateGrantRevokeFeaturedAppRight = (value: string): string | false => {
  const result = grantRevokeFeaturedAppRightSchema.safeParse(value);
  return result.success ? false : result.error.issues[0].message;
};

export const validateNextScheduledSynchronizerUpgrade = (
  upgradeTime: string,
  migrationId: string,
  effectiveDate: string | undefined
): string | false => {
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
