import * as pulumi from '@pulumi/pulumi';
import { Auth0Client } from 'cn-pulumi-common';
import { infraStack, InfrastructureOutputs } from 'cn-pulumi-common';
import { exit } from 'process';

import { BackupConfig, installGcpBucket, GcpBucketConfig, BootstrappingDumpConfig } from './backup';
import { installDocs } from './docs';
import { configureForwardAll } from './gateway';
import { installClusterIngress } from './ingress';
import { Postgres } from './postgres';
import { installSplitwell } from './splitwell';
import { installSvNode, SvOnboarding } from './sv';
import { installValidator1 } from './validator1';

/// Toplevel Chart Installs

const isDevNet = process.env.NON_DEVNET === undefined || process.env.NON_DEVNET === '';
if (!isDevNet) {
  console.error('Launching in non-devnet mode');
}

const approveSvRunbook =
  process.env.APPROVE_SV_RUNBOOK !== undefined && process.env.APPROVE_SV_RUNBOOK !== '';
if (approveSvRunbook) {
  console.error('Approving SV used in SV runbook');
}

const doubleSv = (process.env.DOUBLE_SV !== undefined && process.env.DOUBLE_SV !== '') || !isDevNet;
if (doubleSv) {
  console.error('Launching with a double SV');
}

const withDomainFees =
  (process.env.DOMAIN_FEES !== undefined && process.env.DOMAIN_FEES !== '') || !isDevNet;
if (withDomainFees && !doubleSv) {
  console.error(
    `Currently, you cannot enable domain fees with more than one SV, please also set DOUBLE_SV to 1 and rerun (${doubleSv})`
  );
  exit(1);
}

type BootstrapCliConfig = {
  cluster: string;
  date: string;
};

const bootstrappingConfig: BootstrapCliConfig = process.env.BOOTSTRAPPING_CONFIG
  ? JSON.parse(process.env.BOOTSTRAPPING_CONFIG)
  : undefined;

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
    name: isDevNet ? 'Canton-Foundation-2' : 'Digital-Asset',
    publicKey:
      'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEsRRntNkOLF2Wh7JxV0rBQPgT+SendIjFLXKUXCrLbVHqomkypHQiZP8OgFMSlByOnr81fqiUt3G36LUpg/fmgA==',
  },
];

