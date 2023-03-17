import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import IconButton from '@mui/material/IconButton';
import Tooltip from '@mui/material/Tooltip';

const PartyId: React.FC<{ partyId: string; noCopy?: boolean; classNames?: string }> = ({
  partyId,
  noCopy,
  classNames,
}) => {
  const handleClick = () => navigator.clipboard.writeText(partyId);

  return (
    <div style={{ display: 'flex', alignItems: 'center' }}>
      <Tooltip title={'Party ID: ' + partyId}>
        <div
          style={{
            display: 'inline-flex',
            maxWidth: '160px',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            fontWeight: 'lighter',
          }}
          className={`party-id ${classNames}`}
        >
          {partyId}
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
