// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import { NavLink } from 'react-router-dom';

import { Stack, Toolbar } from '@mui/material';
import Typography, { TypographyOwnProps } from '@mui/material/Typography';

interface HeaderProps extends React.PropsWithChildren {
  title: string;
  titleVariant?: TypographyOwnProps['variant'];
  navLinks?: { name: string; path: string }[];
  noBorder?: boolean;
}

const Header: React.FC<HeaderProps> = ({ children, title, titleVariant, navLinks, noBorder }) => {
  const applyNavStyle = (isActive: boolean) => {
    const style: React.CSSProperties = {
      color: 'white',
      textDecoration: 'none',
    };

    return isActive ? { ...style, textDecoration: 'underline' } : style;
  };

  return (
    <>
      <Toolbar
        sx={{
          borderBottom: noBorder ? 0 : 1,
          borderColor: 'divider',
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          padding: { xs: 0, sm: 0, md: 0, lg: 0, xl: 0 },
        }}
      >
        <Typography
          id="app-title"
          textTransform="uppercase"
          variant={titleVariant || 'h5'}
          fontFamily={theme => theme.fonts.monospace.fontFamily}
          fontWeight={theme => theme.fonts.monospace.fontWeight}
        >
          {title}
        </Typography>

        {navLinks && (
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
        )}
        {children}
      </Toolbar>
    </>
  );
};

export default Header;