const sv34ApprovedSvIdentities = [
  {
    name: 'damlHub',
    publicKey:
      'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE5ZIIu7ciMWwgNmciMq8SfgY6eVi1o8feUEztydSg4cn8bF2mcd59XF7zbXRoxNKpLW2gNz6gnv8Ldfn5MkHPbA==',
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

const svRunbookApprovedSvIdentities = [
  {
    name: 'DA-Helm-Test-Node',
    publicKey:
      'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1eb+JkH2QFRCZedO/P5cq5d2+yfdwP+jE+9w3cT6BqfHxCd/PyA0mmWMePovShmf97HlUajFuN05kZgxvjcPQw==',
  },
];

const additionalDevNetApprovedSvIdentities = [
  {
    name: 'DA-Test-Node',
    publicKey:
      'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE7uz+zW1YcPJIl+TKqXv6/dfxcx+3ISVFgP6m2saeQ0l6r2lNW+WLfq+HUMcycxX9t6bUJ5kyEebYyfk9JW18KA==',
  },
  // Jean Safar (CX)
  {
    name: 'jeanSv',
    publicKey:
      'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAESxcNzqTXgL+ocVESayKc4nddN6taa/uFI9ca7GGIJs1Ns3yKFDDu6UCDKH+qlXA0+CcmyW6ytvZ9WPVeD7JsMw==',
  },
  // Ben Minton (DA support)
  {
    name: 'dasc-test-sv-01',
    publicKey:
      'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEU/AYexo+CmqZxcNEnKgaNoJmdqhHP2nElBymaE4HREPQ0VgObBRNNMlgU/WfUUKElmn2u2D/b/wNtKKrtTA99A==',
  },
];

const approvedSvIdentities = nonDevNetApprovedSvIdentities
  .concat(isDevNet || approveSvRunbook ? svRunbookApprovedSvIdentities : [])
  .concat(isDevNet ? additionalDevNetApprovedSvIdentities : [])
  .concat(doubleSv ? [] : sv34ApprovedSvIdentities);

function joinViaSv1(
  sv1: pulumi.Resource,
  keys: { publicKey: string; privateKey: string },
  sequencerDatabase: Postgres
): SvOnboarding {
  return {
    type: 'join-with-key',
    sponsorApiUrl: 'http://sv-app.sv-1:5014',
    sponsorRelease: sv1,
    sequencerDatabase,
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

const backupBucketConfig: GcpBucketConfig = {
  projectId: 'da-cn-devnet',
  bucketName: 'da-cn-data-dumps',
};

let backupConfig: BackupConfig | undefined;
let bootstrappingDumpConfig: BootstrappingDumpConfig | undefined;

if (!isDevNet || bootstrappingConfig) {
  const backupBucket = installGcpBucket(backupBucketConfig);
  if (!isDevNet) {
    backupConfig = { backupInterval: '10m', bucket: backupBucket };
  }
  if (bootstrappingConfig) {
    const end = new Date(Date.parse(bootstrappingConfig.date));
    // We search within an interval of 1 hour. Given that we usually backups every 10min, this gives us
    // more than enough of a threshold to make sure each node has one backup in that interval
    // while also having sufficiently few backups that the bucket query is fast.
    const start = new Date(end.valueOf() - 60 * 60 * 1000);
    bootstrappingDumpConfig = {
      bucket: backupBucket,
      cluster: bootstrappingConfig.cluster,
      start,
      end,
    };
  }
}

export async function installCluster(auth0Client: Auth0Client): Promise<void> {
  configureForwardAll(
    infraStack.requireOutput(InfrastructureOutputs.INGRESS_NAMESPACE) as pulumi.Output<string>
  );

  const { svApp: sv1, postgresDatabase: postgresDB1 } = installSvNode({
    auth0Client,
    nodename: 'sv-1',
    onboardingName: isDevNet ? 'Canton-Foundation-1' : 'Canton-Foundation',
    validatorWalletUser: 'auth0|64529b128448ded6aa68048f',
    onboarding: { type: 'found-collective' },
    withDomainFees,
    approvedSvIdentities,
    withScan: true,
    withDirectoryBackend: true,
    expectedValidatorOnboardings: [splitwellOnboarding, validator1Onboarding],
    isDevNet,
    backupConfig,
    bootstrappingDumpConfig,
    withDomainNode: true,
    auth0ValidatorAppName: 'sv1_validator',
  });

  installSvNode({
    auth0Client,
    nodename: 'sv-2',
    onboardingName: isDevNet ? 'Canton-Foundation-2' : 'Digital-Asset',
    validatorWalletUser: 'auth0|64529b6852dd694167351045',
    onboarding: joinViaSv1(sv1, SV2_KEY, postgresDB1),
    withDomainFees,
    approvedSvIdentities,
    withScan: true,
    withDirectoryBackend: false,
    expectedValidatorOnboardings: [],
    isDevNet,
    backupConfig: backupConfig,
    withDomainNode: isDevNet,
    auth0ValidatorAppName: 'sv2_validator',
    bootstrappingDumpConfig,
  });
  if (!doubleSv) {
    installSvNode({
      auth0Client,
      nodename: 'sv-3',
      onboardingName: 'Canton-Foundation-3',
      validatorWalletUser: 'auth0|64529bb10c1aee4f2c819218',
      onboarding: joinViaSv1(sv1, SV3_KEY, postgresDB1),
      withDomainFees,
      approvedSvIdentities,
      withScan: false,
      withDirectoryBackend: false,
      expectedValidatorOnboardings: [],
      isDevNet,
      backupConfig,
      withDomainNode: isDevNet,
      auth0ValidatorAppName: 'sv3_validator',
      bootstrappingDumpConfig,
    });

    installSvNode({
      auth0Client,
      nodename: 'sv-4',
      onboardingName: 'Canton-Foundation-4',
      validatorWalletUser: 'auth0|64529bc58d30358eacae5611',
      onboarding: joinViaSv1(sv1, SV4_KEY, postgresDB1),
      withDomainFees,
      approvedSvIdentities,
      withScan: false,
      withDirectoryBackend: false,
      expectedValidatorOnboardings: [],
      isDevNet,
      backupConfig,
      withDomainNode: isDevNet,
      auth0ValidatorAppName: 'sv4_validator',
      bootstrappingDumpConfig,
    });
  }

  const validator = await installValidator1(
    auth0Client,
    sv1,
    'validator1',
    validator1Onboarding,
    withDomainFees,
    isDevNet,
    'auth0|63e3d75ff4114d87a2c1e4f5',
    backupConfig,
    bootstrappingDumpConfig
  );

  const splitwell = await installSplitwell(
    auth0Client,
    sv1,
    'auth0|63e12e0415ad881ffe914e61',
    splitwellOnboarding,
    withDomainFees,
    backupConfig,
    bootstrappingDumpConfig
  );

  const docs = installDocs();

  installClusterIngress(
    infraStack.requireOutput(InfrastructureOutputs.INGRESS_NAMESPACE) as pulumi.Output<string>,
    validator,
    splitwell,
    docs
  );
}
