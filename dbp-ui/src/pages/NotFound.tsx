import { Link } from 'react-router-dom';
import { EmptyState } from '../components/States';
import { PageHeader } from '../components/PageHeader';

export function NotFound() {
  return (
    <>
      <PageHeader title="Page not found" />
      <EmptyState title="There is nothing at this address" action={<Link className="btn" to="/">Back to the dashboard</Link>} />
    </>
  );
}
