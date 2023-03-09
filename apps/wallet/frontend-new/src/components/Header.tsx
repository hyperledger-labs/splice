import * as React from 'react';
import { useUserState } from 'common-frontend';
import { NavLink } from 'react-router-dom';

import { Logout } from '@mui/icons-material';
import { Button, Divider, Stack, Toolbar } from '@mui/material';
import Link from '@mui/material/Link';
import Typography from '@mui/material/Typography';

interface HeaderProps {
  currentUser: string;
}

const Header: React.FC<HeaderProps> = props => {
  const navLinks = [
    { name: 'Transactions', path: 'transactions' },
    { name: 'Transfer', path: 'transfer' },
    { name: 'Subscriptions', path: 'subscriptions' },
    { name: 'FAQs', path: 'faqs' },
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
      >
        Canton Coin Wallet
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
        <Typography id="logged-in-user">{props.currentUser}</Typography>
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
