import * as gcp from '@pulumi/gcp';
import * as pulumi from '@pulumi/pulumi';
import { Key } from '@pulumi/gcp/serviceaccount';
import { Auth0Client } from 'cn-pulumi-common';
import { infraStack, InfrastructureOutputs } from 'cn-pulumi-common';
import { exit } from 'process';

import { installDocs } from './docs';
import { installClusterIngress } from './ingress';
import { installSplitwell } from './splitwell';
import { installSvNode, SvOnboarding } from './sv';
import { installValidator } from './validator';

/// Toplevel Chart Installs

const isDevNet = process.env.NON_DEVNET === undefined || process.env.NON_DEVNET === '';
if (!isDevNet) {
  console.error('Launching in non-devnet mode');
}

const singleSv = (process.env.SINGLE_SV !== undefined && process.env.SINGLE_SV !== '') || !isDevNet;
if (singleSv) {
  console.error('Launching with a single SV');
}

const withDomainFees =
  (process.env.DOMAIN_FEES !== undefined && process.env.DOMAIN_FEES !== '') || !isDevNet;
if (withDomainFees && !singleSv) {
  console.error(
    `Currently, you cannot enable domain fees with more than one SV, please also set SINGLE_SV to 1 and rerun (${singleSv})`
  );
  exit(1);
}

const SV2_KEY = {
  publicKey:
    'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEsRRntNkOLF2Wh7JxV0rBQPgT+SendIjFLXKUXCrLbVHqomkypHQiZP8OgFMSlByOnr81fqiUt3G36LUpg/fmgA==',
  privateKey:
    'MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgOouqxvUir3C9+2apEdOUC40XrbLTdkbBIK78o2m3lOKhRANCAASxFGe02Q4sXZaHsnFXSsFA+BP5J6d0iMUtcpRcKsttUeqiaTKkdCJk/w6AUxKUHI6evzV+qJS3cbfotSmD9+aA',
};

const SV3_KEY = {
  publicKey:
    'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE0fnbBQiM7UiSNaV6tjPq5lK2buIx5L5nzUuhYWxBk341nFChcbK9pDEO4O6gdxexb/OQP6RhQkDOTDdTCr77CA==',
  privateKey:
    'MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg+8jKTfry5rkitnvy9Dyh5uPVKTzcKu3rrPZyrVW9e/KhRANCAATR+dsFCIztSJI1pXq2M+rmUrZu4jHkvmfNS6FhbEGTfjWcUKFxsr2kMQ7g7qB3F7Fv85A/pGFCQM5MN1MKvvsI',
};

const SV4_KEY = {
  publicKey:
    'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEa76d2OWmkpCQ2dTWsWyhofV3tOGdlkhoCnPpY7BbQhCb0s3laR1vp57JYu/d5Cf+332PF2XrgjC0yBWUqM4syQ==',
  privateKey:
    'MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgE5r1MpzeTmvYjtiVLDASw63VA2pfQm4psX7XlUJU8fGhRANCAARrvp3Y5aaSkJDZ1NaxbKGh9Xe04Z2WSGgKc+ljsFtCEJvSzeVpHW+nnsli793kJ/7ffY8XZeuCMLTIFZSozizJ',
};

const nonDevNetApprovedSvIdentities = [
  {
    name: 'sbi',
    publicKey:
      'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAETM+CeyHvphl9RiPDKL3vVX7F+Qo4fIhJopmgU5B7IzkwSdFic20hFB6tnAuCTU+UBjqZgh8N/h9r+CTrXMPsRg==',
  },
  {
    name: 'intellecteu-canton-da-test',
    publicKey:
      'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEr/iPpyuFu2U914tHyNUDuECT4/AYz9J+nLQRTC8m+95yQ6Y4Oah+Y3u3o5MK4a9D+qkoNGoG6ng0HcjA6TGKmw==',
  },
  {
    name: 'damlHub',
    publicKey:
      'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE5ZIIu7ciMWwgNmciMq8SfgY6eVi1o8feUEztydSg4cn8bF2mcd59XF7zbXRoxNKpLW2gNz6gnv8Ldfn5MkHPbA==',
  },
];

const sv234ApprovedSvIdentities = [
  {
    name: 'Canton-Foundation-2',
    publicKey:
      'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEsRRntNkOLF2Wh7JxV0rBQPgT+SendIjFLXKUXCrLbVHqomkypHQiZP8OgFMSlByOnr81fqiUt3G36LUpg/fmgA==',
  },
  {
    name: 'Canton-Foundation-3',
    publicKey:
      'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE0fnbBQiM7UiSNaV6tjPq5lK2buIx5L5nzUuhYWxBk341nFChcbK9pDEO4O6gdxexb/OQP6RhQkDOTDdTCr77CA==',
  },
  {
    name: 'Canton-Foundation-4',
    publicKey:
      'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEa76d2OWmkpCQ2dTWsWyhofV3tOGdlkhoCnPpY7BbQhCb0s3laR1vp57JYu/d5Cf+332PF2XrgjC0yBWUqM4syQ==',
  },
];

