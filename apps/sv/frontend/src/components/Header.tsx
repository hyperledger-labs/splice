import * as React from 'react';
import { NavLink } from 'react-router-dom';

import { Logout } from '@mui/icons-material';
import { Button, Divider, Stack, Toolbar } from '@mui/material';
import Link from '@mui/material/Link';
import Typography from '@mui/material/Typography';

const Header: React.FC = () => {
  const navLinks = [
    { name: 'Debug', path: 'debug' },
    { name: 'FAQs', path: 'faqs' },
  ];

  const applyNavStyle = (isActive: boolean) => {
    const style: React.CSSProperties = {
      color: 'white',
      textDecoration: 'none',
    };

    return isActive ? { ...style, textDecoration: 'underline' } : style;
  };

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
        <Button id="logout-button" onClick={event => {}} color="inherit">
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
