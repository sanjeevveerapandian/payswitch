#!/bin/sh
# Runs inside the web container at start-up, before nginx (the nginx image runs every executable
# file in /docker-entrypoint.d/). Turns ADMIN_USER / ADMIN_PASSWORD from the environment (.env)
# into the hashed password file nginx's auth_basic reads. The plain password is never written to disk.
set -eu

if [ -z "${ADMIN_USER:-}" ] || [ -z "${ADMIN_PASSWORD:-}" ]; then
    echo "40-admin-password: ADMIN_USER and ADMIN_PASSWORD must be set (see .env.example)" >&2
    exit 1
fi
case "$ADMIN_USER" in
    *:*) echo "40-admin-password: ADMIN_USER must not contain ':'" >&2; exit 1 ;;
esac
if [ "$ADMIN_PASSWORD" = "change-me" ]; then
    echo "40-admin-password: ADMIN_PASSWORD is still the placeholder from .env.example" >&2
    exit 1
fi

# SHA-512 crypt ($6$...), salted. Fed on stdin so the password never appears in the process list.
hash=$(printf '%s' "$ADMIN_PASSWORD" | openssl passwd -6 -stdin)
umask 077
printf '%s:%s\n' "$ADMIN_USER" "$hash" > /etc/nginx/admin.htpasswd
chown nginx:nginx /etc/nginx/admin.htpasswd       # nginx's worker processes read it as user nginx
echo "40-admin-password: admin login set up for user '$ADMIN_USER'"
