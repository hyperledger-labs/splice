// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { Contract } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useQuery, UseQueryResult } from '@tanstack/react-query';
import { ApiException } from 'scan-proxy-openapi';

import { TransferPreapproval } from '@daml.js/splice-amulet/lib/Splice/AmuletRules/module';

import { useValidatorScanProxyClient } from '../../contexts/ValidatorScanProxyContext';

const useLookupTransferPreapproval = (
  party?: string
): UseQueryResult<Contract<TransferPreapproval> | null> => {
  const scanClient = useValidatorScanProxyClient();

  return useQuery<Contract<TransferPreapproval> | null>({
    queryKey: ['scan-api', 'lookupTransferPreapproval', party, TransferPreapproval],
    queryFn: async () => {
      try {
        const response = await scanClient.lookupTransferPreapprovalByParty(party!);
        return Contract.decodeOpenAPI(response.transfer_preapproval.contract, TransferPreapproval);
      } catch (e: unknown) {
        if ((e as ApiException<undefined>).code === 404) {
          return null;
        } else if ((e as ApiException<undefined>).code === 400) {
          // This can happen if the party id is not a valid party id
          return null;
        } else {
          console.info(
            `Unexpected exception when querying for transfer preapproval for ${party}: ${e}`
          );
          throw e;
        }
      }
    },
    enabled: !!party,
  });
};

export default useLookupTransferPreapproval;
