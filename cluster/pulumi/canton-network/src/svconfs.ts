import { Output } from '@pulumi/pulumi';
import { DomainMigrationIndex, cometbftRetainBlocks, isDevNet } from 'cn-pulumi-common';

export interface StaticSvConfig {
  nodeName: string;
  onboardingName: string;
  validatorWalletUser: string;
  auth0ValidatorAppName: string;
  cometBft: StaticCometBftConfig;
}

export type StaticCometBftConfig = {
  privateKey: Output<string> | string;
  validator: {
    keyAddress: Output<string> | string;
    privateKey: Output<string> | string;
    publicKey: Output<string> | string;
  };
  nodeIndex: DomainMigrationIndex;
  retainBlocks: number;
  id: Output<string> | string;
};

export interface StaticCometBftConfigWithNodeName extends StaticCometBftConfig {
  nodeName: string;
}

const svconfs: StaticSvConfig[] = [
  {
    nodeName: 'sv-1',
    onboardingName: isDevNet ? 'Canton-Foundation-1' : 'Canton-Foundation',
    auth0ValidatorAppName: 'sv1_validator',
    validatorWalletUser: isDevNet
      ? 'auth0|64afbc0956a97fe9577249d7'
      : 'auth0|64529b128448ded6aa68048f',
    cometBft: {
      nodeIndex: 1,
      id: '5af57aa83abcec085c949323ed8538108757be9c',
      privateKey:
        '/7L74Bs18740fTPdEL04BeO2Gs+1lzEeCjAiB1DYcysmLnU1FAkg/Ho9XsOiIp4U/KT/YNrtIi/A0prm/Ew3eQ==',
      retainBlocks: cometbftRetainBlocks,
      validator: {
        keyAddress: '8A931AB5F957B8331BDEF3A0A081BD9F017A777F',
        privateKey:
          'npgiYbG0Iaslb/JHzliAg5BkfYMOaK3tCdKWvvO4FjCCmTBzVYK20vxkBMEg9YgFEKtvR5XgnAwKeNFrnpEQ/A==',
        publicKey: 'gpkwc1WCttL8ZATBIPWIBRCrb0eV4JwMCnjRa56REPw=',
      },
    },
  },
  {
    nodeName: 'sv-2',
    onboardingName: 'Canton-Foundation-2',
    auth0ValidatorAppName: 'sv2_validator',
    validatorWalletUser: 'auth0|64afbc353bbc7ca776e27bf4',
    cometBft: {
      nodeIndex: 2,
      id: 'c36b3bbd969d993ba0b4809d1f587a3a341f22c1',
      privateKey:
        '1Je33z2g+Dj2UWLqnsO+xwUwbalIS0LLcYAoj+fYuEE2le4kJjJ0h+L7FfVg+3mbgvrikdke91I2X5C2frj0Eg==',
      retainBlocks: cometbftRetainBlocks,
      validator: {
        keyAddress: '04A57312179F1E0C93B868779EE4C7FAC41666F0',
        privateKey:
          '58rlJ+0WYnpfgn26k4TjBAibToi+irz8C2x4XEVBWIgFVIz3+48YtTuUmPvZJTDVrbrXPYvpjLZcouGlS9vGoQ==',
        publicKey: 'BVSM9/uPGLU7lJj72SUw1a261z2L6Yy2XKLhpUvbxqE=',
      },
    },
  },
  {
    nodeName: 'sv-3',
    onboardingName: 'Canton-Foundation-3',
    auth0ValidatorAppName: 'sv3_validator',
    validatorWalletUser: 'auth0|64afbc4431b562edb8995da6',
    cometBft: {
      nodeIndex: 3,
      id: '0d8e87c54d199e85548ccec123c9d92966ec458c',
      privateKey:
        'DdbW/buPo4TXxW+/cvQxp5Lh1BZyH5GYHGoU0uTUQA/S1oZ32DDu1+CZhtrZhqEFMuxPlXVXvyvXsZLBdCkgdQ==',
      retainBlocks: cometbftRetainBlocks,
      validator: {
        keyAddress: 'FFF137F42421B0257CDC8B2E41F777B81A081E80',
        privateKey:
          'y+vvdb5lKRMplJ3mWe5NMdN293Nm6BSzDJFV0txGGKt3GbifUxE/8a5ISQkjBt0HjNVwYB5pyiEUo21soryhEA==',
        publicKey: 'dxm4n1MRP/GuSEkJIwbdB4zVcGAeacohFKNtbKK8oRA=',
      },
    },
  },
  {
    nodeName: 'sv-4',
    onboardingName: 'Canton-Foundation-4',
    auth0ValidatorAppName: 'sv4_validator',
    validatorWalletUser: 'auth0|64afbc720e20777e46fff490',
    cometBft: {
      nodeIndex: 4,
      id: 'ee738517c030b42c3ff626d9f80b41dfc4b1a3b8',
      privateKey:
        'xMB8gnYbacyZqU94cgwJBK2OJO3DffO12uHgeieotVj/Q9LbZEwLue9GnG8+G5GNRDgX8z75txr/Z541Uqyb3A==',
      retainBlocks: cometbftRetainBlocks,
      validator: {
        keyAddress: 'DE36D23DE022948A11200ABB9EE07F049D17D903',
        privateKey:
          'nUSWALRjErKf+/SVI93LU5venfN/YvsLaVdLYhbYPFPa6Zl1RL3trpVRcwayAon9VtBtqfFZoVTErVCKaGUSOg==',
        publicKey: '2umZdUS97a6VUXMGsgKJ/VbQbanxWaFUxK1QimhlEjo=',
      },
    },
  },
  {
    nodeName: 'sv-5',
    onboardingName: 'Canton-Foundation-5',
    auth0ValidatorAppName: 'sv5_validator',
    validatorWalletUser: 'auth0|65c15c482a18b1ef030ba290',
    cometBft: {
      nodeIndex: 5,
      id: '205437468610305149d131bbf9bf1f47658d861b',
      privateKey:
        'tlDwOSTLO1tAz9qfnTTUFFwVzJmtI7qn37rsoRbHPMRW2YWZ+53OXReOPSFzG/4pUCk4zxd1GJgb2ePFiDNlQQ==',
      retainBlocks: cometbftRetainBlocks,
      validator: {
        keyAddress: '1A6C9E60AFD830682CBEF5496F6E5515B20B0F2D',
        privateKey:
          'kQtt4AjOpT4Nz79PZIP1eJ7o7o09R7AIzRnbLV1VN9rKTKnOZMl6LnD4OI0zrucJ9vToUykeWJhTsVenENgmBg==',
        publicKey: 'ykypzmTJei5w+DiNM67nCfb06FMpHliYU7FXpxDYJgY=',
      },
    },
  },
  {
    nodeName: 'sv-6',
    onboardingName: 'Canton-Foundation-6',
    auth0ValidatorAppName: 'sv6_validator',
    validatorWalletUser: 'auth0|65c26e959666d60d24fe523a',
    cometBft: {
      nodeIndex: 6,
      id: '60c21490e82d6a1fb0c35b9a04e4f64ae00ce5c0',
      privateKey:
        'CtgCJPhaF9SzNIl0jMP18BIM9DseWY4Dlc8fRgBh2ygswJBWtSCcJT4YQBXrjQlpQMMl8gaYg8sK2+bbMw0LMw==',
      retainBlocks: cometbftRetainBlocks,
      validator: {
        keyAddress: 'DC41F08916D8C41B931F9037E6F2571C58D0E01A',
        privateKey:
          'RlLIq1RqLGHh2a9XG3BtXRstOmw4avIFA3WzVJaGsXjAAUSM7xfipoPp1EzVNK9aNf5IxegsSogiOqpZSLURMg==',
        publicKey: 'wAFEjO8X4qaD6dRM1TSvWjX+SMXoLEqIIjqqWUi1ETI=',
      },
    },
  },
  {
    nodeName: 'sv-7',
    onboardingName: 'Canton-Foundation-7',
    auth0ValidatorAppName: 'sv7_validator',
    validatorWalletUser: 'auth0|65c26e9d45eaef5c191a167e',
    cometBft: {
      nodeIndex: 7,
      id: '81f3b7d26ae796d369fbf42481a65c6265b41e8c',
      privateKey:
        'VQBBlN8oYVR4vxr8OjF/Q2YTKJVBCpa/048YDp8Gn2PtugPDFiJVxcpZ2ozkQsQ+CXl4IVEmhpLshRO/QVWz9g==',
      retainBlocks: cometbftRetainBlocks,
      validator: {
        keyAddress: '66FA9399FF2E7AF2517E7CE2EDCA11F51C573F61',
        privateKey:
          'Vj9Z0txjW+4MM8AcnDezkQw+tLeJ0jKRRsj5xX9mvmxpZZJGAgAlJzek9rPz3O7bITKpG4pjlJjwS+m3+SW3vg==',
        publicKey: 'aWWSRgIAJSc3pPaz89zu2yEyqRuKY5SY8Evpt/klt74=',
      },
    },
  },
  {
    nodeName: 'sv-8',
    onboardingName: 'Canton-Foundation-8',
    auth0ValidatorAppName: 'sv8_validator',
    validatorWalletUser: 'auth0|65c26ea449ef8564a0ec9297',
    cometBft: {
      nodeIndex: 8,
      id: '404371a5f62773ca07925555c9fbb6287861947c',
      privateKey:
        '8/AM1nf0Hf5nJre5cBTfLhCmUG6YfFNCBaXrBJNi0pYSEzTcRJ5LNZKgQpP5a9aVYzQmCVVlaV2nfOJKzTWmFA==',
      retainBlocks: cometbftRetainBlocks,
      validator: {
        keyAddress: '5E35AE8D464FA92525BCC408C7827A943BDF4900',
        privateKey:
          '0IuhH2UTzhzYPbF3AQaSwp4WaHOYo/65Jr7lvxeAfqn9b9t8YL1LRV4q3HkgP0cUngk7x1Judj/ATwn7IRI7Fg==',
        publicKey: '/W/bfGC9S0VeKtx5ID9HFJ4JO8dSbnY/wE8J+yESOxY=',
      },
    },
  },
  {
    nodeName: 'sv-9',
    onboardingName: 'Canton-Foundation-9',
    auth0ValidatorAppName: 'sv9_validator',
    validatorWalletUser: 'auth0|65c26eac58f141b4ca1dc5da',
    cometBft: {
      nodeIndex: 9,
      id: 'aeee969d0efb0784ea36b9ad743a2e5964828325',
      privateKey:
        'a5d4ZHxQkazrjZH3R6TVZYIFkBoWflC/RmkQCmhhRInWm7Ikj9wBEvdKJuPEWv78MSmOLi3pJuYchkKkbwcvrA==',
      retainBlocks: cometbftRetainBlocks,
      validator: {
        keyAddress: '06070D2FD47073BE1635C3DEB862A88669906847',
        privateKey:
          'yDch3FNnIHFJPGlUpOYecAtxlwAi3QBi7dELVC/ON3muR3ekkf6TCsO32Lxvcj1zVGrOeewV7k+54TeAHmdmDw==',
        publicKey: 'rkd3pJH+kwrDt9i8b3I9c1RqznnsFe5PueE3gB5nZg8=',
      },
    },
  },
];

export default svconfs;
