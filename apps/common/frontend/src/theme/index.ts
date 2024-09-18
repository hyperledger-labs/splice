// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
import 'common-typeface-termina/index.css';

import { createTheme, TypographyStyle } from '@mui/material';

import { generateHslPalette, generateRemValue, stylePillButton } from './utils';

// TS module augmentation to add custom theme vars for storing our custom color values
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
    };
  }
  // allow configuration using `createTheme`
  interface PaletteOptions {
    colors?: {
      neutral?: Record<string, string>;
      primary?: Record<string, string>;
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

let theme = createTheme({
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
    },
  },
});

theme = createTheme(theme, {
  /**
   * See the following for built-in palette names:
   *
   *  - https://mui.com/system/palette/
   *  - https://mui.com/material-ui/customization/default-theme/
   */
  palette: {
    primary: {
      main: theme.palette.colors.primary[79],
      light: theme.palette.colors.primary[89],
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
      default: theme.palette.colors.neutral[10],
    },
  },
});

theme = createTheme(theme, {
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

theme = createTheme(theme, {
  typography: {
    // I couldn't figure out a less verbose way to reliably set the font family... specifying it at
    // `theme.typography.fontFamily`, as indicated by the documentation, didn't actually do anything
    h1: {
      ...theme.fonts.sansSerif,
      fontSize: generateRemValue(5, TYPE_SCALE),
    },
    h2: {
      ...theme.fonts.sansSerif,
      fontSize: generateRemValue(4, TYPE_SCALE),
    },
    h3: {
      ...theme.fonts.sansSerif,
      fontSize: generateRemValue(3, TYPE_SCALE),
    },
    h4: {
      ...theme.fonts.sansSerif,
      fontSize: generateRemValue(2, TYPE_SCALE),
    },
    h5: {
      ...theme.fonts.sansSerif,
      fontSize: generateRemValue(1, TYPE_SCALE),
    },
    h6: {
      ...theme.fonts.sansSerif,
      fontSize: generateRemValue(0, TYPE_SCALE),
    },
    subtitle1: theme.fonts.sansSerif,
    subtitle2: theme.fonts.sansSerif,
    body1: {
      ...theme.fonts.sansSerif,
      fontSize: generateRemValue(0, TYPE_SCALE),
    },
    body2: {
      ...theme.fonts.sansSerif,
      fontSize: '0.875rem',
    },
    button: theme.fonts.sansSerif,
    caption: {
      ...theme.fonts.sansSerif,
      fontSize: generateRemValue(-1, TYPE_SCALE),
    },
    overline: theme.fonts.sansSerif,
  },
});

theme = createTheme(theme, {
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
              color: theme.palette.primary.main,
              marginRight: theme.spacing(0.5),
            },
          },
        },
        {
          props: { color: 'secondary' },
          style: {
            color: 'white',
            textTransform: 'none',
            '.MuiButton-startIcon': {
              color: theme.palette.secondary.main,
              marginRight: theme.spacing(0.5),
            },
          },
        },
        // primary pill button
        stylePillButton({
          bgColor: theme.palette.primary.main,
          bgHoverColor: theme.palette.primary.light,
          bgDisableColor: theme.palette.colors.neutral[25],
          borderFocus: `2px solid ${theme.palette.primary.main}`,
          textColor: 'black',
        }),
        // secondary pill button (border style)
        stylePillButton({
          props: { color: 'secondary' },
          bgDisableColor: theme.palette.colors.neutral[25],
          border: `1px solid ${theme.palette.secondary.main}`,
          borderFocus: `2px solid ${theme.palette.secondary.main}`,
          textColor: 'white',
          textHoverColor: theme.palette.secondary.main,
        }),
        // warning pill button (border style)
        stylePillButton({
          props: { color: 'warning' },
          bgDisableColor: theme.palette.colors.neutral[25],
          border: `1px solid ${theme.palette.warning.main}`,
          borderFocus: `2px solid ${theme.palette.warning.main}`,
          textColor: 'white',
          textHoverColor: theme.palette.warning.main,
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
            bgColor: theme.palette.primary.main,
            bgHoverColor: theme.palette.primary.light,
            bgDisableColor: theme.palette.colors.neutral[25],
            borderFocus: `2px solid ${theme.palette.primary.main}`,
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
          backgroundColor: theme.palette.primary.main,
        },
      },
    },
    MuiCard: {
      styleOverrides: {
        root: {
          marginBottom: '4px',
          backgroundColor: theme.palette.colors.neutral[20],
          backgroundImage: 'none',
        },
      },
    },
    MuiTableCell: {
      styleOverrides: {
        root: {
          borderColor: theme.palette.colors.neutral[15],
        },
        head: {
          ...theme.fonts.monospace,
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
          marginRight: theme.spacing(4),
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
            backgroundColor: theme.palette.secondary.main,
            borderBottomSize: '4px',
          },
        },
      },
    },
    MuiInputBase: {
      styleOverrides: {
        root: {
          '.MuiOutlinedInput-input': {
            backgroundColor: theme.palette.colors.neutral[10],
            webkitBoxShadow: theme.palette.colors.neutral[10],
          },
        },
      },
    },
  },
});

export { theme };
