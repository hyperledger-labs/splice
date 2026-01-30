// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as React from 'react';
import { NavLink } from 'react-router';

import { Warning } from '@mui/icons-material';
import { Badge, Box, Stack, Toolbar } from '@mui/material';
import Typography, { TypographyOwnProps } from '@mui/material/Typography';

interface HeaderProps extends React.PropsWithChildren {
  title: string;
  titleVariant?: TypographyOwnProps['variant'];
  navLinks?: { name: string; path: string; badgeCount?: number; hasAlert?: boolean }[];
  noBorder?: boolean;
}

const Header: React.FC<HeaderProps> = ({ children, title, titleVariant, navLinks, noBorder }) => {
  const applyNavStyle = (isActive: boolean): React.CSSProperties => ({
    color: 'white',
    textDecoration: isActive ? 'underline' : 'none',
    whiteSpace: 'nowrap',
  });

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
          sx={{ flexShrink: 0, textWrap: 'balance', marginRight: 2 }}
        >
          {title}
        </Typography>

        {navLinks && (
          <Stack
            direction="row"
            spacing={3}
            alignItems="center"
            justifyContent="space-evenly"
            sx={{ flex: 1 }}
          >
            {navLinks.map((navLink, index) => (
              <Box key={`nav-link-${index}`}>
                <NavLink
                  key={index}
                  id={`navlink-${navLink.path}`}
                  data-testid={`navlink-${navLink.path}`}
                  to={navLink.path}
                  style={p => applyNavStyle(p.isActive)}
                >
                  {navLink.name}

                  {navLink.badgeCount ? (
                    <Badge
                      key={`nav-badge-${index}`}
                      id={`nav-badge-${navLink.path}-count`}
                      color="error"
                      badgeContent={navLink.badgeCount}
                      sx={{ marginLeft: 2 }}
                    />
                  ) : navLink.hasAlert ? (
                    <Badge
                      key={`nav-alert-badge-${index}`}
                      id={`nav-badge-${navLink.path}-alert`}
                      badgeContent={
                        <Warning fontSize="small" color="secondary" sx={{ marginLeft: 3 }} />
                      }
                    />
                  ) : (
                    <></>
                  )}
                </NavLink>
              </Box>
            ))}
          </Stack>
        )}
        {children}
      </Toolbar>
    </>
  );
};

export default Header;
