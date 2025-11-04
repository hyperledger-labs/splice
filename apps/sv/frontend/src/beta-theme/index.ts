// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import 'common-typeface-termina/index.css';
import 'common-typeface-lato/index.css';

import { createTheme, TypographyStyle } from '@mui/material';

import { generateHslPalette, generateRemValue, stylePillButton } from './utils';

// // TS module augmentation to add custom theme vars for storing our custom color values
declare module '@mui/material/styles' {
  interface Theme {
    fonts: {
      sansSerif: TypographyStyle;
      monospace: TypographyStyle;
    };
  }

  interface ThemeOptions {
    fonts?: {
      sansSerif: TypographyStyle;
      monospace: TypographyStyle;
    };
  }

  interface Palette {
    colors: {
      neutral: Record<string, string>;
      primary: Record<string, string>;
      secondary: string;
      mainnet: string;
      testnet: string;
      devnet: string;
      scratchnet: string;
    };
  }
  // allow configuration using `createTheme`
  interface PaletteOptions {
    colors?: {
      neutral?: Record<string, string>;
      primary?: Record<string, string>;
      secondary?: string;
      mainnet: string;
      testnet: string;
      devnet: string;
      scratchnet: string;
    };
  }
}

declare module '@mui/material/Button' {
  interface ButtonPropsVariantOverrides {
    pill: true;
  }
}

declare module '@mui/material/Typography' {
  interface TypographyPropsVariantOverrides {
    pill: true;
  }
}

declare module '@mui/material/TableCell' {
  interface TableCellPropsVariantOverrides {
    party: true;
  }
}

let betaTheme = createTheme({
  /**
   * We can add our own custom key/values to the palette, but
   * keep in mind we need to augment the interfaces with whatever we want to add
   *
   *  - https://mui.com/material-ui/customization/theming/#custom-variables
   */
  palette: {
    mode: 'dark',
    colors: {
      neutral: generateHslPalette(0, 0, [0, 10, 15, 25, 30, 40, 50, 60, 70, 80]),
      primary: generateHslPalette(195, 96, [79, 89]),
      secondary: '#F3FF97',
      mainnet: '#F8FDCD',
      testnet: '#C8F1FE',
      devnet: '#C6B2FF',
      scratchnet: '#FFFFFF',
    },
  },
});

betaTheme = createTheme(betaTheme, {
  /**
   * See the following for built-in palette names:
   *
   *  - https://mui.com/system/palette/
   *  - https://mui.com/material-ui/customization/default-theme/
   */
  palette: {
    primary: {
      main: betaTheme.palette.colors.primary[79],
      light: betaTheme.palette.colors.primary[89],
    },
    secondary: {
      main: '#F3FF97',
    },
    warning: {
      main: '#FD8575',
    },
    error: {
      main: '#FD8575',
    },
    success: {
      main: '#33C200',
    },
    background: {
      default: betaTheme.palette.colors.neutral[10],
    },
    text: {
      light: '#E2E2E2',
    },
  },
});

betaTheme = createTheme(betaTheme, {
  fonts: {
    sansSerif: {
      fontFamily: '"Inter", sans-serif',
      fontWeight: 400,
    },
    monospace: {
      fontFamily: '"Termina", monospace',
      fontWeight: 500,
    },
  },
});

// Based on the Major Third type scale: https://typescale.com/?size=16&scale=1.250&text=A%20Visual%20Type%20Scale&font=Lato&fontweight=400&bodyfont=body_font_default&bodyfontweight=400&lineheight=1.75&backgroundcolor=%23ffffff&fontcolor=%23000000&preview=false
const TYPE_SCALE = 1.25;

betaTheme = createTheme(betaTheme, {
  typography: {
    // I couldn't figure out a less verbose way to reliably set the font family... specifying it at
    // `theme.typography.fontFamily`, as indicated by the documentation, didn't actually do anything
    h1: {
      ...betaTheme.fonts.sansSerif,
      fontSize: generateRemValue(5, TYPE_SCALE),
    },
    h2: {
      ...betaTheme.fonts.sansSerif,
      fontSize: generateRemValue(4, TYPE_SCALE),
    },
    h3: {
      ...betaTheme.fonts.sansSerif,
      fontSize: generateRemValue(3, TYPE_SCALE),
    },
    h4: {
      ...betaTheme.fonts.sansSerif,
      fontSize: generateRemValue(2, TYPE_SCALE),
    },
    h5: {
      ...betaTheme.fonts.sansSerif,
      fontSize: generateRemValue(1, TYPE_SCALE),
    },
    h6: {
      ...betaTheme.fonts.sansSerif,
      fontSize: generateRemValue(0, TYPE_SCALE),
    },
    subtitle1: betaTheme.fonts.sansSerif,
    subtitle2: betaTheme.fonts.sansSerif,
    body1: {
      ...betaTheme.fonts.sansSerif,
      fontSize: generateRemValue(0, TYPE_SCALE),
    },
    body2: {
      ...betaTheme.fonts.sansSerif,
      fontSize: '0.875rem',
    },
    button: betaTheme.fonts.sansSerif,
    caption: {
      ...betaTheme.fonts.sansSerif,
      fontSize: generateRemValue(-1, TYPE_SCALE),
    },
    overline: betaTheme.fonts.sansSerif,
  },
});

