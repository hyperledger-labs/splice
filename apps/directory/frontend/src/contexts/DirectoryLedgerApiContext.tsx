import { LedgerApiClient, buildLedgerApiClientInterface } from 'common-frontend';

import {
  DirectoryEntry,
  DirectoryEntryOffer,
  DirectoryInstall,
} from '@daml.js/directory/lib/CN/Directory';
import {
  AppPaymentRequest,
  DeliveryOffer,
} from '@daml.js/wallet-payments-0.1.0/lib/CN/Wallet/Payment';
import { ContractId } from '@daml/types';

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

  async queryDirectoryEntryOffer(user: string, provider: string, name: string) {
    const response = await this.queryAcs(user, DirectoryEntryOffer);
    return response.find(
      c =>
        c.payload.entryRequest.user === user &&
        c.payload.entryRequest.provider === provider &&
        c.payload.entryRequest.name === name
    );
  }

  async queryDirectoryEntryPaymentRequest(user: string, offer: ContractId<DirectoryEntryOffer>) {
    const response = await this.queryAcs(user, AppPaymentRequest);
    // @ts-ignore
    let offerAsDeliveryOffer: ContractId<DeliveryOffer> = offer as ContractId<DeliveryOffer>;
    return response.find(c => c.payload.deliveryOffer === offerAsDeliveryOffer);
  }
}

export const [DirectoryLedgerApiClientProvider, useDirectoryLedgerApiClient] =
  buildLedgerApiClientInterface(DirectoryLedgerApiClient);