const additionalDevNetApprovedSvIdentities = [
  {
    name: 'DA-Test-Node',
    publicKey:
      'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE7uz+zW1YcPJIl+TKqXv6/dfxcx+3ISVFgP6m2saeQ0l6r2lNW+WLfq+HUMcycxX9t6bUJ5kyEebYyfk9JW18KA==',
  },
  {
    name: 'DA-Helm-Test-Node',
    publicKey:
      'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1eb+JkH2QFRCZedO/P5cq5d2+yfdwP+jE+9w3cT6BqfHxCd/PyA0mmWMePovShmf97HlUajFuN05kZgxvjcPQw==',
  },
  // Jean Safar (CX)
  {
    name: 'jeanSv',
    publicKey:
      'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAESxcNzqTXgL+ocVESayKc4nddN6taa/uFI9ca7GGIJs1Ns3yKFDDu6UCDKH+qlXA0+CcmyW6ytvZ9WPVeD7JsMw==',
  },
];

const approvedSvIdentities = nonDevNetApprovedSvIdentities
  .concat(isDevNet ? additionalDevNetApprovedSvIdentities : [])
  .concat(singleSv ? [] : sv234ApprovedSvIdentities);

function joinViaSv1(
  sv1: pulumi.Resource,
  keys: { publicKey: string; privateKey: string }
): SvOnboarding {
  return {
    type: 'join-with-key',
    sponsorApiUrl: 'http://sv-app.sv-1:5014',
    sponsorRelease: sv1,
    ...keys,
  };
}

const splitwellOnboarding = {
  name: 'splitwell',
  secret: 'splitwellsecret',
  expiresIn: '1h',
};

const validator1Onboarding = {
  name: 'validator1',
  secret: 'validator1secret',
  expiresIn: '1h',
};

function configureGcpBucketKey(): Key {
  const serviceAccountName = `projects/da-cn-devnet/serviceAccounts/da-cn-data-exports@da-cn-devnet.iam.gserviceaccount.com`;

  // Note, creating a new key can fail with a precondition error on an attempt
  // to create keys beyond the tenth.
  const key = new gcp.serviceaccount.Key(`gcp-bucket-${process.env.GCP_CLUSTER_BASENAME}`, {
    serviceAccountId: serviceAccountName,
    publicKeyType: 'TYPE_X509_PEM_FILE',
  });

  return key;
}

export async function installCluster(auth0Client: Auth0Client): Promise<void> {
  const sv1 = await installSvNode(
    auth0Client,
    'sv-1',
    'Canton-Foundation-1',
    'auth0|64529b128448ded6aa68048f',
    { type: 'found-collective' },
    withDomainFees,
    approvedSvIdentities,
    true,
    true,
    [splitwellOnboarding, validator1Onboarding],
    isDevNet,
    isDevNet
      ? undefined
      : {
          projectId: 'da-cn-devnet',
          bucketName: 'da-cn-data-dumps',
          jsonCredentials: configureGcpBucketKey().privateKey,
        }
  );
  if (!singleSv) {
    await installSvNode(
      auth0Client,
      'sv-2',
      'Canton-Foundation-2',
      'auth0|64529b6852dd694167351045',
      joinViaSv1(sv1, SV2_KEY),
      withDomainFees,
      approvedSvIdentities,
      true,
      false
    );
    await installSvNode(
      auth0Client,
      'sv-3',
      'Canton-Foundation-3',
      'auth0|64529bb10c1aee4f2c819218',
      joinViaSv1(sv1, SV3_KEY),
      withDomainFees,
      approvedSvIdentities
    );
    await installSvNode(
      auth0Client,
      'sv-4',
      'Canton-Foundation-4',
      'auth0|64529bc58d30358eacae5611',
      joinViaSv1(sv1, SV4_KEY),
      withDomainFees,
      approvedSvIdentities
    );
  }

  const validator = await installValidator(
    auth0Client,
    sv1,
    'validator1',
    'auth0|63e3d75ff4114d87a2c1e4f5',
    validator1Onboarding,
    withDomainFees,
    isDevNet
  );
  const splitwell = await installSplitwell(
    auth0Client,
    sv1,
    'auth0|63e12e0415ad881ffe914e61',
    splitwellOnboarding,
    withDomainFees
  );

  const docs = installDocs();

  installClusterIngress(
    infraStack.requireOutput(InfrastructureOutputs.INGRESS_NAMESPACE) as pulumi.Output<string>,
    validator,
    splitwell,
    docs
  );
}
