// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { useTokenMetadataClient } from '../api/TokenMetadataClientContext';
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { Instrument } from '@lfdecentralizedtrust/token-metadata-openapi';

export const useAmuletMetadata = (): UseQueryResult<Instrument> => {
  const metadataClient = useTokenMetadataClient();
  return useQuery({
    queryKey: ['useAmuletMetadata'],
    queryFn: async () => {
      return await metadataClient.getInstrument('Amulet');
    },
  });
};
