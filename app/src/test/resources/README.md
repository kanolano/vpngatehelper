# Test fixtures

## `vpngate_sample.csv`

A real capture of `https://www.vpngate.net/api/iphone/`, committed so the parser tests assert
against the actual upstream format rather than a mock of it.

**On the private keys inside it.** Each row's last column is a base64-encoded `.ovpn` profile, and
those profiles contain an `RSA PRIVATE KEY` block. This is not a leaked credential:

- It is **VPN Gate's shared client key** — byte-identical in every row and for every VPN Gate user
  worldwide. It authenticates "an OpenVPN client", not a person or an account.
- It is published openly by VPN Gate as part of their public server list.
- It grants nothing beyond what anyone gets by visiting vpngate.net.

The PEM text is only ever present base64-encoded, never as literal `-----BEGIN` lines, so secret
scanners do not match it.

**Several tests assert counts taken from this exact snapshot** — 97 rows, 85 TCP / 12 UDP,
40 JP / 33 KR. Re-capturing the file is expected to break them; update the numbers from the new
capture:

```bash
curl -s https://www.vpngate.net/api/iphone/ -o app/src/test/resources/vpngate_sample.csv
```
