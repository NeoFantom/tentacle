# Connecting the computer to the phone

The computer always speaks the same thing: the phone HTTP API
(`docs/ARCHITECTURE.md` §3) with the phone's Bearer token. Only the base URL
differs. No custom transport protocol is involved; every mode is built from
standard pieces (HTTP, TLS, SSH, a VPN mesh).

File commands, verification codes and the
[phone notification feed](NOTIFICATIONS.md) all use this same path selection.
The local notification GUI keeps the phone token server-side and binds its own
viewer to loopback; it does not add another route to the phone.

| Mode | Phone | Computer `phone.url` | Works when |
|---|---|---|---|
| **LAN** | local server on (default port 8765) | `http://<phone Wi-Fi IP>:8765` | same network, no client isolation |
| **USB** | local server on | `http://127.0.0.1:8765` after `adb forward tcp:8765 tcp:8765` | cable + USB debugging |
| **Tailscale (built-in, works with a VPN/proxy app on)** | local server on + "Tailscale (built-in)" enabled in the app | `http://<phone 100.x IP>:8765` | computer in the same tailnet (Tailscale or Headscale) |
| **Tailscale app** | local server on + Tailscale app | `http://<phone 100.x IP>:8765` | both in the same tailnet, no other VPN app on the phone |
| **Own server (SSH tunnel)** | local server on + SSH tunnel to your server | `https://<your server>/<path>` | both can reach your server over the Internet |

The app's **Phone local network** row displays this phone's Wi-Fi/Ethernet
address, even when the computer is elsewhere. It is a candidate address, not a
measurement of the computer's network or an active connection. Its neutral
indicator and note mean computer reachability has not been checked. LAN access
requires a shared reachable network without client isolation; use Tailscale or
the SSH reverse tunnel when the computer is on another network. The file-server
running status describes the phone service, not a confirmed computer connection.

