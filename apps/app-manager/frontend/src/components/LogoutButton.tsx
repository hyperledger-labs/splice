import * as React from 'react';
import { useUserState } from 'common-frontend';

import { Logout } from '@mui/icons-material';
import { Button, Stack } from '@mui/material';
import Link from '@mui/material/Link';

export const LogoutButton: React.FC = () => {
  const { logout } = useUserState();
  return (
    <Button id="logout-button" onClick={logout} color="inherit">
      <Stack direction="row" alignItems="center">
        <Logout />
        <Link color="inherit" textTransform="none">
          Logout
        </Link>
      </Stack>
    </Button>
  );
};

export default LogoutButton;
