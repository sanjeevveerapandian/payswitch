# Deploying the public demo: one VM, Docker Compose and Caddy

The whole stack runs on one Linux server. Caddy listens on ports 80/443, gets a free HTTPS certificate
from Let's Encrypt by itself, and forwards everything to the PaySwitch gateway on `127.0.0.1:8000`.
Nothing else is reachable from the internet.

```
internet --443--> Caddy (HTTPS, on the server) --127.0.0.1:8000--> web (nginx) --> PSP, switch, banks
                                                                                   Postgres, Prometheus, Grafana
                                                                                   (Docker network only)
```

> **Check prices and free-tier terms yourself before you create anything.** Cloud providers change them
> often, and a "free" VM can start billing after a trial or above a limit. Nothing in this guide depends
> on one provider.

Every command below runs **on the server** unless it says "on your laptop". Replace:

- `YOUR_SERVER_IP`: the server's public IP address
- `payswitch-demo.duckdns.org`: your own domain or free subdomain (step 2)
- `deploy`: the user name you choose in step 3

---

## 1. Create the server

- **OS:** Ubuntu Server 24.04 LTS.
- **Size:** 2 vCPUs and **4 GB RAM** recommended. On my laptop the running stack used about 1.15 GiB after
  1,000 payments (5 JVMs at 160-210 MiB each, Postgres about 140 MiB), and building the images needs extra
  memory for Maven. 2 GB can work with the swap file from step 5, but it is tight.
- **Disk:** 25 GB or more.
- **CPU type:** x86-64 or ARM64 both work: every base image used here is published for both.
- **Login:** choose SSH key login when the provider asks. If you have no key yet, on your laptop:

  ```bash
  ssh-keygen -t ed25519 -C "payswitch-server"
  ```

  and paste the content of `~/.ssh/id_ed25519.pub` (the `.pub` file, never the other one) into the provider's form.
- **Static IP:** reserve one (the name varies: "static IP", "elastic IP", "reserved IP"), so the address
  does not change after a reboot and break your domain.
- **The provider's own firewall** (security group / security list / cloud firewall): allow inbound
  **TCP 22, 80 and 443** only. This is separate from the server's own firewall in step 6; both must allow them.

## 2. Get a name for the server (free option: DuckDNS)

HTTPS certificates are issued for names, not IP addresses.

1. Go to https://www.duckdns.org and sign in.
2. Create a subdomain, for example `payswitch-demo`. Your address becomes `payswitch-demo.duckdns.org`.
3. Put `YOUR_SERVER_IP` in its "current ip" field and press "update ip".
4. On your laptop, check it (it can take a minute):

   ```bash
   nslookup payswitch-demo.duckdns.org
   ```

   It must answer `YOUR_SERVER_IP`.

If you own a domain, create an `A` record (for example `payswitch.yourdomain.com`) pointing to `YOUR_SERVER_IP` instead.

## 3. First login and a normal user

On your laptop:

```bash
ssh root@YOUR_SERVER_IP          # some providers use "ubuntu@" instead of "root@"
```

Create your own user with sudo, and give it your SSH key:

```bash
adduser deploy
usermod -aG sudo deploy
rsync --archive --chown=deploy:deploy ~/.ssh /home/deploy
```

(If you logged in as `ubuntu`, put `sudo` in front of each of these three commands.)

**In a second terminal on your laptop**, check that the new user works before you go further:

```bash
ssh deploy@YOUR_SERVER_IP
sudo whoami                      # must print: root
```

Only when that works, turn off password and root logins. On the server, as `deploy`:

```bash
sudo nano /etc/ssh/sshd_config.d/99-hardening.conf
```

Put these three lines in it, save (Ctrl+O, Enter) and exit (Ctrl+X):

```
PermitRootLogin no
PasswordAuthentication no
KbdInteractiveAuthentication no
```

```bash
sudo systemctl restart ssh
```

Keep your current session open and test a NEW `ssh deploy@YOUR_SERVER_IP` from your laptop. If it fails, fix
it from the session that is still open.

From now on, always log in as `deploy`.

## 4. Updates, automatic security updates

```bash
sudo apt update && sudo apt -y upgrade
sudo apt -y install unattended-upgrades
sudo dpkg-reconfigure -plow unattended-upgrades      # answer "Yes"
sudo reboot                                          # if the upgrade asked for it; then log in again
```

