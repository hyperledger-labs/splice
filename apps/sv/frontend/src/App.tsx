import { ErrorBoundary } from 'common-frontend';

import { Box, Toolbar, Typography } from '@mui/material';

import Home from './views/Home';

const App: React.FC = () => {
  return (
    <ErrorBoundary>
      <Box height="100%" sx={{ display: 'flex', flexDirection: 'column' }}>
        <Box bgcolor="colors.neutral.20">
          <Toolbar>
            <Typography
              variant="h4"
              textTransform="uppercase"
              fontFamily={theme => theme.fonts.monospace.fontFamily}
              fontWeight={theme => theme.fonts.monospace.fontWeight}
              sx={{ flexGrow: 1 }}
              id="app-title"
            >
              SV Operations
            </Typography>
          </Toolbar>
        </Box>
        <Home />
      </Box>
    </ErrorBoundary>
  );
};

export default App;
