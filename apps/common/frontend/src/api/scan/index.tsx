// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ScanClientProvider, useScanClient } from './ScanClientContext';
import useAmuletPrice, { useAmuletPriceFromOpenRounds } from './useAmuletPrice';
import useBackfillingStatus from './useBackfillingStatus';
import useGetAmuletRules from './useGetAmuletRules';
import useGetAnsRules, { useGetAnsRulesFromResponse } from './useGetAnsRules';
import useGetDsoPartyId from './useGetDsoPartyId';
import useListAnsEntries, { useListAnsEntriesFromResponse } from './useListAnsEntries';
import useLookupAnsEntryByName, {
  useLookupAnsEntryByNameFromResponse,
} from './useLookupAnsEntryByName';
import useLookupAnsEntryByParty, {
  useLookupAnsEntryByPartyFromResponse,
} from './useLookupAnsEntryByParty';
import useLookupFeaturedAppRight, {
  useLookupFeaturedAppRightBuilder,
} from './useLookupFeaturedAppRight';
import useOpenRounds from './useOpenRounds';

export {
  useScanClient,
  ScanClientProvider,
  useAmuletPrice,
  useAmuletPriceFromOpenRounds,
  useBackfillingStatus,
  useGetAmuletRules,
  useGetAnsRules,
  useGetAnsRulesFromResponse,
  useGetDsoPartyId,
  useLookupFeaturedAppRight,
  useLookupFeaturedAppRightBuilder,
  useListAnsEntries,
  useListAnsEntriesFromResponse,
  useLookupAnsEntryByName,
  useLookupAnsEntryByNameFromResponse,
  useLookupAnsEntryByParty,
  useLookupAnsEntryByPartyFromResponse,
  useOpenRounds,
};
