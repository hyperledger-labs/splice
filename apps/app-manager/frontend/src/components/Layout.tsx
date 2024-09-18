import * as React from 'react';
import { Header } from 'common-frontend';

import { Box, Divider, Stack } from '@mui/material';
import Container from '@mui/material/Container';

import LogoutButton from './LogoutButton';

interface LayoutProps {
  children: React.ReactNode;
}

const Layout: React.FC<LayoutProps> = (props: LayoutProps) => {
  return (
    <Box bgcolor="colors.neutral.20" display="flex" flexDirection="column" minHeight="100vh">
      <Container maxWidth="xl">
        <Header title="App Manager">
          <Stack direction="row" alignItems="center" spacing={1}>
            <Divider orientation="vertical" variant="middle" flexItem />
            <LogoutButton />
          </Stack>
        </Header>
      </Container>

      <Box bgcolor="colors.neutral.15" sx={{ flex: 1 }}>
        <Container maxWidth="lg">{props.children}</Container>
      </Box>
    </Box>
  );
};
export default Layout;
