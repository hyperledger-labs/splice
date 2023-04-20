import * as React from 'react';
import { NavLink } from 'react-router-dom';

import { Divider, Stack, Toolbar } from '@mui/material';
import Typography from '@mui/material/Typography';

import CurrentUser from './CurrentUser';
import FeaturedAppRight from './FeaturedAppRight';
import { LogoutButton } from './LogoutButton';

const Header: React.FC = () => {
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
        <CurrentUser />
        <FeaturedAppRight />
        <Divider orientation="vertical" variant="middle" flexItem />
        <LogoutButton />
      </Stack>
    </Toolbar>
  );
};

export default Header;
