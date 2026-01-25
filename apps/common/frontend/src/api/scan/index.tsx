// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { ScanClientProvider, useScanClient } from './ScanClientContext';
import useActivity from './useActivity';
import useAmuletPrice, { useAmuletPriceFromOpenRounds } from './useAmuletPrice';
import useBackfillingStatus from './useBackfillingStatus';
import useDsoInfo from './useDsoInfo';
import useGetAmuletRules from './useGetAmuletRules';
import useGetAnsRules, { useGetAnsRulesFromResponse } from './useGetAnsRules';
import useGetDsoPartyId from './useGetDsoPartyId';
import useGetRoundOfLatestData from './useGetRoundOfLatestData';
import useGetTopValidatorsByPurchasedTraffic from './useGetTopValidatorsByPurchasedTraffic';
import useGetTopValidatorsByValidatorFaucets from './useGetTopValidatorsByValidatorFaucets';
import useGetTopValidatorsByValidatorRewards from './useGetTopValidatorsByValidatorRewards';
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
import useTopAppProviders from './useTopAppProviders';
import useTotalRewards from './useTotalRewards';

export {
  useScanClient,
  ScanClientProvider,
  useAmuletPrice,
  useAmuletPriceFromOpenRounds,
  useBackfillingStatus,
  useGetAmuletRules,
  useGetAnsRules,
  useGetAnsRulesFromResponse,
  useGetRoundOfLatestData,
  useGetDsoPartyId,
  useGetTopValidatorsByValidatorRewards,
  useGetTopValidatorsByPurchasedTraffic,
  useGetTopValidatorsByValidatorFaucets,
  useLookupFeaturedAppRight,
  useLookupFeaturedAppRightBuilder,
  useActivity,
  useTopAppProviders,
  useTotalRewards,
  useListAnsEntries,
  useListAnsEntriesFromResponse,
  useLookupAnsEntryByName,
  useLookupAnsEntryByNameFromResponse,
  useLookupAnsEntryByParty,
  useLookupAnsEntryByPartyFromResponse,
  useOpenRounds,
  useDsoInfo,
};
