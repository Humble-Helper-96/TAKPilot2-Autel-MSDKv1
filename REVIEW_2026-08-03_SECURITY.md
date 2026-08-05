# Security review: TLS, CoT and certificate enrollment — 3 August 2026

**Written in Simplified Technical English (ASD-STE100).**

> **This is a record of one date.** It gives the findings of 3 August 2026 and what was decided
> about each one. Section 8 gives the disposition. Do not change the findings. Add a new record if
> you do a new review.

**Scope:** `TakClient` (TLS when the application operates), `TakCertEnroller` (enrollment and key
storage) and `CotParser` (parsing of XML that the application does not control).

The code comes from the DJI TAKPilot2 application. The findings apply to both applications.

The findings are in order. The most severe finding is first. **No code was changed for this review.**
A change to the TLS behaviour or the trust behaviour can stop the connection to the server of the
operator.

## 1. A person can intercept the enrollment (HIGH)

**Where:** `TakCertEnroller.createEnrollmentSSLContext()` and the hostname verifier in `httpsGet`,
`httpsGetBytes` and `httpsPost`.

**What:** The `X509TrustManager` for the enrollment only calls `cert.checkValidity()`, which tests
the expiry date. It also does a chain test, but it discards the result if the test fails. Therefore
it accepts any self-signed server certificate.

The hostname verifier returns true for each session that gave a certificate. It does not compare the
name.

The application sends the TAK username and password of the operator on this connection as HTTP
Basic. It also receives the truststore on this connection.

**How it fails:** A person is on the network during the first enrollment. That person gives any
self-signed certificate. The application accepts it. The application then sends the credentials of
the operator, and that person collects them. That person then returns a truststore that they
control. The application trusts that truststore in each later session. Therefore the compromise
continues.

This is the trust-on-first-use gap of TAK automatic enrollment. The code does not close any part of
it. There is no fingerprint confirmation and no path to supply a truststore by a different method.

**How to correct it.** Any one of these closes most of the gap:

- Show the SHA-256 fingerprint of the server certificate to the operator. The operator confirms it
  by a different method before the application sends the credentials.
- Let a person import a truststore or a data package by a different method, and use only that.
- As a minimum, write in the instructions that the enrollment must operate on a trusted network.

Full validation against a public CA is not correct here. TAK servers usually have self-signed
certificates. Therefore the fingerprint confirmation is the practical correction.

## 2. The TLS connection does not verify the hostname (MEDIUM)

**Where:** `TakClient.connect()`.

**What:** The socket is a raw `SSLSocket` from `factory.createSocket(addr, port)`. The code gives it
an `InetAddress`. Therefore the application sends no SNI hostname.

The code never calls `SSLParameters.setEndpointIdentificationAlgorithm("HTTPS")`. Therefore it does
not compare the identity in the certificate of the server with `serverAddress`.

This is much less severe than finding 1. The certificate IS validated against the enrolled
truststore. The real `TrustManager` tests the chain and the expiry.

**How it fails:** The enrolled CA signs more than one host. This occurs with a shared TAK CA. A
person who holds any certificate from that CA can then be the server. If the CA is specific to one
server, the risk is low.

**Important:** Many TAK installations do not verify the hostname. Their servers use an IP address or
a self-signed certificate. Therefore this behaviour can be correct for the ecosystem. Test against
the target servers before you make this stricter. If you do not, you will stop the connections where
the name in the certificate is not the address.

**How to correct it:** Give the hostname to `createSocket` as a String. This also gives SNI. Then set
the endpoint identification to `"HTTPS"`. You can also make this a control that is on by default.

## 3. The private key has only the well-known password "atakatak" (MEDIUM)

**Where:** `TakCertEnroller.DEFAULT_P12_PASSWORD` and `buildClientP12`.

**What:** The client key and the truststore are `.p12` files in the application-private `filesDir`.
The password is public and is in the code. Therefore the password gives no protection. The only real
protection is the application sandbox.

