// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import { encode } from 'jwt-simple';
import Ledger from '@daml/ledger';

export type UserManagement = {
  tokenPayload: (loginName: string) => Object,
  primaryParty: (loginName: string, ledger: Ledger) => Promise<string>,
};

export type Insecure = {
  provider: "none",
  userManagement: UserManagement,
  makeToken: (party: string) => string,
};

export type Authentication = Insecure;

export const userManagement: UserManagement = {
  tokenPayload: (loginName: string) =>
  ({
    sub: loginName,
    scope: "daml_ledger_api"
  }),
  primaryParty: async (loginName, ledger: Ledger) => {
    const user = await ledger.getUser();
    if (user.primaryParty !== undefined) {
      return user.primaryParty;
    } else {
      throw new Error(`User '${loginName}' has no primary party`);
    }

  },
};

export const authConfig: Authentication = {
  provider: "none",
  userManagement: userManagement,
  makeToken: (loginName) => {
    const payload = userManagement.tokenPayload(loginName);
    return encode(payload, "secret", "HS256");
  }
};
