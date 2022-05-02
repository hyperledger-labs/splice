// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import React from "react";
import LoginScreen from "./LoginScreen";
import MainScreen from "./MainScreen";
import DamlLedger from "@daml/react";
import Credentials from "../Credentials";

/**
 * React component for the entry point into the application.
 */
// APP_BEGIN
const App: React.FC = () => {
  const [credentials, setCredentials] = React.useState<
    Credentials | undefined
  >();
  if (credentials) {
    return (
        <DamlLedger
          token={credentials.token}
          party={credentials.party}
          user={credentials.user}>
          <MainScreen
            onLogout={() => {
              setCredentials(undefined);
            }}
          />
        </DamlLedger>
    );
  } else {
    return <LoginScreen onLogin={setCredentials} />;
  }
};
// APP_END

export default App;
