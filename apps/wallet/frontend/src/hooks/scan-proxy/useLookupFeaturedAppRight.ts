// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useLookupFeaturedAppRightBuilder } from '@lfdecentralizedtrust/splice-common-frontend/scan-api';
import { UseQueryResult } from '@tanstack/react-query';

import { FeaturedAppRight } from '@daml.js/splice-amulet/lib/Splice/Amulet/';

import { useValidatorScanProxyClient } from '../../contexts/ValidatorScanProxyContext';

const useLookupFeaturedAppRight = (
  primaryPartyId?: string
): UseQueryResult<Contract<FeaturedAppRight> | undefined> => {
  const scanClient = useValidatorScanProxyClient();

  return useLookupFeaturedAppRightBuilder(
    () => scanClient.lookupFeaturedAppRight(primaryPartyId!),
    primaryPartyId
  );
};

export default useLookupFeaturedAppRight;
