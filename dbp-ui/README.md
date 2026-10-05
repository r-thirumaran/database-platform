# dbp-ui — Database Access Platform portal

React 18 + TypeScript + Vite single-page app for the control plane described in
[`docs/control-plane-api.md`](../docs/control-plane-api.md). It is served by the control plane itself
(`dist/` → `classpath:/static`) and, during development, by the Vite dev server on port 5173 with
`/api` and `/actuator` proxied to `http://localhost:8080`.

## Run

```bash
cd dbp-ui
npm install

# A. against the real control plane (Spring Boot on 8080, seeded with POST /api/v1/seed/demo)
npm run dev                 # http://localhost:5173 — /api and /actuator are proxied to http://localhost:8080
DBP_API_URL=http://cp.internal:8080 npm run dev   # another control plane address

# B. against the built-in mock control plane (no Java needed)
npm run mock                # http://localhost:8080/api/v1 — in-memory retail demo dataset
npm run dev                 # second terminal
```

`npm run mock` implements every public endpoint of the contract against `mock/data.ts` (teams,
applications, two databases, three datasources, tables, columns, routines, dependencies,
relationships, query stats, pool stats, live connections, components, policies, violations). Mutations
work against the in-memory store and `POST /seed/demo` resets it. `MOCK_LATENCY_MS` (default 120) adds
jitter so loading states are visible; `PORT` changes the port. If `dist/` exists the mock also serves
it with an SPA fallback, so `npm run build && npm run mock` demos the production bundle on 8080 exactly
as the control plane would.

## Build and quality gates

| Command             | What                                                            |
|---------------------|-----------------------------------------------------------------|
| `npm run build`     | Production build to `dist/` (base path `/`). Copied by the control-plane Docker build. |
| `npm run preview`   | Serve `dist/` locally (needs an API on 8080 or `VITE_API_BASE`). |
| `npm run lint`      | ESLint (typescript-eslint, react-hooks, react-refresh), zero warnings. |
| `npm run typecheck` | `tsc --noEmit` for the app and for `vite.config.ts` / `mock/` / `e2e/`. |
| `npm run test`      | Vitest (API client, formatting, components).                     |
| `npm run e2e`       | Playwright (smoke + page-by-page walkthrough); starts the mock and the dev server itself. Chromium is expected at `$PLAYWRIGHT_BROWSERS_PATH`. |
| `npm run e2e:real`  | Same specs against a **real, seeded control plane** on `http://localhost:8080` (override with `PW_BASE_URL`); only the dev server is started. The walkthrough clicks through every page, exercises every mutation (and undoes it) and fails on any console error or 4xx/5xx response. |
| `npm run check`     | lint + typecheck + test + build.                                 |

## Environment

| Variable        | Default | Meaning |
|-----------------|---------|---------|
| `VITE_API_BASE` | `""`    | Prefix for every API call (`${VITE_API_BASE}/api/v1/...`). Empty = same origin, which is what the control plane and the dev proxy need. Set e.g. `https://control-plane.example.org` to point a static build elsewhere. |
| `DBP_API_URL`   | `http://localhost:8080` | Dev server / Playwright only: where the Vite proxy forwards `/api` and `/actuator`. |
| `PW_USE_REAL_API`, `PW_BASE_URL` | unset | Playwright: skip the mock and target a real control plane (`npm run e2e:real`). |

Theme follows `prefers-color-scheme`; the toggle in the sidebar (system / light / dark) is stored in
`localStorage` only.

## Code layout

```
src/api/        types.ts (every resource of the contract), client.ts (fetch wrapper, error
                normalisation), resources.ts (one function per endpoint), hooks.ts (React Query)
src/components/ layout, badges, tables, modals, states, stat tiles, meters, entity picker
src/charts/     validated palette tokens, Recharts stacked bars, Cytoscape canvas + styles
src/pages/      one folder per area (see below)
mock/           data.ts (typed demo dataset — doubles as documentation of the JSON shapes), server.ts
e2e/            Playwright smoke test
```

## Pages and the endpoints they use

