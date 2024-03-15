import { Output } from '@pulumi/pulumi';
import { isDevNet } from 'cn-pulumi-common';
import { cometbftRetainBlocks } from 'cn-pulumi-common/src/deployment_config';

export interface StaticSvConfig {
  nodeName: string;
  onboardingName: string;
  validatorWalletUser: string;
  auth0ValidatorAppName: string;
  cometBft: StaticCometBftConfig;
  onboardingPollingInterval?: string;
}

export type StaticCometBftConfig = {
  privateKey: Output<string> | string;
  validator: {
    keyAddress: Output<string> | string;
    privateKey: Output<string> | string;
    publicKey: Output<string> | string;
  };
  nodeIndex: number;
  retainBlocks: number;
  id: Output<string> | string;
};

export interface StaticCometBftConfigWithNodeName extends StaticCometBftConfig {
  nodeName: string;
}

const svconfs: StaticSvConfig[] = [
  {
    nodeName: 'sv-1',
    onboardingName: 'Digital-Asset-2',
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
    onboardingName: 'Digital-Asset-Eng-2',
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
    onboardingName: 'Digital-Asset-Eng-3',
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
    onboardingName: 'Digital-Asset-Eng-4',
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
    onboardingName: 'Digital-Asset-Eng-5',
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
    onboardingName: 'Digital-Asset-Eng-6',
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
    onboardingName: 'Digital-Asset-Eng-7',
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
    onboardingName: 'Digital-Asset-Eng-8',
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
    onboardingName: 'Digital-Asset-Eng-9',
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
  {
    nodeName: 'sv-10',
    onboardingName: 'Digital-Asset-Eng-10',
    auth0ValidatorAppName: 'sv10_validator',
    validatorWalletUser: 'auth0|65e0a7854c76b74b28b8477f',
    cometBft: {
      nodeIndex: 10,
      id: 'cc8e74ca2c3c66820266dc6cca759f5368dd9924',
      privateKey:
        'rrf2SKLa1gU3xGvoueeYmc6B8EESs7tyVljWb2vflDP1om8gCSYpaIobMtjfnh8DedY8vrZfZh7lvnZuYYS0wg==',
      retainBlocks: cometbftRetainBlocks,
      validator: {
        keyAddress: 'E71220096CC607150D56914B9175A5D4B70B00E6',
        privateKey:
          'SwqjEqjukgRqu6DxaQQKJHxiEyU7t/zitQvzRW4ZTUQOSCp7gCwDGK7BwJNoz1Bo3vvcCmWkzvEDtLpS35q2rg==',
        publicKey: '9aJvIAkmKWiKGzLY354fA3nWPL62X2Ye5b52bmGEtMI=',
      },
    },
  },
  {
    nodeName: 'sv-11',
    onboardingName: 'Digital-Asset-Eng-11',
    auth0ValidatorAppName: 'sv11_validator',
    validatorWalletUser: 'auth0|65e0a78976d9757e3f14846b',
    cometBft: {
      nodeIndex: 11,
      id: '21f60b2667972ff943fbd46ea9ca82ddf0905948',
      privateKey:
        'vLbQjq8pJ288nOxle6fq52UXZYuGNLX1nC0Q3FxQP1xewjkKy8l+Qcg9bOumTix7gFRRZw2WbFxs1sjtCKSocw==',
      retainBlocks: cometbftRetainBlocks,
      validator: {
        keyAddress: '14474E591E9C75E5FCA4520B36CD4963E2FBAA2C',
        privateKey:
          'rBmpzn2EwWPH/Zc+jakmQODl94RoLcI6fbNVeeUTifJxI0im8qlRV2ekOHubTOFldGFfgxNGVl5gOfCXjuYusw==',
        publicKey: 'cSNIpvKpUVdnpDh7m0zhZXRhX4MTRlZeYDnwl47mLrM=',
      },
    },
  },
  {
    nodeName: 'sv-12',
    onboardingName: 'Digital-Asset-Eng-12',
    auth0ValidatorAppName: 'sv12_validator',
    validatorWalletUser: 'auth0|65e0a78d68c39e5cc0351ed2',
    cometBft: {
      nodeIndex: 12,
      id: '817bb28c471d7a8631e701c914fc7e9a65e74be2',
      privateKey:
        '9IBlZ1pjb+sfHEJY9uH/o00/54exhZ9XFl3iXTFdldSlhmmjUf0pR3YIL6hkjN2X7RI0TVBlVotP22H9YhIGhw==',
      retainBlocks: cometbftRetainBlocks,
      validator: {
        keyAddress: '1E5F191A4E2C4DD5026A3B26F1F66A809D5D4E8C',
        privateKey:
          '+zX5POXRv4X63O3t2n/T/7dQlhc+vtXzQPcIc29ukn8XRy5oislTg19Oa2mpU/rLqhF4mHZZK4H1hYMq1JHbBQ==',
        publicKey: 'F0cuaIrJU4NfTmtpqVP6y6oReJh2WSuB9YWDKtSR2wU=',
      },
    },
  },
  {
    nodeName: 'sv-13',
    onboardingName: 'Digital-Asset-Eng-13',
    auth0ValidatorAppName: 'sv13_validator',
    validatorWalletUser: 'auth0|65e0a7914c76b74b28b84793',
    cometBft: {
      nodeIndex: 13,
      id: '254dd73eb4cee23d439c2f2e706ccdbeac52f06c',
      privateKey:
        'fNyXPdBLI7skpoyhS57REEqt0FyQTZP8JRJnmtVUV9I4URL3gRORNcAn71kznx53UAsLtpRFLdSij2FvA/WwsA==',
      retainBlocks: cometbftRetainBlocks,
      validator: {
        keyAddress: 'CFF50F6EFD5DFDD8DAD7A468D5FB5DA2D43CF281',
        privateKey:
          'BhcNZ9TNV4EbKw+GxN2oKMJzN2CswkXc5n2ozect+MTqW1Y3EdGvA+P2o9gHcdBYviGl4VMKMdJbab7usJhYDA==',
        publicKey: '6ltWNxHRrwPj9qPYB3HQWL4hpeFTCjHSW2m+7rCYWAw=',
      },
    },
  },
  {
    nodeName: 'sv-14',
    onboardingName: 'Digital-Asset-Eng-14',
    auth0ValidatorAppName: 'sv14_validator',
    validatorWalletUser: 'auth0|65e0a795aa7a40df0cc65ace',
    cometBft: {
      nodeIndex: 14,
      id: '9de44f8ddac42901c094371e867bb0db60ab03b8',
      privateKey:
        'MRXAxpwuEH63FfwPSiC17OM73owF5XZ7dmGFLeENcFKxuZHblOOAkhMy8S93KpZRKDd+HRzXsfkbyhyr7DrWdg==',
      retainBlocks: cometbftRetainBlocks,
      validator: {
        keyAddress: 'F691F4CA91B972A6B291C09BADA9970AAAC86C84',
        privateKey:
          '/8MSzUv70iyE5M7g6B1CN4swFNwxOQGjveEgAKcJMX6s/h5Y7hZly1StATlkMUeRfEJZ9lrq1zwHyouavNXuAQ==',
        publicKey: 'rP4eWO4WZctUrQE5ZDFHkXxCWfZa6tc8B8qLmrzV7gE=',
      },
    },
  },
  {
    nodeName: 'sv-15',
    onboardingName: 'Digital-Asset-Eng-15',
    auth0ValidatorAppName: 'sv15_validator',
    validatorWalletUser: 'auth0|65e0a7994c76b74b28b8479c',
    cometBft: {
      nodeIndex: 15,
      id: '7a5f4f9ee97ec24bb4a1a6ed22ec3676805fa494',
      privateKey:
        'sRaCaZcBcHtzLtX5rAtd0MNvVLBjY2K1yBck1QFm3oZ+d9IlKS+FAxgMWzQTaUNwTVhaIm39vDdx+E6K1w/0yQ==',
      retainBlocks: cometbftRetainBlocks,
      validator: {
        keyAddress: 'AAE830BF1289910D20E646D9B69561D9E0F965EA',
        privateKey:
          'VYGQIwjykHZoW/Twa45aYBZu53ubMUmVxn94hlmCp42IDExO8It79g7hw/35GhxPHDv6USwbGyKI9zaEyrqguw==',
        publicKey: 'iAxMTvCLe/YO4cP9+RocTxw7+lEsGxsiiPc2hMq6oLs=',
      },
    },
  },
  {
    nodeName: 'sv-16',
    onboardingName: 'Digital-Asset-Eng-16',
    auth0ValidatorAppName: 'sv16_validator',
    validatorWalletUser: 'auth0|65e0a79de124e5c43dcb6a19',
    cometBft: {
      nodeIndex: 16,
      id: '9831eeb365f221034e70f27c5073ee0857bdc945',
      privateKey:
        'WsUfOOFAmym9HVI5qvYH98HAyHW4Eu/L88ri5ms5JPvDbo22puGKBAGye1GVEUQaDKYNRVLOEGDlO/jNZBGz6g==',
      retainBlocks: cometbftRetainBlocks,
      validator: {
        keyAddress: '0C77119A80F4B4305729D49EC76FC7D4C0576229',
        privateKey:
          'A3kveZBU2CspwiATgCe+aRc7oG7TxaRsX0Sre7nDyM75w2mUVEubuAFL+EiwmtZVwO18agJDZhvgcLO8Gph1nQ==',
        publicKey: '+cNplFRLm7gBS/hIsJrWVcDtfGoCQ2Yb4HCzvBqYdZ0=',
      },
    },
  },
];

export default svconfs;
