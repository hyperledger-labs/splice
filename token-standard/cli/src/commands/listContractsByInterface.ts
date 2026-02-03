// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import {
  createLedgerApiClient,
  ensureInterfaceViewIsPresent,
  filtersByParty,
} from "../apis/ledger-api-utils";
import { InterfaceId } from "../constants";
import { CommandOptions } from "../token-standard-cli";
import { JsGetActiveContractsResponse } from "@lfdecentralizedtrust/canton-json-api-v2-openapi";

export async function listContractsByInterface(
  interfaceId: InterfaceId,
  partyId: string,
  opts: CommandOptions,
): Promise<void> {
  try {
    const ledgerClient = createLedgerApiClient(opts);
    const ledgerEnd = await ledgerClient.getV2StateLedgerEnd();
    const responses = await ledgerClient.postV2StateActiveContracts({
      filter: {
        filtersByParty: filtersByParty(partyId, [interfaceId], false),
      },
      verbose: false,
      activeAtOffset: ledgerEnd.offset!,
    });
    const prettyContracts = responses.map((response) =>
      toPrettyContract(interfaceId, response),
    );
    console.log(JSON.stringify(prettyContracts, null, 2));
  } catch (err) {
    console.error(
      `Failed to list contracts of interface ${interfaceId.toString()}`,
      err,
    );
    throw err;
  }
}

// Make them nicer to show by excluding stuff useless to users such as the createdEventBlob
export function toPrettyContract(
  interfaceId: InterfaceId,
  response: JsGetActiveContractsResponse,
): {
  contractId: string;
  payload: any;
} {
  const createdEvent = response.contractEntry!.JsActiveContract.createdEvent;
  return {
    contractId: createdEvent.contractId,
    payload: ensureInterfaceViewIsPresent(createdEvent, interfaceId).viewValue,
  };
}
