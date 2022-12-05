import { ErrorBoundary } from 'common-frontend';

import { AppBar, Box, Button, Container, CssBaseline, Toolbar, Typography } from '@mui/material';

import './App.css';
import { useUserState } from './contexts/UserContext';
import Home from './views/Home';
import Login from './views/Login';

const App: React.FC = () => {
  const { isAuthenticated, logout, userAccessToken, userId } = useUserState();

  return (
    <ErrorBoundary>
      <Box height="100%" sx={{ display: 'flex', flexDirection: 'column' }}>
        <CssBaseline />
        <AppBar position="static">
          <Toolbar>
            <Typography variant="h6" sx={{ flexGrow: 1 }}>
              Canton Name Service
              {userId && (
                // TODO(#1445) Show primaryPartyId here after dedup with wallet.
                <div id="logged-in-user">{userId}</div>
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
          {isAuthenticated && userId && userAccessToken ? (
            <Home userId={userId} ledgerApiToken={userAccessToken} />
          ) : (
            <Login />
          )}
        </Container>
      </Box>
    </ErrorBoundary>
  );
};

export default App;
