// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { sanitizeUrl as sanitize } from '@braintree/sanitize-url';

export const sanitizeUrl = (url: string): string => {
  if (isStringEmptyOrUndefined(url)) return '';

  return sanitize(url);
};

const isStringEmptyOrUndefined = (str: string | undefined | null): boolean => {
  return str === undefined || str === null || str.trim() === '';
};
