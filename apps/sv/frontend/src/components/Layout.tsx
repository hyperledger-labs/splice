import * as React from 'react';

import { Box } from '@mui/material';
import Container from '@mui/material/Container';

import Header from './Header';

interface LayoutProps {
  children: React.ReactNode;
}

const Layout: React.FC<LayoutProps> = (props: LayoutProps) => {
  return (
    <Box bgcolor="colors.neutral.20" display="flex" flexDirection="column" minHeight="100vh">
      <Container maxWidth="xl">
        <Header />
      </Container>

      <Box bgcolor="colors.neutral.25" sx={{ flex: 1 }}>
        <Container maxWidth="lg">{props.children}</Container>
      </Box>
    </Box>
  );
};
export default Layout;