The manifest has `allowBackup="false"`. This stops extraction with `adb backup`. Therefore the
exposure needs root access, physical access, or a change to the backup rules.

A person who gets the key can be the operator on the TAK network.

**How to correct it:** Put the private key in the Android Keystore. Use hardware protection if the
controller has it. As an alternative, make the password from a secret that is specific to the device.

`PORT-STATUS.md` also lists this item.

## 4. The receive buffer has no limit (LOW)

**Where:** `TakClient.run()`, the `recvBuffer` value.

**What:** The bytes collect in a `StringBuilder` until `</event>` occurs. There is no limit on the
size.

A server with a fault, or a server that a person controls but that has correct TLS, can send data
with no `</event>`. The buffer then increases with no limit. The controller has a small memory.
Therefore the application stops.

**How to correct it:** Put a limit on the buffer, for example a few MB. If the buffer becomes larger,
discard the incomplete data or stop the connection. This is protection only. It does not change the
behaviour for a correct server.

## 5. The signed certificate is not compared with the key pair (LOW)

**Where:** `TakCertEnroller.enroll()`.

**What:** The application puts the `signedCert` from the server and the local private key into the
`.p12` file. It does not test that `signedCert.getPublicKey()` agrees with `keyPair.getPublic()`.

This fails safely. If the two do not agree, the TLS connection fails later. Therefore this is
robustness, not a hole. But the failure occurs a long way from its cause.

**How to correct it:** Do one equality test before `buildClientP12`. Give a clear error message.

## 6. Parts that are correct

These parts were examined and are correct. This record stops a second review of them.

- **The CoT parsing is safe from XXE and from entity-expansion attacks.** `CotParser` uses
  `XmlPullParser` (the Android KXmlParser) with `FEATURE_PROCESS_DOCDECL` off, which is the default.
  Therefore the parser does not process DOCTYPE, does not define internal entities and never gets
  external entities. An undefined entity gives an exception, which the code catches. A pull parser is
  the correct choice for XML from a server that the application does not control. The application
  reads the fields into typed values. It does not evaluate them.
- **The application refuses an unencrypted connection.** `TakClient.connect()` gives an exception if
  there is no client certificate or no truststore. It does not use a plain socket.
- **The application does not log credentials.** The enrollment logs the URL and the username. It
  never logs the password or the Basic authentication header.
- **`allowBackup="false"`** in the manifest stops the extraction of the `.p12` files with
  `adb backup`.

## 7. Summary

| Severity | Count | Findings |
|---|---|---|
| High | 1 | Interception of the enrollment and exposure of the credentials |
| Medium | 2 | No hostname verification; the password of the key at rest |
| Low | 2 | The buffer with no limit; the certificate and key comparison |

Findings 4 and 5 are protection only. You can correct them with no risk to the connection.

Findings 1, 2 and 3 change the trust behaviour or the storage behaviour. Decide about them against
the target TAK servers before you change the code. Finding 1 protects the credentials of the
operator. It needs a decision.

## 8. Disposition, 3 August 2026

- **Finding 4 (the buffer with no limit): CORRECTED** in commit 481aae0. `TakClient` now has a limit
  of 4 MB on the buffer. It stops the connection if the data is more than this. Tested on the device:
  TAK connects and receives CoT correctly.
- **Finding 5 (the certificate and key comparison): CORRECTED** in commit 481aae0. `TakCertEnroller`
  now tests that the public key of the signed certificate agrees with the key pair before it makes
  the `.p12` file.
- **Findings 1, 2 and 3: ACCEPTED as standard TAK behaviour.** This was the decision of the operator
  on 3 August 2026. Trust on first use at enrollment, no hostname verification, and the usual
  `atakatak` password are how the TAK ecosystem operates. They are records, not changes.

  Examine finding 1 again if the enrollment must operate on a network that is not trusted. Examine
  finding 3 again before a larger fleet deployment. The corrections in this document are still
  correct if the risk changes.
