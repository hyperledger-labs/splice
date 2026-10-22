// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { useQuery } from '@tanstack/react-query';
import { useSvAdminClient } from '../contexts/SvAdminServiceContext';
import { useListDsoRulesVoteRequests } from './useListVoteRequests';
import { validatePartyId } from '../components/forms/formValidators';
import { extractInFlightOpsParties } from '../utils/governance';
import { useMemo } from 'react';

export interface FeaturedAppGrantWarnings {
  providerHasRightWarning?: string;
  opsOverlapWarning?: string;
}

// Non-blocking warnings for the grant form (#7665). Mirrors the backend checks but is advisory:
// (a) provider already has a FeaturedAppRight (granting another duplicates it), and
// (b) chosen opsParties overlap with an in-flight vote request. The backend still rejects on
// submit; these just surface the conflict earlier. The provider query only fires on a fully
// valid party id, so it does not run per keystroke.
export const useFeaturedAppGrantWarnings = (
  provider: string,
  opsParties: string[]
): FeaturedAppGrantWarnings => {
  const svAdminClient = useSvAdminClient();
  const providerIsValid = validatePartyId(provider) === false;

  const rightsQuery = useQuery({
    queryKey: ['featuredAppRightsByProvider', provider],
    enabled: providerIsValid,
    queryFn: () => svAdminClient.listFeaturedAppRightsByProvider(provider),
  });

  const voteRequestsQuery = useListDsoRulesVoteRequests();

  const providerHasRightWarning =
    providerIsValid && (rightsQuery.data?.featured_app_rights.length ?? 0) > 0
      ? 'This provider already has a featured app right. Granting another will create a duplicate.'
      : undefined;

  const cleanedOps = opsParties.map(p => p.trim()).filter(p => p !== '');
  const inFlight = useMemo(
    () => new Set(extractInFlightOpsParties(voteRequestsQuery.data)),
    [voteRequestsQuery.data]
  );
  const overlap = cleanedOps.filter(p => inFlight.has(p));
  const opsOverlapWarning =
    overlap.length > 0
      ? `These ops parties are already referenced by a pending vote request: ${overlap.join(', ')}`
      : undefined;

  return { providerHasRightWarning, opsOverlapWarning };
};
