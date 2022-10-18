import { useState } from 'react';

import { TabPanel, TabContext } from '@mui/lab';
import { Box, Tab, Tabs } from '@mui/material';

import AppMultiPaymentRequests from './AppMultiPaymentRequests';
import AppPaymentRequests from './AppPaymentRequests';
import Coins from './Coins';
import PaymentChannels from './PaymentChannels';

const Home: React.FC<{ userId: string }> = ({ userId }) => {
  const [tabValue, setTabValue] = useState<string>('coins');

  return (
    <>
      <Box sx={{ borderBottom: 1, borderColor: 'divider', marginBottom: 5 }}>
        <Tabs value={tabValue} onChange={(_, value) => setTabValue(value)}>
          <Tab label="Coins" value="coins" />
          <Tab label="Payment channels" value="payment_channels" />
          <Tab
            label="App payment requests"
            value="app_payment_requests"
            id="payment-requests-tab"
          />
        </Tabs>
      </Box>
      <TabContext value={tabValue}>
        <TabPanel value="coins">
          <Coins userId={userId} />
        </TabPanel>
        <TabPanel value="payment_channels">
          <PaymentChannels userId={userId} />
        </TabPanel>
        <TabPanel value="app_payment_requests">
          <AppPaymentRequests userId={userId} />
          <AppMultiPaymentRequests userId={userId} />
        </TabPanel>
      </TabContext>
    </>
  );
};

export default Home;
