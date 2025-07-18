// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';

dayjs.extend(utc);

export interface VoteRequestValidity {
  isValid: boolean;
  alertMessage: object;
}

export function isValidUrl(url: string): boolean {
  if (!url) return false;

  let validUrl: URL;
  try {
    validUrl = new URL(url);
  } catch (error) {
    console.debug(`Invalid URL: ${url}`, error);
    return false;
  }
  return ['http:', 'https:'].some(protocol => validUrl.protocol === protocol);
}

export function isValidVoteRequestUrl(url: string): boolean {
  return url === '' || isValidUrl(url);
}
