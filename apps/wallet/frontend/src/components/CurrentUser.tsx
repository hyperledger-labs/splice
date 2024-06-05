import * as React from 'react';
import { AnsEntryDisplay } from 'common-frontend';

import { useCurrentUser } from '../contexts/CurrentUserContext';

export const CurrentUser: React.FC = () => {
  const currentUser = useCurrentUser();

  if (currentUser.state === 'onboarded') {
    return (
      <AnsEntryDisplay
        partyId={currentUser.primaryParty}
        ansEntryName={currentUser.ansEntry}
        id="logged-in-user"
      />
    );
  } else {
    return <>Not Onboarded</>;
  }
};

export default CurrentUser;