| Page | Route | Endpoints |
|------|-------|-----------|
| Dashboard | `/` | `GET /stats/overview`, `GET /stats/connections?groupBy=`, `GET /stats/tables/hot`, `GET /stats/queries/top`, `GET /stats/pools`, `GET /components` |
| Databases | `/databases`, `/databases/:id` | `GET/POST/PUT/DELETE /databases`, `GET /databases/{id}/schemas`, `GET /databases/{id}/collector-status`, `POST /databases/{id}/test-connection`, `POST /databases/{id}/collect`, `GET /tables?databaseId=&schema=`, `GET /routines?databaseId=&schema=`, `POST /tables/bulk-ownership` |
| Datasources | `/datasources`, `/datasources/:id` | `GET/POST/PUT/DELETE /datasources`, `GET /datasources/{id}/summary`, `PUT/POST/DELETE /datasources/{id}/routing-rules`, `POST /datasources/{id}/switch` (after showing `GET /impact/datasource/{id}`), `GET /migration-events?datasourceId=`, `GET/POST/PUT/DELETE /access-grants` |
| Applications | `/applications`, `/applications/:id` | `GET/POST/PUT/DELETE /applications`, `GET /applications/{id}/summary`, `GET/POST/DELETE /applications/{id}/api-keys`, `GET /relationships?applicationId=`, `GET /stats/queries/top?applicationId=`, access grants |
| Teams | `/teams`, `/teams/:id` | `GET/POST/PUT/DELETE /teams`, `GET /teams/{id}/summary` |
| Tables | `/tables`, `/tables/:id` | `GET /tables?...&page=&size=` (paged), `GET /tables/{id}/summary`, `PUT /tables/{id}`, `GET /tables/{id}/columns`, `PUT /tables/{id}/columns/{columnId}`, `POST /tables/{id}/ownership`, `POST /tables/bulk-ownership` |
| Routines | `/routines`, `/routines/:id` | `GET /routines?...`, `GET /routines/{id}/summary` |
| Graph explorer | `/graph?root=type:id&depth=` | `GET /graph?root=&depth=&include=&edgeKinds=&limit=` (expand = depth 1 around a node, merged client side; `truncated: true` shows a notice) |
| Impact analysis | `/impact?table=` / `?column=` | `GET /impact/table/{id}`, `GET /impact/column/{id}`, `GET /tables/{id}/columns` |
| Connections | `/connections` | `GET /connections/live` (10 s refresh) |
| Queries | `/queries` | `GET /stats/queries/top?by=&window=&databaseId=&applicationId=` |
| Governance | `/governance` | `GET/PUT /governance/policies`, `GET/PUT /governance/violations`, `POST /governance/evaluate` |
| Admin | `/admin` | `GET/POST/PUT/DELETE /credentials`, `POST /credentials/{id}/rotate`, `GET /components`, `GET /export`, `POST /import`, `POST /seed/demo` |

Lists that can grow (tables) use the paged form (`?page=&size=` → `{ items, page, size, total }`); the
other lists use the plain-array form. Every page has loading, error (with retry) and empty states.

Contract details the pages rely on (verified against the Spring Boot control plane with `npm run e2e:real`):
`PUT` on teams, applications, databases, datasources and access grants replaces the whole resource, so the
pages always send the full object (pool policy, identity rules and grant toggles included); `PUT /tables/{id}`
and `PUT /tables/{id}/columns/{columnId}` are partial and an explicit `null` clears a field; routine summaries
return `callers` as `ConsumerEntry[]` and incoming dependencies as `referencedBy`; datasource impact lists
`applications[]` (not consumers); views are tables of kind `VIEW`; the graph may carry the additional edge kinds
`ROUTES_TO`, `PRODUCES` and `GRANTED`.

## Charts

Charts follow the data-viz method bundled with the repository tooling: a single validated categorical
palette (light and dark steps, CSS variables `--series-1..8`), thin marks with surface gaps, hairline
grid, a legend for every multi-series chart, a table twin for every chart, and status colours used
only for state (pool meters) with an icon and label. Cytoscape resolves the same CSS variables at
render time so the graph re-themes with the page.
