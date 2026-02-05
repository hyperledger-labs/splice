// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

export const shortenPartyId = (partyId: string): string => {
  const elements = partyId.split('::');
  if (elements.length === 2) {
    return `${elements[0]}::${elements[1].slice(0, 10)}…`;
  }
  return partyId;
};
