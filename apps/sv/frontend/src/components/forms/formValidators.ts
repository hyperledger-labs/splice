// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import dayjs from 'dayjs';
import { z } from 'zod';
import type { EffectivityType } from '../../utils/types';
import { isValidUrl } from '../../utils/validations';

export const urlSchema = z.string().refine(url => isValidUrl(url), {
  message: 'Invalid URL',
});

export const summarySchema = z.string().min(1, { message: 'Summary is required' });

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
  .regex(/^\d+$/, { message: 'Amount must be a valid number' });

export const validateWeight = (value: string): string | false => {
  const result = svWeightSchema.safeParse(value);
  return result.success ? false : result.error.issues[0].message;
};

export const validateRewardAmount = (value: string): string | false => {
  const result = rewardAmountSchema.safeParse(value);
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
    .refine(({ effectiveDate, mintBefore }) => dayjs(effectiveDate).isBefore(dayjs(mintBefore)), {
      message: 'Mint Before date must be after Effective Date',
      path: ['mintBefore'],
    });

  const result = schema.safeParse(value);
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
