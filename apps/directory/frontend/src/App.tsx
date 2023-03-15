import { Login, ErrorBoundary, useUserState, PartyId } from 'common-frontend';

import { Box, Button, Container, Toolbar, Typography } from '@mui/material';

import { config } from './utils/config';
import Home from './views/Home';

const App: React.FC = () => {
  const { isAuthenticated, logout, primaryPartyId } = useUserState();

  return (
    <ErrorBoundary>
      <Box height="100%" sx={{ display: 'flex', flexDirection: 'column' }}>
        <Box bgcolor="colors.neutral.20">
          <Toolbar>
            <Typography
              variant="h3"
              textTransform="uppercase"
              fontFamily={theme => theme.fonts.monospace.fontFamily}
              fontWeight={theme => theme.fonts.monospace.fontWeight}
              sx={{ flexGrow: 1 }}
            >
              Canton Name Service
            </Typography>
            {primaryPartyId && (
              // Using a DirectoryEntry here seems a bit circular
              <div id="logged-in-user">
                <PartyId partyId={primaryPartyId} />
              </div>
            )}

            {isAuthenticated && (
              <Button color="inherit" onClick={logout}>
                Log Out
              </Button>
            )}
          </Toolbar>
        </Box>
        <Container style={{ height: '100%', flex: '1' }}>
          {isAuthenticated ? (
            <Home />
          ) : (
            <Login
              title="Canton Name Service Log In"
              authConfig={config.auth}
              testAuthConfig={config.testAuth}
            />
          )}
        </Container>
      </Box>
    </ErrorBoundary>
  );
};

export default App;
