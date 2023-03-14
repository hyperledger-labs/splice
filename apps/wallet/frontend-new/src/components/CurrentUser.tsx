import * as React from 'react';

import { useCurrentUser } from '../contexts/CurrentUserContext';

export const CurrentUser: React.FC = () => {
  const currentUser = useCurrentUser();

  if (currentUser.state === 'onboarded') {
    if (currentUser.directoryEntry) {
      return <>{currentUser.directoryEntry}</>;
    } else {
      // show no user details after login if user has no cns entry
      return <></>;
    }
  } else {
    return <>Not Onboarded</>;
  }
};

export default CurrentUser;
