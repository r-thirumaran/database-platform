# Convenience targets for the demo stack. Run from deploy/ (or: make -C deploy <target>).
COMPOSE       ?= docker compose
CORE          := --profile core
EXAMPLES      := --profile examples
MONITORING    := --profile monitoring
BATCH         := --profile batch
ALL_PROFILES  := $(CORE) $(EXAMPLES) $(MONITORING)
CP_URL        ?= http://localhost:8080

.PHONY: help env up up-core up-monitoring down build pull logs ps demo batch batch-direct batch-proxy keys clean

help:
	@echo "Targets:"
	@echo "  make env            copy .env.example to .env (if missing)"
	@echo "  make build          build all images (control plane incl. UI, gateway, proxy, examples)"
	@echo "  make up             start core + examples (Oracle needs 3-5 minutes on first start)"
	@echo "  make up-core        start only databases + platform + bootstrap"
	@echo "  make up-monitoring  start prometheus + grafana"
	@echo "  make demo           up, wait for bootstrap, then exercise the three orders-service variants"
	@echo "  make batch          run reporting-batch through the gateway (profile batch)"
	@echo "  make batch-direct   run reporting-batch straight against Oracle"
	@echo "  make batch-proxy    run reporting-batch through the proxy"
	@echo "  make keys           show the generated API keys (deploy/.generated/examples.env)"
	@echo "  make logs           follow logs of every running service"
	@echo "  make ps             status"
	@echo "  make down           stop everything (keeps volumes)"
	@echo "  make clean          stop everything, remove volumes, images and generated files"

env:
	@test -f .env || (cp .env.example .env && echo "created .env from .env.example - review it (ORACLE_PASSWORD)")

build: env
	$(COMPOSE) $(ALL_PROFILES) $(BATCH) build

pull: env
	$(COMPOSE) $(ALL_PROFILES) pull --ignore-buildable

up: env
	$(COMPOSE) $(CORE) $(EXAMPLES) up -d
	@echo "control plane: $(CP_URL)   orders-service: :8091 direct, :8092 proxy, :8093 gateway   legacy-reporting: :8094"

up-core: env
	$(COMPOSE) $(CORE) up -d

up-monitoring: env
	$(COMPOSE) $(MONITORING) up -d
	@echo "prometheus: http://localhost:9090   grafana: http://localhost:3000 (admin / GRAFANA_ADMIN_PASSWORD)"

down:
	$(COMPOSE) $(ALL_PROFILES) $(BATCH) down --remove-orphans

logs:
	$(COMPOSE) $(ALL_PROFILES) logs -f --tail=200

ps:
	$(COMPOSE) $(ALL_PROFILES) ps

keys:
	@cat .generated/examples.env 2>/dev/null || echo "no keys yet - bootstrap has not run (make up-core)"

# Start everything, wait for the bootstrap job, then generate some traffic on every variant.
demo: up
	@echo "waiting for bootstrap to finish ..."
	@until test -s .generated/bootstrap.done; do sleep 5; printf '.'; done; echo
	@for port in 8091 8092 8093; do \
	  echo "== orders-service on :$$port"; \
	  until curl -fsS http://localhost:$$port/health >/dev/null 2>&1; do sleep 5; printf '.'; done; echo; \
	  curl -fsS http://localhost:$$port/health; echo; \
	  curl -fsS "http://localhost:$$port/orders?limit=3"; echo; \
	  curl -fsS -X POST -H 'Content-Type: application/json' -d '{"customerId":7,"productId":3,"qty":2}' http://localhost:$$port/orders; echo; \
	  curl -fsS http://localhost:$$port/customers/7/tier; echo; \
	  curl -fsS http://localhost:$$port/customers/7/orders | head -c 400; echo; \
	done
	@echo "== legacy-reporting on :8094"
	@until curl -fsS http://localhost:8094/health >/dev/null 2>&1; do sleep 5; printf '.'; done; echo
	@curl -fsS "http://localhost:8094/report/customers?country=DE" | head -c 400; echo
	@curl -fsS "http://localhost:8094/report/revenue?months=6"; echo
	@echo "open $(CP_URL) - Applications, Connections, Graph, Governance"

batch: env
	$(COMPOSE) $(BATCH) run --rm reporting-batch

batch-direct: env
	$(COMPOSE) $(BATCH) run --rm \
	  -e DBP_JDBC_URL=jdbc:oracle:thin:@//oracle:1521/FREEPDB1 -e DBP_JDBC_USER=SALES_APP \
	  -e DBP_JDBC_PASSWORD="$${SALES_APP_PASSWORD:-SalesApp\#Demo2026}" reporting-batch

batch-proxy: env
	$(COMPOSE) $(BATCH) run --rm \
	  -e DBP_JDBC_URL=jdbc:oracle:thin:@//proxy:1521/sales.reporting-batch -e DBP_JDBC_USER=SALES_APP \
	  -e DBP_JDBC_PASSWORD="$${SALES_APP_PASSWORD:-SalesApp\#Demo2026}" reporting-batch

clean:
	$(COMPOSE) $(ALL_PROFILES) $(BATCH) down --remove-orphans --volumes --rmi local
	rm -f .generated/examples.env .generated/bootstrap.done
