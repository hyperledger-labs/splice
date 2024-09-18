// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
export * from './config';

export const toEntryNameSuffix: (nameServiceNameAcronym: string) => string = (
  nameServiceNameAcronym: string
) => `.unverified.${nameServiceNameAcronym.toLowerCase()}`;
export const toFullEntryName: (name: string, suffix: string) => string = (
  name: string,
  suffix: string
) => `${name}${suffix}`;
