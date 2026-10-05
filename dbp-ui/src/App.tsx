import { Suspense } from 'react';
import { createBrowserRouter, Navigate } from 'react-router-dom';
import { Layout } from './components/Layout';
import { Loading } from './components/States';
import { Dashboard } from './pages/Dashboard';
import { DatabasesPage } from './pages/databases/DatabasesPage';
import { DatabaseDetail } from './pages/databases/DatabaseDetail';
import { DatasourcesPage } from './pages/datasources/DatasourcesPage';
import { DatasourceDetail } from './pages/datasources/DatasourceDetail';
import { ApplicationsPage } from './pages/applications/ApplicationsPage';
import { ApplicationDetail } from './pages/applications/ApplicationDetail';
import { TeamsPage } from './pages/teams/TeamsPage';
import { TeamDetail } from './pages/teams/TeamDetail';
import { TablesPage } from './pages/tables/TablesPage';
import { TableDetail } from './pages/tables/TableDetail';
import { RoutinesPage } from './pages/routines/RoutinesPage';
import { RoutineDetail } from './pages/routines/RoutineDetail';
import { ConnectionsPage } from './pages/runtime/ConnectionsPage';
import { QueriesPage } from './pages/runtime/QueriesPage';
import { GovernancePage } from './pages/runtime/GovernancePage';
import { AdminPage } from './pages/admin/AdminPage';
import { NotFound } from './pages/NotFound';

import { GraphPage, ImpactPage } from './pages/lazy';

const lazyEl = (el: React.ReactNode) => <Suspense fallback={<Loading />}>{el}</Suspense>;

export const router = createBrowserRouter([
  {
    path: '/',
    element: <Layout />,
    children: [
      { index: true, element: <Dashboard /> },
      { path: 'dashboard', element: <Navigate to="/" replace /> },
      { path: 'databases', element: <DatabasesPage /> },
      { path: 'databases/:id', element: <DatabaseDetail /> },
      { path: 'datasources', element: <DatasourcesPage /> },
      { path: 'datasources/:id', element: <DatasourceDetail /> },
      { path: 'applications', element: <ApplicationsPage /> },
      { path: 'applications/:id', element: <ApplicationDetail /> },
      { path: 'teams', element: <TeamsPage /> },
      { path: 'teams/:id', element: <TeamDetail /> },
      { path: 'tables', element: <TablesPage /> },
      { path: 'tables/:id', element: <TableDetail /> },
      { path: 'routines', element: <RoutinesPage /> },
      { path: 'routines/:id', element: <RoutineDetail /> },
      { path: 'graph', element: lazyEl(<GraphPage />) },
      { path: 'impact', element: lazyEl(<ImpactPage />) },
      { path: 'connections', element: <ConnectionsPage /> },
      { path: 'queries', element: <QueriesPage /> },
      { path: 'governance', element: <GovernancePage /> },
      { path: 'admin', element: <AdminPage /> },
      { path: '*', element: <NotFound /> },
    ],
  },
]);
