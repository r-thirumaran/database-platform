#!/bin/sh
# 05_passwords.sh -- align demo role passwords with the container environment.
#
# The postgres image executes *.sh files from /docker-entrypoint-initdb.d (sourced when not
# executable), so container environment variables are available here while plain .sql files
# cannot read them. Unset variables keep the defaults from 00_databases.sql.
# (no "set -e": the entrypoint may source this file)

apply_password() {
    role="$1"
    password="$2"
    if [ -n "$password" ]; then
        echo "05_passwords.sh: setting password of role $role from environment"
        psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
             -v role="$role" -v pw="$password" \
             -c "ALTER ROLE :\"role\" PASSWORD :'pw';"
    else
        echo "05_passwords.sh: no password supplied for role $role, keeping demo default"
    fi
}

apply_password dbp           "${DBP_DB_PASSWORD:-}"
apply_password sales         "${SALES_PASSWORD:-}"
apply_password sales_app     "${SALES_APP_PASSWORD:-}"
apply_password dbp_collector "${DBP_COLLECTOR_PASSWORD:-}"
