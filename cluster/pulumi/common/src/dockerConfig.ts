// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import * as k8s from '@pulumi/kubernetes';
import * as pulumi from '@pulumi/pulumi';
import { getSecretVersionOutput } from '@pulumi/gcp/secretmanager/getSecretVersion';
import { Secret } from '@pulumi/kubernetes/core/v1';

type Credentials = {
  username: string;
  password: string;
};

// A singleton because we use this in all Helm charts, and don't want to fetch from the secret manager multiple times
export class DockerConfig {
  private static instance: DockerConfig;

  private jsonConfig: pulumi.Output<string>;

  private constructor() {
    const jfrogCreds = DockerConfig.fetchCredentialsFromSecret('artifactory-keys');
    const googleCreds = DockerConfig.fetchGoogleCredentialsFromSecret(
      'us-central1-artifact-reader-key'
    );
    this.jsonConfig = pulumi.all([jfrogCreds, googleCreds]).apply(([jfrog, google]) => {
      const artifactoryAuth = DockerConfig.toAuthField(jfrog);
      const googleAuth = DockerConfig.toAuthField(google);
      const conf = Buffer.from(
        JSON.stringify({
          auths: {
            'digitalasset-canton-enterprise-docker.jfrog.io': {
              auth: artifactoryAuth,
              username: jfrog.username,
              password: jfrog.password,
            },
            'digitalasset-canton-network-docker.jfrog.io': {
              auth: artifactoryAuth,
              username: jfrog.username,
              password: jfrog.password,
            },
            'digitalasset-canton-network-docker-dev.jfrog.io': {
              auth: artifactoryAuth,
              username: jfrog.username,
              password: jfrog.password,
            },
            'us-central1-docker.pkg.dev': {
              auth: googleAuth,
              username: google.username,
              password: google.password,
            },
          },
        })
      );
      return conf.toString();
    });
  }

  public static getConfig(): DockerConfig {
    if (!DockerConfig.instance) {
      DockerConfig.instance = new DockerConfig();
    }
    return DockerConfig.instance;
  }

  public createDockerClientConfigSecret(
    namespaceName: string | pulumi.Input<string>,
    secretName: string = 'docker-client-config',
    dependsOn: pulumi.Resource[] = []
  ): Secret {
    return new k8s.core.v1.Secret(
      secretName,
      {
        metadata: {
          namespace: namespaceName,
          name: secretName,
        },
        stringData: {
          'config.json': this.jsonConfig,
        },
      },
      {
        dependsOn,
      }
    );
  }

  public createImagePullSecret(
    namespaceName: string,
    secretName: string = 'docker-reg-cred',
    dependsOn: pulumi.Resource[] = []
  ): Secret {
    return new k8s.core.v1.Secret(
      `${namespaceName}-${secretName}`,
      {
        metadata: {
          name: secretName,
          namespace: namespaceName,
        },
        type: 'kubernetes.io/dockerconfigjson',
        stringData: {
          '.dockerconfigjson': this.jsonConfig,
        },
      },
      {
        dependsOn,
      }
    );
  }

  private static toAuthField(credentials: Credentials): string {
    return Buffer.from(`${credentials.username}:${credentials.password}`).toString('base64');
  }

  private static fetchCredentialsFromSecret(secretName: string): pulumi.Output<Credentials> {
    const temp = getSecretVersionOutput({ secret: secretName });
    return temp.apply(k => {
      const secretData = k.secretData;
      const parsed = JSON.parse(secretData);
      return {
        username: String(parsed.username),
        password: String(parsed.password),
      };
    });
  }

  private static fetchGoogleCredentialsFromSecret(secretName: string): pulumi.Output<Credentials> {
    const temp = getSecretVersionOutput({ secret: secretName });
    return temp.apply(k => {
      const secretData = k.secretData;
      return {
        username: '_json_key',
        password: secretData,
      };
    });
  }
}
