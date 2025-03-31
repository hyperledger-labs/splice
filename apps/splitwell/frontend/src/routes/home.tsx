// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { usePrimaryParty } from '@lfdecentralizedtrust/splice-common-frontend';
import { useGetDsoPartyId } from '@lfdecentralizedtrust/splice-common-frontend/scan-api';
import { useCallback, useEffect, useMemo, useState } from 'react';

import { Container, Stack, Typography } from '@mui/material';

import GroupSetup from '../components/GroupSetup';
import Groups from '../components/Groups';
import { useSplitwellLedgerApiClient } from '../contexts/SplitwellLedgerApiContext';
import {
  useConnectedDomains,
  useProviderPartyId,
  useRequestSplitwellInstall,
  useSplitwellDomains,
  useSplitwellInstalls,
} from '../hooks';
import { useSplitwellRules } from '../hooks/queries/useSplitwellRules';

function collectFirst<A, B>(xs: A[], f: (elt: A) => B | undefined): B | undefined {
  for (const x of xs) {
    const b = f(x);
    if (b) {
      return b;
    }
  }
  return undefined;
}

const Home: React.FC = () => {
  const ledgerApiClient = useSplitwellLedgerApiClient();
  const { data: dso = undefined } = useGetDsoPartyId();

  const primaryPartyQuery = usePrimaryParty();
  const primaryPartyId = primaryPartyQuery.data;

  const provider = useProviderPartyId();

  const splitwellDomainIds = useSplitwellDomains();

  const connectedDomainIds = useConnectedDomains();

  const installs = useSplitwellInstalls();
  const requestInstall = useRequestSplitwellInstall();
  const rules = useSplitwellRules();

  const [requestedInstalls, setRequestedInstalls] = useState<string[]>([]);

  useEffect(() => {
    const requestInstalls = async () => {
      if (
        !primaryPartyId ||
        !connectedDomainIds.data ||
        !provider.data ||
        !installs.data ||
        !splitwellDomainIds.data ||
        !rules.data
      ) {
        return;
      } else {
        const connectedSplitwellRules = rules.data.filter(rules =>
          connectedDomainIds.data?.includes(rules.domainId)
        );
        for (const rules of connectedSplitwellRules) {
          const domainId = rules.domainId;
          if (
            !requestedInstalls.find(requested => requested === domainId) &&
            !installs.data.find(install => install.domain_id === domainId)
          ) {
            console.debug(
              `SplitwellInstall not found for domain ${domainId}, creating SplitwellInstallRequest`
            );
            setRequestedInstalls(requestedInstalls => requestedInstalls.concat([domainId]));
            await requestInstall.mutateAsync({
              domainId,
              primaryPartyId,
              rules: rules.contract,
            });
          }
        }
      }
    };
    requestInstalls();
  }, [
    primaryPartyId,
    provider,
    ledgerApiClient,
    splitwellDomainIds,
    connectedDomainIds,
    installs,
    requestInstall,
    requestedInstalls,
    rules,
  ]);

  const rulesMap = useMemo(
    () => new Map((rules.data || []).map(rules => [rules.domainId, rules.contract])),
    [rules]
  );

  const pickPreferredRulesDomain = useCallback(() => {
    if (!splitwellDomainIds.data) {
      return [undefined, undefined] as const;
    }
    const result = collectFirst(
      [splitwellDomainIds.data.preferred, ...splitwellDomainIds.data.others],
      id => {
        const rules = rulesMap.get(id);
        if (rules && connectedDomainIds.data?.includes(id)) {
          return [id, rules] as const;
        }
      }
    );
    return result ? result : ([undefined, undefined] as const);
  }, [rulesMap, splitwellDomainIds, connectedDomainIds]);

  const [preferredDomainId, preferredRulesDomain] = pickPreferredRulesDomain();

  if (provider.data && primaryPartyId && dso && splitwellDomainIds && preferredRulesDomain) {
    return (
      <Container>
        <Stack spacing={3}>
          <GroupSetup
            party={primaryPartyId}
            provider={provider.data}
            dso={dso}
            domainId={preferredDomainId}
            newGroupRules={preferredRulesDomain}
            rulesMap={rulesMap}
          />
          <Groups party={primaryPartyId} provider={provider.data} rulesMap={rulesMap} />
        </Stack>
      </Container>
    );
  } else {
    return <Typography>Loading …</Typography>;
  }
};

export default Home;
