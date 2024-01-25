import { useQuery } from '@tanstack/react-query';
import { PollingStrategy } from 'common-frontend';
import { createContext, useContext, useState } from 'react';

import { Party } from '@daml/types';

import { usePrimaryParty } from '../hooks';
import useLookupCnsEntryByParty from '../hooks/scan-proxy/useLookupCnsEntryByParty';

type CurrentUser =
  | { state: 'onboarded'; primaryParty: Party; cnsEntry: string | undefined }
  | { state: 'not_onboarded' };

const CurrentUserContext: React.Context<CurrentUser> = createContext<CurrentUser>({
  state: 'not_onboarded',
});

export const CurrentUserProvider: React.FC<React.PropsWithChildren> = ({ children }) => {
  const primaryPartyId = usePrimaryParty();

  const [currentUser, setCurrentUser] = useState<CurrentUser>({ state: 'not_onboarded' });
  const { data: cnsEntry } = useLookupCnsEntryByParty(primaryPartyId);
  const cnsEntryName = cnsEntry?.payload.name;

  useQuery({
    refetchInterval: PollingStrategy.NONE,
    queryKey: ['lookupEntryByParty', primaryPartyId, cnsEntry, cnsEntryName],
    queryFn: async () => {
      setCurrentUser({
        state: 'onboarded',
        cnsEntry: cnsEntryName,
        primaryParty: primaryPartyId!,
      });
      return cnsEntry;
    },
    enabled: !!primaryPartyId,
  });

  return <CurrentUserContext.Provider value={currentUser}>{children}</CurrentUserContext.Provider>;
};

export const useCurrentUser: () => CurrentUser = () => {
  return useContext(CurrentUserContext);
};
