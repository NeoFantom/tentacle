# Connecting the computer to the phone

The computer always speaks the same thing: the phone HTTP API
(`docs/ARCHITECTURE.md` §3) with the phone's Bearer token. Only the base URL
differs. No custom transport protocol is involved; every mode is built from
standard pieces (HTTP, TLS, SSH, a VPN mesh).

| Mode | Phone | Computer `phone.url` | Works when |
|---|---|---|---|
| **LAN** | local server on (default port 8765) | `http://<phone Wi-Fi IP>:8765` | same network, no client isolation |
| **USB** | local server on | `http://127.0.0.1:8765` after `adb forward tcp:8765 tcp:8765` | cable + USB debugging |
| **Tailscale (built-in, works with a VPN/proxy app on)** | local server on + "Tailscale (built-in)" enabled in the app | `http://<phone 100.x IP>:8765` | computer in the same tailnet (Tailscale or Headscale) |
| **Tailscale app** | local server on + Tailscale app | `http://<phone 100.x IP>:8765` | both in the same tailnet, no other VPN app on the phone |
| **Own server (SSH tunnel)** | local server on + SSH tunnel to your server | `https://<your server>/<path>` | both can reach your server over the Internet |

Modes combine. The app's "Copy pairing link (all addresses)" puts every
working address into one link (built-in tailnet, Wi-Fi, other interfaces,
public URL); `tentacle pair` stores them as `phone.urls` and every command uses
the first one that works (see [How the computer chooses a path](#how-the-computer-chooses-a-path)).
Direct whenever possible, relay only when unavoidable.

**Android allows only one VPN at a time.** The Tailscale *app* is a VPN on
Android, so it cannot run while a proxy/VPN app (Clash, v2rayNG, …) is active
on the phone. The *built-in* Tailscale node is not a VPN: it runs inside
Tentacle in userspace and only serves Tentacle's own port, so it works while
such an app stays on. The SSH-tunnel mode is an ordinary outbound TCP
connection, so it also works with or without such apps on either device.

## Tailscale (built-in)

```
computer (tailscale) ══WireGuard, direct or via DERP══ phone: Tentacle's tsnet node :8765 → 127.0.0.1:8765
```

The app embeds a standard Tailscale node (`tailscale.com/tsnet`, userspace
networking, no VpnService, no TUN). It joins your tailnet as its own device
(default name `tentacle-<device model>`) and accepts connections only on the
file server's port; nothing else of the phone is exposed, and the phone token
is still required. The protocol is plain Tailscale/WireGuard; nothing custom.

1. In the app, open **Tailscale (built-in)** and turn it on (the file server
   must be running).
2. Log in, either
   - interactively: the status shows "Needs login"; tap **Log in** to open the
     control server's login page in the browser, sign in (or, on Headscale,
     have the admin approve the node), or
   - with a pre-auth key: under **Advanced** enter an auth key before enabling.
     The key is stored app-private, never shown again, and deleted after the
     first successful login.
3. The status shows "Connected as 100.x.y.z" and the pairing section gains a
   "Tailscale (built-in)" line. Copy the pairing link (or the all-addresses
   link) and run `tentacle pair '<link>'` on the computer, which must be in the
   same tailnet (Tailscale client on the computer).

**Headscale / other control servers.** Under **Advanced**, set the control
server URL (e.g. `https://example.com`); empty means Tailscale's default
control server. Changing the URL starts a fresh node identity. Interactive
login and pre-auth keys both work with Headscale.

**Login state** lives in app-private storage (`no_backup`, excluded from
backups) and survives app and phone restarts. **Log out** removes the node from
the tailnet; logging in again needs the browser or a new key. The node uploads
no logs to Tailscale (no-logs-no-support mode); its logs go to logcat.

**Direct vs relayed.** Like any Tailscale node, the app first talks through a
DERP relay and upgrades to a direct UDP path when NAT traversal succeeds. Direct
paths depend on the NAT types on both sides (symmetric/carrier-grade NAT and
UDP-blocking networks force the relay); otherwise traffic stays on DERP.
Network changes (Wi-Fi ↔ mobile, airplane mode) are passed to the node
immediately, so it re-discovers endpoints and reconnects within seconds.

**With a proxy/VPN app on the phone.** The built-in node's traffic is ordinary
app traffic, so a proxy app in global/TUN mode may route it through the proxy.
Control and DERP traffic still work that way, but direct UDP paths usually
never form. If `tentacle ping` keeps reporting the tailnet path as `relayed`,
exclude Tentacle in the proxy app's per-app (bypass) settings or add a
direct rule for it.

## How the computer chooses a path

`phone.urls` (written by `tentacle pair` from an all-addresses link) lists the
phone's base URLs in preference order; when it is empty, `phone.url` is used
alone. For each command the CLI:

1. probes all candidates at once with `GET /v1/ping` (2.5 s timeout; LAN,
   loopback and tailnet addresses never go through a system proxy);
2. picks the first working candidate in list order. A tailnet address
   (100.64.0.0/10) counts only while it is a direct path: if the computer's
   `tailscale status --json` shows that peer as relayed and a later candidate
   works (for example your own server), the later one wins; if nothing else
   works, the relayed tailnet path is used anyway. Without a `tailscale` CLI the
   tailnet candidate is used as is. (Tailscale's public relays can be slow from
   some regions; your own server is the intended fallback.)
3. caches the winner for 60 s in the data directory; a connection error clears
   the cache and retries once on the next candidate.

`tentacle ping` and `tentacle doctor` show every candidate with its result
(`ok`, `direct`, `relayed`, `unreachable`) and round-trip time, and which one
was chosen. `tentacle --url URL …` bypasses the selection for one command.

## Own server mode (SSH reverse tunnel)

```
computer ──HTTPS──► your server (nginx) ──► 127.0.0.1:<port> ══SSH -R══ phone:8765
```

1. The app keeps an SSH connection to your server and requests a remote port
   forward: server `127.0.0.1:<remote port>` → phone `127.0.0.1:<local port>`
   (equivalent to `ssh -N -R 127.0.0.1:<remote port>:127.0.0.1:8765 user@server`).
2. nginx on the server exposes that loopback port under HTTPS.
3. The computer uses `https://<your server>/<path>` as `phone.url`.

Files are still protected end to end by the phone token (checked on the phone);
the server and nginx never need it. TLS protects the HTTPS leg; SSH protects
the tunnel leg.

### Phone app (SSH tunnel settings)

- Host, port (default 22), user, remote bind port (default 8765 on
  `127.0.0.1`).
- The SSH host must reach the server directly. If your web domain sits behind
  an HTTP CDN/proxy (e.g. Cloudflare's orange cloud), SSH on that name goes to
  the CDN and never arrives: the app stays on "connecting", the host key stays
  untrusted, and the server's sshd log shows nothing. Use the server's IP or a
  DNS-only name for the SSH host; the public URL can still be the CDN name.
- Key: the app generates its own key pair on first use (ECDSA P-256, private
  key never leaves the app) and shows the public key in OpenSSH format with a
  copy button. Password auth is not supported.
- Host key: trust on first use; the fingerprint is shown and stored; a changed
  host key stops the tunnel with an error until the user re-trusts it.
- Keepalive every 30 s; reconnect with exponential backoff (1 s … 60 s, with
  jitter) on any failure, and immediately on network change. The status line
  shows connected / reconnecting / error (key rejected, host key changed,
  port in use).
- SSH library: a maintained pure-Java SSH client (`com.github.mwiede:jsch`), the
  one runtime dependency of the app.

### Server setup (any Linux server with OpenSSH ≥ 7.8 and nginx)

1. A dedicated user is recommended (e.g. `tentacle`, no password, no shell
   needed). Add the phone's public key to its `~/.ssh/authorized_keys`,
   restricted to this single forward:

   ```
   restrict,port-forwarding,permitlisten="127.0.0.1:8765",command="/usr/sbin/nologin" ecdsa-sha2-nistp256 AAAA… tentacle-phone
   ```

   Keep `GatewayPorts no` (the default) so the port is bound to loopback only.
2. nginx, inside the HTTPS `server` block:

   ```nginx
   location /tentacle/ {
       proxy_pass http://127.0.0.1:8765/;
       proxy_http_version 1.1;
       proxy_set_header Connection "";
       proxy_buffering off;
       proxy_request_buffering off;
       proxy_read_timeout 300s;
       client_max_body_size 0;
   }
   ```
3. Computer: `phone.url = "https://<your server>/tentacle"`.

Examples in this repository use `example.com`; there is no default server.
