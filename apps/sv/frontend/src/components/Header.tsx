import * as React from 'react';
import { useUserState } from 'common-frontend';
import { NavLink } from 'react-router-dom';

import { Logout } from '@mui/icons-material';
import { Button, Divider, Stack, Toolbar, Typography } from '@mui/material';
import Link from '@mui/material/Link';

const Header: React.FC = () => {
  const navLinks = [
    { name: 'Information', path: 'svc' },
    { name: 'Validator Onboarding', path: 'validator-onboarding' },
  ];

  const applyNavStyle = (isActive: boolean) => {
    const style: React.CSSProperties = {
      color: 'white',
      textDecoration: 'none',
    };

    return isActive ? { ...style, textDecoration: 'underline' } : style;
  };
  const { logout } = useUserState();
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
        id="app-title"
      >
        Sv Operations
      </Typography>
      <Stack direction="row" spacing={4} alignItems="center">
        {navLinks.map((navLink, index) => (
          <NavLink
            key={index}
            id={`navlink-${navLink.path}`}
            to={navLink.path}
            style={p => applyNavStyle(p.isActive)}
          >
            {navLink.name}
          </NavLink>
        ))}
      </Stack>
      <Stack direction="row" alignItems="center" spacing={1}>
        <Divider orientation="vertical" variant="middle" flexItem />
        <Button id="logout-button" onClick={logout} color="inherit">
          <Stack direction="row" alignItems="center">
            <Logout />
            <Link color="inherit" textTransform="none">
              Logout
            </Link>
          </Stack>
        </Button>
      </Stack>
    </Toolbar>
  );
};

export default Header;
