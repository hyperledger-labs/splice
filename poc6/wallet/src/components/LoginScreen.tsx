// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import React, { useCallback } from "react";
import { Button, Form, Grid, Header, Image, Segment } from "semantic-ui-react";
import Credentials from "../Credentials";
import Ledger from "@daml/ledger";
import { authConfig, Insecure } from "../config";

type Props = {
  onLogin: (credentials: Credentials) => void;
};

const LoginScreen: React.FC<Props> = ({ onLogin }) => {
  const login = useCallback(
    async (credentials: Credentials) => {
      onLogin(credentials);
    },
    [onLogin],
  );

  const wrap: (c: JSX.Element) => JSX.Element = component => (
    <Grid textAlign="center" style={{ height: "100vh" }} verticalAlign="middle">
      <Grid.Column style={{ maxWidth: 450 }}>
        <Header
          as="h1"
          textAlign="center"
          size="huge"
          style={{ color: "#223668" }}>
          <Header.Content>
            Create
            <Image
              as="a"
              href="https://www.daml.com/"
              target="_blank"
              src="/daml.svg"
              alt="Daml Logo"
              spaced
              size="small"
              verticalAlign="bottom"
            />
            App
          </Header.Content>
        </Header>
        <Form size="large" className="test-select-login-screen">
          <Segment>{component}</Segment>
        </Form>
      </Grid.Column>
    </Grid>
  );

  const InsecureLogin: React.FC<{ auth: Insecure }> = ({ auth }) => {
    const [username, setUsername] = React.useState("");

    const handleLogin = async (event: React.FormEvent) => {
      event.preventDefault();
      const token = auth.makeToken(username);
      const ledger = new Ledger({ token: token });
      const primaryParty: string = await auth.userManagement
        .primaryParty(username, ledger)
        .catch(error => {
          const errorMsg =
            error instanceof Error ? error.toString() : JSON.stringify(error);
          alert(`Failed to login as '${username}':\n${errorMsg}`);
          throw error;
        });

      await login({
        user: { userId: username, primaryParty: primaryParty },
        party: primaryParty,
        token: auth.makeToken(username),
      });
    };

    return wrap(
      <>
        <Form.Input
          fluid
          placeholder="Username"
          value={username}
          className="test-select-username-field"
          onChange={(e, { value }) => setUsername(value?.toString() ?? "")}
        />
        <Button
          primary
          fluid
          className="test-select-login-button"
          onClick={handleLogin}>
          Log in
        </Button>
      </>,
    );
  };

  return (
    <InsecureLogin auth={authConfig} />
  );
};

export default LoginScreen;
