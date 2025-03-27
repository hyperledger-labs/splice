// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import { PollingStrategy } from '@lfdecentralizedtrust/splice-common-frontend-utils';
import { useQuery } from '@tanstack/react-query';
import { createContext, useContext, useState } from 'react';

import { Party } from '@daml/types';

import { usePrimaryParty } from '../hooks';
import useLookupAnsEntryByParty from '../hooks/scan-proxy/useLookupAnsEntryByParty';

type CurrentUser =
  | { state: 'onboarded'; primaryParty: Party; ansEntry: string | undefined }
  | { state: 'not_onboarded' };

const CurrentUserContext: React.Context<CurrentUser> = createContext<CurrentUser>({
  state: 'not_onboarded',
});

export const CurrentUserProvider: React.FC<React.PropsWithChildren> = ({ children }) => {
  const primaryPartyId = usePrimaryParty();

  const [currentUser, setCurrentUser] = useState<CurrentUser>({ state: 'not_onboarded' });
  const { data: ansEntry } = useLookupAnsEntryByParty(primaryPartyId);
  const ansEntryName = ansEntry?.name;

  useQuery({
    refetchInterval: PollingStrategy.NONE,
    queryKey: ['lookupEntryByParty', primaryPartyId, ansEntry, ansEntryName],
    queryFn: async () => {
      setCurrentUser({
        state: 'onboarded',
        ansEntry: ansEntryName,
        primaryParty: primaryPartyId!,
      });
      return ansEntry;
    },
    enabled: !!primaryPartyId,
  });

  return <CurrentUserContext.Provider value={currentUser}>{children}</CurrentUserContext.Provider>;
};

export const useCurrentUser: () => CurrentUser = () => {
  return useContext(CurrentUserContext);
};