## 5. A swap file (recommended; needed on a 2 GB server)

Swap is disk space the kernel can use when RAM runs out: slow, but better than a killed container.

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
free -h                                              # the "Swap:" line shows 2.0Gi
```

## 6. The server's firewall: only SSH, HTTP and HTTPS

```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable                                      # answer "y"; your SSH session stays open
sudo ufw status verbose
```

Port 80 is needed too: Let's Encrypt checks it when it issues the certificate, and Caddy uses it to send
plain-HTTP visitors to HTTPS.

**Why the gateway listens on 127.0.0.1 only:** Docker publishes container ports by writing its own firewall
rules, which ufw does not see. A port published on `0.0.0.0` would be open to the internet *even though ufw
blocks it*. `docker-compose.prod.yml` therefore publishes the gateway as `127.0.0.1:8000`, reachable only
by Caddy on the same machine, and publishes nothing else.

> Some providers' Ubuntu images (Oracle Cloud's, for example) also ship their own `iptables` rules that
> block everything except SSH. If step 10 cannot get a certificate although DNS and the provider's
> firewall are right, look at `sudo iptables -L INPUT -n --line-numbers` and the provider's documentation.

## 7. Install Docker

The official way, from Docker's own package repository (compare with
https://docs.docker.com/engine/install/ubuntu/ in case it changed):

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl git
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

Let `deploy` run docker without sudo, then log out and in again so it takes effect:

```bash
sudo usermod -aG docker deploy
exit
```

```bash
ssh deploy@YOUR_SERVER_IP
docker run --rm hello-world          # prints "Hello from Docker!"
docker compose version               # must be v2.24.4 or newer (the prod file uses "!override")
```

(Being in the `docker` group is as powerful as root on this machine. Only add users you trust.)

## 8. Get the code

```bash
cd ~
git clone https://github.com/sanjeevveerapandian/payswitch.git
cd payswitch
```

## 9. Create `.env` (the secrets)

```bash
cp .env.example .env
chmod 600 .env                        # only you can read it
openssl rand -base64 24               # run it three times: one password each for the three below
nano .env
```

Fill in every value:

| Key | Value |
|---|---|
| `PUBLIC_URL` | `https://payswitch-demo.duckdns.org` (no slash at the end) |
| `ADMIN_USER` | a user name for the admin parts, e.g. `admin` |
| `ADMIN_PASSWORD` | a generated password |
| `POSTGRES_PASSWORD` | another generated password |
| `GRAFANA_ADMIN_PASSWORD` | another generated password |
| `WEB_PORT` | `8000` |

Then **add this line** at the end of `.env` on the server:

```
COMPOSE_FILE=docker-compose.yml:docker-compose.prod.yml
```

docker compose reads `COMPOSE_FILE` from `.env`, so on this server every plain `docker compose ...` command
uses the production settings. That way you never forget the prod file. (Do not add this line on your laptop.
The `:` between the two files is the Linux separator; Windows would need `;`.)

Keep a copy of the passwords in your password manager. `.env` is git-ignored and must never be committed.

## 10. Start PaySwitch

```bash
docker compose config --quiet && echo "config OK"
docker compose up -d --build --wait
```

The first build downloads Maven, the JDK and every library, and compiles everything: expect **5-15 minutes**
on a small server. `--wait` returns when every container reports healthy.

```bash
docker compose ps                     # every line: "Up ... (healthy)"
curl -s http://127.0.0.1:8000/healthz # ok
```

If `web` keeps restarting, run `docker compose logs web`: it refuses to start with a missing admin password
or with the placeholder `change-me`.

## 11. Caddy: HTTPS in front

