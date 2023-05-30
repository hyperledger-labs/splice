import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import { Typography, TypographyProps } from '@mui/material';
import IconButton from '@mui/material/IconButton';
import Tooltip from '@mui/material/Tooltip';

export type PartyIdProps = {
  partyId: string;
  noCopy?: boolean;
  classNames?: string;
} & TypographyProps;
const PartyId: React.FC<PartyIdProps> = props => {
  const { partyId, classNames, noCopy, ...typographyProps } = props;
  const handleClick = () => navigator.clipboard.writeText(partyId);

  return (
    <div style={{ display: 'flex', alignItems: 'center' }}>
      <Tooltip title={'Party ID: ' + partyId}>
        <div
          style={{
            display: 'inline-flex',
            maxWidth: '400px',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            fontWeight: 'lighter',
          }}
          className={`party-id ${classNames}`}
        >
          <Typography {...typographyProps}>{partyId}</Typography>
        </div>
      </Tooltip>
      {!noCopy && (
        <IconButton onClick={handleClick}>
          <ContentCopyIcon fontSize={'small'} />
        </IconButton>
      )}
    </div>
  );
};

export default PartyId;
