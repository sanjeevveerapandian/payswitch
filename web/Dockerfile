# The gateway image: nginx with our config and the UI baked in (build context: web/).
FROM nginx:1.27-alpine
# openssl hashes the admin password at start-up (40-admin-password.sh). The Alpine nginx image
# does not include it.
RUN apk add --no-cache openssl
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY admin-ok.json /usr/share/nginx/admin/ok.json
COPY --chmod=0755 40-admin-password.sh /docker-entrypoint.d/40-admin-password.sh
COPY html/ /usr/share/nginx/html/
HEALTHCHECK --interval=10s --timeout=3s --retries=5 \
    CMD wget -q -O /dev/null http://127.0.0.1/healthz || exit 1
