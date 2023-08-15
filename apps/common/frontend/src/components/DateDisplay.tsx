import { formatDatetime } from '../utils/temporal-fns';

interface DateDisplayProps {
  datetime: string | Date;
}

const DateDisplay: React.FC<DateDisplayProps> = (props: DateDisplayProps) => {
  const formattedDate = formatDatetime(props.datetime);

  return <>{`${formattedDate.date} ${formattedDate.time}`}</>;
};

export default DateDisplay;
