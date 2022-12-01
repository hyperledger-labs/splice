import { LedgerApiClient, buildLedgerApiClientInterface } from 'common-frontend';

import { DirectoryEntry, DirectoryInstall } from '@daml.js/directory/lib/CN/Directory';

class DirectoryLedgerApiClient extends LedgerApiClient {
  async queryDirectoryInstall(user: string, provider: string) {
    const response = await this.queryAcs(user, DirectoryInstall);
    return response.find(c => c.payload.user === user && c.payload.provider === provider);
  }

  async queryOwnedDirectoryEntries(user: string, provider: string) {
    // We query through our own participant here so we get filtering to entries visible only to us.
    // Alternatively, we could add a filtered API on the provider.
    const response = await this.queryAcs(user, DirectoryEntry);
    return response.filter(c => c.payload.user === user && c.payload.provider === provider);
  }
}

export const [DirectoryLedgerApiClientProvider, useDirectoryLedgerApiClient] =
  buildLedgerApiClientInterface(DirectoryLedgerApiClient);
