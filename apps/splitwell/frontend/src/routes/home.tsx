import { usePrimaryParty } from 'common-frontend';
import { useGetSvcPartyId } from 'common-frontend/scan-api';
import { useCallback, useEffect, useMemo, useState } from 'react';

import { Container, Stack, Typography } from '@mui/material';

import { SplitwellInstall } from '@daml.js/splitwell/lib/CN/Splitwell';
import { ContractId } from '@daml/types';

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
  const { data: svc = undefined } = useGetSvcPartyId();

  const primaryPartyQuery = usePrimaryParty();
  const primaryPartyId = primaryPartyQuery.data;

  const provider = useProviderPartyId();

  const splitwellDomainIds = useSplitwellDomains();

  const connectedDomainIds = useConnectedDomains();

  const installs = useSplitwellInstalls();
  const requestInstall = useRequestSplitwellInstall();

  const [requestedInstalls, setRequestedInstalls] = useState<string[]>([]);

  useEffect(() => {
    const requestInstalls = async () => {
      if (
        !primaryPartyId ||
        !connectedDomainIds.data ||
        !provider.data ||
        !installs.data ||
        !splitwellDomainIds.data
      ) {
        return;
      } else {
        const connectedSplitwellDomainIds = connectedDomainIds.data.filter(
          d => splitwellDomainIds.data.preferred === d || splitwellDomainIds.data.others.includes(d)
        );
        for (const domainId of connectedSplitwellDomainIds) {
          if (
            !requestedInstalls.find(requested => requested === domainId) &&
            !installs.data.find(install => install.domainId === domainId)
          ) {
            console.debug(
              `SplitwellInstall not found for domain ${domainId}, creating SplitwellInstallRequest`
            );
            setRequestedInstalls(requestedInstalls => requestedInstalls.concat([domainId]));
            await requestInstall.mutateAsync({
              domainId,
              primaryPartyId,
              providerPartyId: provider.data,
              ledgerApiClient,
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
  ]);

  const installsMap = useMemo(
    () =>
      new Map(
        (installs.data || []).map(install => [
          install.domainId,
          install.contractId as ContractId<SplitwellInstall>,
        ])
      ),
    [installs]
  );

  const pickPreferredInstallDomain = useCallback(() => {
    if (!splitwellDomainIds.data) {
      return [undefined, undefined] as const;
    }
    const result = collectFirst(
      [splitwellDomainIds.data.preferred, ...splitwellDomainIds.data.others],
      id => {
        const install = installsMap.get(id);
        if (install) {
          return [id, install] as const;
        }
      }
    );
    return result ? result : ([undefined, undefined] as const);
  }, [installsMap, splitwellDomainIds]);

  const [preferredDomainId, preferredInstallDomain] = pickPreferredInstallDomain();

  if (provider.data && primaryPartyId && svc && splitwellDomainIds && preferredInstallDomain) {
    return (
      <Container>
        <Stack spacing={3}>
          <GroupSetup
            party={primaryPartyId}
            provider={provider.data}
            svc={svc}
            domainId={preferredDomainId}
            newGroupInstall={preferredInstallDomain}
            installs={installsMap}
          />
          <Groups party={primaryPartyId} provider={provider.data} installs={installsMap} />
        </Stack>
      </Container>
    );
  } else {
    return <Typography>Loading …</Typography>;
  }
};

export default Home;
