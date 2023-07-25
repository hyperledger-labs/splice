import * as React from 'react';

import { Divider, Stack, Toolbar } from '@mui/material';
import Typography from '@mui/material/Typography';

import { LogoutButton } from './LogoutButton';

const Header: React.FC = () => {
  return (
    <Toolbar
      sx={{
        borderBottom: 1,
        borderColor: 'divider',
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
      }}
    >
      <Typography
        variant="h5"
        textTransform="uppercase"
        fontFamily={theme => theme.fonts.monospace.fontFamily}
        fontWeight={theme => theme.fonts.monospace.fontWeight}
      >
        App Manager
      </Typography>
      <Stack direction="row" alignItems="center" spacing={1}>
        <Divider orientation="vertical" variant="middle" flexItem />
        <LogoutButton />
      </Stack>
    </Toolbar>
  );
};

export default Header;
