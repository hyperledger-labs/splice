import { useDirectoryClient } from 'common-frontend';
import { createContext, useContext, useEffect, useState } from 'react';

import { Party } from '@daml/types';

import { usePrimaryParty } from '../hooks';

type CurrentUser =
  | { state: 'onboarded'; primaryParty: Party; directoryEntry: string | undefined }
  | { state: 'not_onboarded' };

const CurrentUserContext: React.Context<CurrentUser> = createContext<CurrentUser>({
  state: 'not_onboarded',
});

export const CurrentUserProvider: React.FC<React.PropsWithChildren> = ({ children }) => {
  const { lookupEntryByParty } = useDirectoryClient();
  const primaryPartyId = usePrimaryParty();

  const [currentUser, setCurrentUser] = useState<CurrentUser>({ state: 'not_onboarded' });

  useEffect(() => {
    const fetchEntry = async () => {
      if (primaryPartyId) {
        const directoryEntry = await lookupEntryByParty(primaryPartyId!);
        setCurrentUser({
          state: 'onboarded',
          directoryEntry: directoryEntry?.name,
          primaryParty: primaryPartyId,
        });
      }
    };
    fetchEntry();
  }, [primaryPartyId, lookupEntryByParty]);

  return <CurrentUserContext.Provider value={currentUser}>{children}</CurrentUserContext.Provider>;
};

export const useCurrentUser: () => CurrentUser = () => {
  return useContext(CurrentUserContext);
};
