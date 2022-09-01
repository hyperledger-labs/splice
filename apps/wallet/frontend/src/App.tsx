import { useState } from 'react';
import './App.css';

import { AppBar, Box, Container, CssBaseline, Tab, Tabs, Toolbar, Typography } from '@mui/material';
import { TabPanel, TabContext } from '@mui/lab';
import { WalletClientProvider } from './WalletServiceContext';
import Coins from './Coins';
import PaymentChannels from './PaymentChannels';
import AppPaymentRequests from './AppPaymentRequests';

function App() {

  const [tabValue, setTabValue] = useState<string>("coins");
  return (
    <WalletClientProvider url={process.env.REACT_APP_GRPC_URL || "http://localhost:8080"}>
      <Box>
        <CssBaseline />
        <AppBar position='static'>
          <Toolbar>
            <Typography variant="h6">
              CC Wallet
            </Typography>
          </Toolbar>
        </AppBar>
        <Container>
          <Box sx={{ borderBottom: 1, borderColor: 'divider', marginBottom: 5 }}>
            <Tabs value={tabValue} onChange={(_, value) => setTabValue(value)}>
              <Tab label="Coins" value="coins" />
              <Tab label="Payment channels" value="payment_channels" />
              <Tab label="App payment requests" value="app_payment_requests" />
            </Tabs>
          </Box>
          <TabContext value={tabValue}>
            <TabPanel value="coins">
              <Coins />
            </TabPanel>
            <TabPanel value="payment_channels">
              <PaymentChannels />
            </TabPanel>
            <TabPanel value="app_payment_requests">
              <AppPaymentRequests />
            </TabPanel>
          </TabContext>
        </Container>
      </Box>
    </WalletClientProvider>
  );
}

export default App;