Install Caddy from its official repository (compare with https://caddyserver.com/docs/install#debian-ubuntu-raspbian):

```bash
sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https curl
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt update
sudo apt install -y caddy
```

Replace the whole of Caddy's config:

```bash
sudo nano /etc/caddy/Caddyfile
```

```
payswitch-demo.duckdns.org {
    # Everything goes to the PaySwitch gateway. Caddy passes the visitor's address on in
    # X-Forwarded-For (nginx uses it for its per-address rate limits) and sends the live
    # event stream on at once (it never buffers text/event-stream).
    reverse_proxy 127.0.0.1:8000

    # Browsers: from now on, only ever use HTTPS for this name (one year).
    header Strict-Transport-Security "max-age=31536000"
}
```

```bash
sudo caddy validate --config /etc/caddy/Caddyfile
sudo systemctl reload caddy
sudo journalctl -u caddy -n 50 --no-pager      # look for "certificate obtained successfully"
```

That is all for HTTPS: Caddy requested the certificate from Let's Encrypt, redirects `http://` to `https://`,
and renews the certificate by itself before it expires.

## 12. Check it

On your laptop, open **https://payswitch-demo.duckdns.org**. You should see the TOTAL (Rs 17,500.00), the
pay form and the live monitor with a green "live". Make a payment and watch it move through its states.

"Admin log in" shows the browser's own login box: use `ADMIN_USER` and `ADMIN_PASSWORD` from `.env`.

The full gateway check, from the server (so the admin password never leaves it):

```bash
cd ~/payswitch
set -a; . ./.env; set +a
scripts/smoke-gateway.sh https://payswitch-demo.duckdns.org
```

Every line must say PASS. (The last check uses up the server's own `/pay` allowance for a minute; visitors
are not affected, the limit is per address.)

Also check that nothing else is open. **On your laptop:**

```bash
curl -m 5 http://YOUR_SERVER_IP:8000/healthz     # must FAIL (timeout or refused)
curl -m 5 http://YOUR_SERVER_IP:5432             # must FAIL
```

## 13. Watch it: logs and resources

```bash
cd ~/payswitch
docker compose ps                               # health of every container
docker compose logs -f --tail=100 switch        # one service live (Ctrl+C to stop); also psp, bank-a, web...
docker compose logs --since=10m                 # everything from the last 10 minutes
scripts/trace.sh TXN...                         # one payment's story across every container
docker stats --no-stream                        # memory and CPU per container
sudo journalctl -u caddy -f                     # Caddy: certificates, HTTPS errors
df -h /                                         # disk space
```

Grafana: https://payswitch-demo.duckdns.org/grafana/ (admin password). Logs are rotated (3 x 10 MB per
container), so they cannot fill the disk.

## 14. Redeploy after you push

After `git push` on your laptop, on the server:

```bash
cd ~/payswitch
git pull
docker compose up -d --build --wait
docker image prune -f                           # remove the old, now unused images
```

Only containers whose image or settings changed are recreated, each down for a few seconds while it
restarts. Database changes (Flyway migrations) run by themselves when the services start. If you changed
`.env` only (a new admin password, say): `docker compose up -d --wait` recreates the affected containers.

Before you push, CI (the badge in the README) shows whether `mvn verify` and the image builds passed.

## 15. Everyday tasks

| Task | Command |
|---|---|
| Restart one service | `docker compose restart switch` |
| Stop everything (data kept) | `docker compose down` |
| Start again | `docker compose up -d --wait` |
| Reset the demo to day one (**deletes all payments and balances**) | `docker compose down -v && docker compose up -d --wait` |
| Put the demo customers back to their starting balances | Admin, "Reseed demo balances" (it also runs every night at 03:30 IST) |
| Back up the databases | `docker compose exec -T postgres pg_dumpall -U payswitch > backup-$(date +%F).sql` |
| Update the server's packages | automatic (step 4); `sudo apt update && sudo apt upgrade` by hand |

After a server reboot everything comes back by itself: Docker and Caddy start at boot, and every container
has `restart: unless-stopped`.

## 16. When something is wrong

| Symptom | Look at |
|---|---|
| The browser says the certificate is invalid, or the site does not load | `nslookup` your name (step 2); ports 80 and 443 open in BOTH firewalls (steps 1 and 6); `sudo journalctl -u caddy -n 100` |
| `502 Bad Gateway` | A service is starting or restarting: `docker compose ps`. The switch-crash scenario causes a few seconds of 502 on purpose. |
| A container keeps restarting | `docker compose logs <service>`; `docker stats` (memory); `free -h` |
| `429 Too Many Requests` | The rate limits doing their job. Wait a minute. |
| "Busy: the ... scenario is running" | Another visitor's scenario; one runs at a time. Wait a few seconds. |
| The build fails with "Killed" | Out of memory while building: add the swap file (step 5), then build again |