Modes combine. The app's "Copy pairing link" puts every working address into
one link (built-in tailnet, Wi-Fi, other interfaces, public URL); `tentacle
pair` stores them as `phone.urls` and every command races them and uses the
best one that answers (see [How the computer chooses a path](#how-the-computer-chooses-a-path)).
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

1. In the app, turn on the **Tailscale (built-in)** card under "Ways to
   connect" (the file server must be running).
2. Log in, either
   - interactively: the status shows "Needs login"; tap **Log in** to open the
     control server's login page in the browser, sign in (or, on Headscale,
     have the admin approve the node), or
   - with a pre-auth key: under **Advanced** enter an auth key and tap **Save
     Tailscale settings** before enabling.
     The key is stored app-private, never shown again, and deleted after the
     first successful login.
3. The status shows "Connected as 100.x.y.z" and the status card's Tailscale
   row turns green. Tap **Copy pairing link** (it now includes the tailnet
   address; single-address links are under **More**) and run
   `tentacle pair '<link>'` on the computer, which must be in the same tailnet
   (Tailscale client on the computer).

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

`phone.urls` (written by `tentacle pair` from the app's pairing link) lists the
phone's base URLs in preference order; when it is empty, `phone.url` is used
alone. For each command the CLI:

1. **races** all candidates at once with `GET /v1/ping` (2.5 s timeout each;
   LAN, loopback and tailnet addresses never go through a system proxy),
   together with the LAN addresses the phone reported last time, and asks the
   local `tailscale status --json` in parallel when a tailnet address is among
   them. The phone's ping answer lists its current Wi-Fi/Ethernet base URLs
   (`lan`); any of them not tried yet joins the race the moment it is reported
   and is remembered, so a changed Wi-Fi address is found without re-pairing;
2. decides as soon as the answer cannot improve:
   - a reachable **LAN** address (private, non-tailnet) is taken the moment it
     answers — same network means no relay and full Wi-Fi speed;
   - otherwise, when the first *good* candidate answers (a non-tailnet one such
     as your own server, or a tailnet address whose peer is a direct path), the
     race waits only a short grace (0.25 s) for a LAN answer, then takes the
     first good candidate in list order that has answered;
   - a **relayed** tailnet path (the computer's `tailscale status` shows the
     peer without a direct address) is used only when nothing else answers
     before the timeout. Without a `tailscale` CLI, or while its answer is
     still pending, the tailnet candidate is taken at face value.
     (Tailscale's public relays can be slow from some regions; your own server
     is the intended fallback.)

   Slow or dead candidates never delay the choice: their probes finish in the
   background and are ignored.
3. caches the winner for 60 s in the data directory; a connection error clears
   the cache and retries once on the next candidate.

`tentacle ping`, `tentacle pair` and `tentacle doctor` wait for every probe and
show each candidate with its result (`ok`, `direct`, `relayed`, `unreachable`)
and round-trip time, and which one was chosen. `tentacle --url URL …` bypasses
the selection for one command.

On the phone, the local server, the SSH tunnel and the built-in Tailscale node
start independently when the service starts (none waits for another). On a
default network, interface or address change the tunnel coalesces brief callback
bursts, disconnects a session whose own network was lost, and otherwise probes
the existing SSH session before replacing it. The Tailscale node is told about
the new interfaces immediately.

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

- Card **Own server (SSH tunnel)**: a switch, a one-line status and, under
  **Server settings**, the fields: server address (SSH host), SSH port
  (default 22), user (default `tentacle`), remote port (default 8765, bound on
  `127.0.0.1`) and the optional public URL. There is no default server: the
  project is open source and examples use `example.com` / `192.0.2.x`.
- **Paste server info**: paste any text that contains the details and tap
  **Recognize** (or **Paste**, which takes the clipboard). The app picks out
  the host, SSH port, user, remote port and public URL from, for example,
  `ssh -p 2222 tentacle@192.0.2.10`, `ssh tentacle@host -p 2222`,
  `ssh -N -R 127.0.0.1:9876:127.0.0.1:8765 tentacle@host`, `ssh://user@host:2222`,
  `user@[2001:db8::1]:22`, bare IPv4/IPv6 addresses or domain names, an
  `https://` URL (taken as the public URL; its host is used for SSH only when
  nothing else names the server), labelled lines in English or Chinese
  (`Host: …`, `主机：…`, `端口 22`, `用户名=tentacle`, `远程端口 9876`) and the
  `permitlisten="127.0.0.1:<port>"` of an `authorized_keys` line. It shows what
  it recognised and what is still missing; nothing is saved until you tap
  **Save**. Fields that are left empty use the defaults above.
- The SSH host must reach the server directly. If your web domain sits behind
  an HTTP CDN/proxy (e.g. Cloudflare's orange cloud), SSH on that name goes to
  the CDN and never arrives: the app stays on "connecting", the host key stays
  untrusted, and the server's sshd log shows nothing. Use the server's IP or a
  DNS-only name for the SSH host; the public URL can still be the CDN name.
  The app resolves the SSH host and shows a warning when it lands in
  Cloudflare's published address ranges.
- Key: the app generates its own key pair on first use (ECDSA P-256, private
  key never leaves the app) and shows the public key in OpenSSH format with a
  copy button, plus a ready `authorized_keys` line, under **Server setup
  (key)**. Password auth is not supported. The server needs an ECDSA or RSA
  host key (the SSH library does not accept an Ed25519-only server).
- Host key: trust on first use; the fingerprint is shown and stored; a changed
  host key stops the tunnel with an error until the user re-trusts it.
- Keepalive every 30 s; failures normally retry with exponential backoff
  (1 s … 60 s, with jitter); the status shows connected / reconnecting / error
  (key rejected, host key changed, port in use).
  Network changes are coalesced for 400 ms. The app remembers the network on
  which each session or connection attempt started. If that network is lost,
  it immediately disconnects the old session on a best-effort basis and cancels
  attempts. With no network it stays offline without attempting connections;
  when a network returns it resets backoff and connects once immediately,
  without probing the discarded session. A loss and replacement network within
  the debounce window produce one disconnect-and-connect decision.
  If the default changes while the session's own network remains available, or
  that network's addresses/interface change, a 2 s `direct-tcpip` round trip to
  server loopback port 9 probes the existing session. Losing default status
  alone does not count as losing that network. Any reply, including
  refusal, keeps it alive on its original network; no reply replaces it once.
  Remote-port conflicts after the app's network-related session replacement
  retry every 1 s for up to 30 s from the first recovery attempt while showing
  "Connecting…", then use normal error/backoff; offline waiting does not consume
  that grace period.
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
   In `sshd_config`, also consider:

   ```sshdconfig
   Match User tentacle
       ClientAliveInterval 15
       ClientAliveCountMax 3
   ```

   This frees the remote port in approximately 45 s when a phone disappears
   without closing its SSH session; validate and reload sshd after editing.
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
