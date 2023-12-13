import * as React from 'react';
import { PartyId } from 'common-frontend';

import Typography from '@mui/material/Typography';

import { useCurrentUser } from '../contexts/CurrentUserContext';

export const CurrentUser: React.FC = () => {
  const currentUser = useCurrentUser();

  if (currentUser.state === 'onboarded') {
    if (currentUser.cnsEntry) {
      return <Typography id="logged-in-user">{currentUser.cnsEntry}</Typography>;
    } else {
      // show no user details after login if user has no cns entry
      return <PartyId partyId={currentUser.primaryParty} id="logged-in-user" />;
    }
  } else {
    return <>Not Onboarded</>;
  }
};

export default CurrentUser;
