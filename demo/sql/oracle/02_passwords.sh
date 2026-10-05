#!/bin/sh
# 02_passwords.sh -- align demo account passwords with the container environment.
#
# gvenzl/oracle-free sources *.sh files from /container-entrypoint-initdb.d in the entrypoint
# shell, so environment variables passed to the container are visible here (plain .sql files
# cannot read them). Each variable is optional; unset variables keep the defaults from 01_users.sql.
# (no "set -e": the entrypoint may source this file instead of executing it)

apply_password() {
    user="$1"
    password="$2"
    if [ -n "$password" ]; then
        echo "02_passwords.sh: setting password of $user from environment"
        sqlplus -s / as sysdba <<SQL
WHENEVER SQLERROR EXIT SQL.SQLCODE
ALTER SESSION SET CONTAINER = FREEPDB1;
ALTER USER $user IDENTIFIED BY "$password";
EXIT
SQL
    else
        echo "02_passwords.sh: no password supplied for $user, keeping demo default"
    fi
}

apply_password SALES         "${SALES_PASSWORD:-}"
apply_password SALES_APP     "${SALES_APP_PASSWORD:-}"
apply_password DBP_COLLECTOR "${DBP_COLLECTOR_PASSWORD:-}"
