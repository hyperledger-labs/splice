import { Login, ErrorBoundary, useUserState, PartyId } from 'common-frontend';

import { AppBar, Box, Button, Container, CssBaseline, Toolbar, Typography } from '@mui/material';

import './App.css';
import { config } from './utils/config';
import Home from './views/Home';

const App: React.FC = () => {
  const { isAuthenticated, logout, primaryPartyId } = useUserState();

  return (
    <ErrorBoundary>
      <Box height="100%" sx={{ display: 'flex', flexDirection: 'column' }}>
        <CssBaseline />
        <AppBar position="static">
          <Toolbar>
            <Typography variant="h6" sx={{ flexGrow: 1 }}>
              Canton Name Service
              {primaryPartyId && (
                // Using a DirectoryEntry here seems a bit circular
                <div id="logged-in-user">
                  <PartyId partyId={primaryPartyId} />
                </div>
              )}
            </Typography>
            {isAuthenticated && (
              <Button color="inherit" onClick={logout}>
                Log Out
              </Button>
            )}
          </Toolbar>
        </AppBar>
        <Container style={{ height: '100%', flex: '1' }}>
          {isAuthenticated ? (
            <Home />
          ) : (
            <Login
              title={'Canton Name Service Log In'}
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
