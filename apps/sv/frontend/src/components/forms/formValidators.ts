// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { z } from 'zod';
import { isValidUrl } from '../../utils/validations';
import dayjs from 'dayjs';

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

export const validateWeight = (value: string): string | undefined => {
  const result = svWeightSchema.safeParse(value);
  return result.success ? undefined : result.error.issues[0].message;
};

export const validateSvSelection = (value: string): string | undefined => {
  const result = svSelectionSchema.safeParse(value);
  return result.success ? undefined : result.error.issues[0].message;
};

export const validateExpiration = (value: string): string | undefined => {
  const result = expirationSchema.safeParse(value);
  return result.success ? undefined : result.error.issues[0].message;
};

export const validateEffectiveDate = (value: string): string | undefined => {
  const result = effectiveDateSchema.safeParse(value);
  return result.success ? undefined : result.error.issues[0].message;
};

export const validateExpiryEffectiveDate = (value: {
  expiration: string;
  effectiveDate: string;
}): string | undefined => {
  const result = expiryEffectiveDateSchema.safeParse(value);
  return result.success ? undefined : result.error.issues[0].message;
};

export const validateSummary = (value: string): string | undefined => {
  const result = summarySchema.safeParse(value);
  return result.success ? undefined : result.error.issues[0].message;
};

export const validateUrl = (value: string): string | undefined => {
  const result = urlSchema.safeParse(value);
  return result.success ? undefined : result.error.issues[0].message;
};

export const validateGrantRevokeFeaturedAppRight = (value: string): string | undefined => {
  const result = grantRevokeFeaturedAppRightSchema.safeParse(value);
  return result.success ? undefined : result.error.issues[0].message;
};