betaTheme = createTheme(betaTheme, {
  /**
   * Style overrides allow us to customize the default look of all components of a given type
   *    - https://mui.com/material-ui/customization/theme-components/#global-style-overrides
   *
   * For components that take "variant" props (e.g., buttons), we can also define custom variants
   *    - https://mui.com/material-ui/customization/theme-components/#creating-new-component-variants
   */
  components: {
    MuiButton: {
      defaultProps: {
        disableRipple: true,
      },
      variants: [
        {
          props: { variant: 'primary-button' },
          style: {
            color: 'black',
            textTransform: 'none',
            '.MuiButton-startIcon': {
              color: betaTheme.palette.primary.main,
              marginRight: betaTheme.spacing(0.5),
            },
          },
        },
        {
          props: { color: 'secondary' },
          style: {
            color: 'white',
            textTransform: 'none',
            '.MuiButton-startIcon': {
              color: betaTheme.palette.secondary.main,
              marginRight: betaTheme.spacing(0.5),
            },
          },
        },
        // primary pill button
        stylePillButton(
          {
            bgColor: betaTheme.palette.primary.main,
            bgHoverColor: betaTheme.palette.primary.light,
            bgDisableColor: betaTheme.palette.colors.neutral[25],
            borderFocus: `2px solid ${betaTheme.palette.primary.main}`,
            textColor: 'black',
          },
          { textTransform: 'none', fontSize: '16px' }
        ),
        // secondary pill button (border style)
        stylePillButton({
          props: { color: 'secondary' },
          bgDisableColor: betaTheme.palette.colors.neutral[25],
          border: `1px solid ${betaTheme.palette.secondary.main}`,
          borderFocus: `2px solid ${betaTheme.palette.secondary.main}`,
          textColor: 'white',
          textHoverColor: betaTheme.palette.secondary.main,
        }),
        // warning pill button (border style)
        stylePillButton({
          props: { color: 'warning' },
          bgDisableColor: betaTheme.palette.colors.neutral[25],
          border: `1px solid ${betaTheme.palette.warning.main}`,
          borderFocus: `2px solid ${betaTheme.palette.warning.main}`,
          textColor: 'white',
          textHoverColor: betaTheme.palette.warning.main,
        }),
        {
          props: { variant: 'outlined', color: 'secondary', size: 'small' },
          style: {
            color: 'white',
          },
        },
      ],
    },
    MuiLink: {
      variants: [
        // primary pill button-like link
        stylePillButton(
          {
            bgColor: betaTheme.palette.primary.main,
            bgHoverColor: betaTheme.palette.primary.light,
            bgDisableColor: betaTheme.palette.colors.neutral[25],
            borderFocus: `2px solid ${betaTheme.palette.primary.main}`,
            textColor: 'black',
          },
          {
            padding: 16,
          }
        ),
      ],
    },
    MuiChip: {
      styleOverrides: {
        root: {
          backgroundColor: betaTheme.palette.primary.main,
        },
      },
    },
    MuiCard: {
      styleOverrides: {
        root: {
          marginBottom: '4px',
          backgroundColor: betaTheme.palette.colors.neutral[20],
          backgroundImage: 'none',
        },
      },
    },
    MuiTableCell: {
      styleOverrides: {
        root: {
          borderColor: betaTheme.palette.colors.neutral[15],
        },
        head: {
          ...betaTheme.fonts.monospace,
          fontSize: '0.8125rem',
          fontWeight: 700,
          textTransform: 'uppercase',
        },
        variants: {
          props: { variant: 'party' },
          style: {
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          },
        },
      },
    },
    MuiTab: {
      defaultProps: {
        disableRipple: true,
      },
      styleOverrides: {
        root: {
          textTransform: 'capitalize',
          fontWeight: 'bold',
          paddingLeft: '0px',
          paddingRight: '0px',
          marginRight: betaTheme.spacing(4),
          color: 'white',
          '&.Mui-selected': {
            color: 'white',
          },
        },
      },
    },
    MuiTabs: {
      styleOverrides: {
        root: {
          '.MuiTabs-indicator': {
            backgroundColor: betaTheme.palette.secondary.main,
            borderBottomSize: '4px',
          },
        },
      },
    },
    MuiInputBase: {
      styleOverrides: {
        root: {
          '.MuiOutlinedInput-input': {
            backgroundColor: betaTheme.palette.colors.neutral[10],
            webkitBoxShadow: betaTheme.palette.colors.neutral[10],
          },
        },
      },
    },
  },
});

export { betaTheme };
