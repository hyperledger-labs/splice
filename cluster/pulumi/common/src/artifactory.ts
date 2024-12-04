import * as pulumi from '@pulumi/pulumi';
import { getSecretVersionOutput } from '@pulumi/gcp/secretmanager/getSecretVersion';

type ArtifactoryKeys = {
  username: string;
  password: string;
};

function fetchArtifactoryCredsFromSecret(): pulumi.Output<ArtifactoryKeys> {
  const temp = getSecretVersionOutput({ secret: 'artifactory-keys' });
  return temp.apply(k => {
    const secretData = k.secretData;
    const parsed = JSON.parse(secretData);
    return {
      username: String(parsed.username),
      password: String(parsed.password),
    };
  });
}

// A singleton because we use this in all Helm charts, and don't want to fetch from the secret manager multiple times
export class ArtifactoryCreds {
  private static instance: ArtifactoryCreds;
  creds: pulumi.Output<ArtifactoryKeys>;
  private constructor() {
    this.creds = fetchArtifactoryCredsFromSecret();
  }

  public static getCreds(): ArtifactoryCreds {
    if (!ArtifactoryCreds.instance) {
      ArtifactoryCreds.instance = new ArtifactoryCreds();
    }
    return ArtifactoryCreds.instance;
  }
}
