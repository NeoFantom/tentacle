# Connecting the computer to the phone

The computer always speaks the same thing: the phone HTTP API
(`docs/ARCHITECTURE.md` §3) with the phone's Bearer token. Only the base URL
differs. No custom transport protocol is involved; every mode is built from
standard pieces (HTTP, TLS, SSH, a VPN mesh).

| Mode | Phone | Computer `phone.url` | Works when |
|---|---|---|---|
| **LAN** | local server on (default port 8765) | `http://<phone Wi-Fi IP>:8765` | same network, no client isolation |
| **USB** | local server on | `http://127.0.0.1:8765` after `adb forward tcp:8765 tcp:8765` | cable + USB debugging |
| **Tailscale** | local server on + Tailscale app | `http://<phone 100.x IP>:8765` | both in the same tailnet |
| **Own server (SSH tunnel)** | local server on + SSH tunnel to your server | `https://<your server>/<path>` | both can reach your server over the Internet |

Pick one mode for both sides: either both devices join the same Tailscale
tailnet, or both point at the same server.

**Android allows only one VPN at a time.** Tailscale is a VPN on Android, so it
cannot run while a proxy/VPN app (Clash, v2rayNG, …) is active on the phone.
The SSH-tunnel mode is an ordinary outbound TCP connection, not a VPN, so it
works with or without such apps on either device.

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
