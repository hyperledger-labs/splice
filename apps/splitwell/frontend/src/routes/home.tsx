import { usePrimaryParty, useUserState, useStateSnapshotServiceClient } from 'common-frontend';
import { useGetSvcPartyId } from 'common-frontend/scan-api';
import {
  ListSplitwellInstallsRequest,
  SplitwellContext,
} from 'common-protobuf/com/daml/network/splitwell/v0/splitwell_service_pb';
import { GetConnectedDomainsRequest } from 'common-protobuf/com/digitalasset/canton/research/participant/multidomain/state_service_pb';
import { Empty } from 'google-protobuf/google/protobuf/empty_pb';
import { useCallback, useState, useEffect } from 'react';

import { Container, Stack, Typography } from '@mui/material';

import { SplitwellInstall, SplitwellInstallRequest } from '@daml.js/splitwell/lib/CN/Splitwell';
import { ContractId } from '@daml/types';

import GroupSetup from '../components/GroupSetup';
import Groups from '../components/Groups';
import { useSplitwellLedgerApiClient } from '../contexts/SplitwellLedgerApiContext';
import { useSplitwellClient } from '../contexts/SplitwellServiceContext';

type SplitwellDomains = {
  preferred: string;
  others: string[];
};

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
  const splitwellClient = useSplitwellClient();
  const ledgerApiClient = useSplitwellLedgerApiClient();
  const stateSnapshotServiceClient = useStateSnapshotServiceClient();
  const { data: svc = undefined } = useGetSvcPartyId();
  const { updateStatus } = useUserState();

  const [provider, setProvider] = useState<string | undefined>();
  const [installs, setInstalls] = useState<Map<string, ContractId<SplitwellInstall>>>(new Map());
  const [splitwellDomainIds, setSplitwellDomainIds] = useState<SplitwellDomains | undefined>();
  const [connectedDomainIds, setConnectedDomainIds] = useState<string[]>([]);

  const primaryPartyId = usePrimaryParty(ledgerApiClient);

  useEffect(() => {
    if (primaryPartyId) {
      updateStatus({ userOnboarded: true, userWalletInstalled: true, partyId: primaryPartyId });
    }
  }, [primaryPartyId, updateStatus]);

  useEffect(() => {
    const fetchProvider = async () => {
      const provider = await splitwellClient.getProviderPartyId(new Empty(), undefined);
      setProvider(provider.getPartyId());
    };
    fetchProvider();
  }, [splitwellClient]);

  useEffect(() => {
    const querySplitwellDomain = async () => {
      console.debug('Querying backend for splitwell domain');
      const domainsResponse = await splitwellClient.getSplitwellDomainIds(new Empty(), undefined);
      const domains: SplitwellDomains = {
        preferred: domainsResponse.getPreferredDomainId(),
        others: domainsResponse.getOtherDomainIdsList(),
      };
      console.debug(`Splitwell domains from provider: ${JSON.stringify(domains)}`);
      setSplitwellDomainIds(domains);
    };

    querySplitwellDomain();
  }, [splitwellClient]);

  useEffect(() => {
    const queryConnectedDomains = async (partyId: string) => {
      const req = new GetConnectedDomainsRequest().setParty(partyId);
      console.debug('Querying for connected domains');
      const domains = await stateSnapshotServiceClient.getConnectedDomains(req);
      const domainIds = domains.getConnectedDomainsList().map(domain => domain.getDomainId());
      setConnectedDomainIds(domainIds);
      console.debug(`Connected domains: ${domainIds}`);
    };
    if (primaryPartyId) {
      queryConnectedDomains(primaryPartyId);
    }
  }, [stateSnapshotServiceClient, primaryPartyId]);

  useEffect(() => {
    const queryInstall = async (
      user: string,
      domainId: string
    ): Promise<ContractId<SplitwellInstall> | undefined> => {
      const installs = await splitwellClient.listSplitwellInstalls(
        new ListSplitwellInstallsRequest().setContext(new SplitwellContext().setUserPartyId(user))
      );
      const install = installs
        .getInstallsList()
        .find(install => install.getDomainId() === domainId);
      if (install) {
        return install.getContractId() as ContractId<SplitwellInstall>;
      }
      return install;
    };
    let effectCancelled = false;
    const setupInstallContractForDomain = async (
      user: string,
      provider: string,
      domainId: string
    ) => {
      console.debug(`Searching for SplitwellInstall on domain ${domainId}`);
      const install = await queryInstall(user, domainId);
      if (effectCancelled) {
        return;
      }
      if (install) {
        console.debug(`SplitwellInstall found for domain ${domainId}`);
        setInstalls(prev => new Map(prev).set(domainId, install));
      } else {
        console.debug(
          `SplitwellInstall not found for domain ${domainId}, creating SplitwellInstallRequest`
        );
        await ledgerApiClient.create(
          [user],
          SplitwellInstallRequest,
          {
            user: user,
            provider: provider,
          },
          domainId
        );
        console.debug('Created SplitwellInstallRequest, waiting for SplitwellInstall');
        setTimeout(() => {
          const maxRetries = 30;
          const querySplitwellInstall = async (n: number) => {
            const install = await queryInstall(user, domainId);
            if (effectCancelled) {
              return;
            }
            if (install) {
              console.debug(`SplitwellInstall found for domain ${domainId}`);
              setInstalls(prev => new Map(prev).set(domainId, install));
            } else if (n > 0) {
              console.debug(
                `SplitwellInstall not found for domain ${domainId}, waiting before retrying`
              );
              setTimeout(() => querySplitwellInstall(n - 1), 500);
            } else {
              throw new Error(
                `SplitwellInstall not found for domain ${domainId} after ${maxRetries} retries, giving up`
              );
            }
          };
          querySplitwellInstall(maxRetries);
          return () => {
            effectCancelled = true;
          };
        }, 500);
      }
    };

    const setupInstallContracts = async () => {
      if (primaryPartyId && provider && splitwellDomainIds && connectedDomainIds) {
        const connectedSplitwellDomainIds = connectedDomainIds.filter(
          d => splitwellDomainIds.preferred === d || splitwellDomainIds.others.includes(d)
        );
        console.debug(`Connected splitwell domain ids: ${connectedSplitwellDomainIds}`);
        for (const domain of connectedSplitwellDomainIds) {
          await setupInstallContractForDomain(primaryPartyId, provider, domain);
        }
      }
    };
    setupInstallContracts();
  }, [
    primaryPartyId,
    provider,
    ledgerApiClient,
    splitwellClient,
    splitwellDomainIds,
    connectedDomainIds,
    stateSnapshotServiceClient,
  ]);

  const pickPreferredInstallDomain = useCallback(() => {
    if (!splitwellDomainIds) {
      return [undefined, undefined] as const;
    }
    const result = collectFirst(
      [splitwellDomainIds.preferred, ...splitwellDomainIds.others],
      id => {
        const install = installs.get(id);
        if (install) {
          return [id, install] as const;
        }
      }
    );
    return result ? result : ([undefined, undefined] as const);
  }, [installs, splitwellDomainIds]);

  const [preferredDomainId, preferredInstallDomain] = pickPreferredInstallDomain();

  if (provider && primaryPartyId && svc && splitwellDomainIds && preferredInstallDomain) {
    return (
      <Container>
        <Stack spacing={3}>
          <GroupSetup
            party={primaryPartyId}
            provider={provider}
            svc={svc}
            domainId={preferredDomainId}
            newGroupInstall={preferredInstallDomain}
            installs={installs}
          />
          <Groups party={primaryPartyId} provider={provider} installs={installs} />
        </Stack>
      </Container>
    );
  } else {
    return <Typography>Loading …</Typography>;
  }
};

export default Home;
