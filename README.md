# VirtuProbe Bridge

A Burp Suite extension that bridges requests between Burp and [VirtuProbe Studio](https://virtuprobe.studio).

Burp is where you explore. VirtuProbe is where a request becomes a durable, repeatable, shareable
test. This extension is the bridge between the two, so the interesting requests you find in Burp
stop living inside a single project file and become an organized, versioned workspace you can rerun,
chain and share across engagements.

> Status: early scaffold. The API surface and the round-trip flow are being built. See the roadmap
> below for what lands first.

## What it does

Two directions, over VirtuProbe's local API:

- **Burp to VirtuProbe.** Right click a request (or a selection in Proxy history or the site map)
  and send it to VirtuProbe as an HTTP probe, either as a structured probe you can edit or byte for
  byte as it went on the wire. Pick the destination bundle, or let VirtuProbe use the active project.
- **VirtuProbe to Burp.** Send a stored VirtuProbe request straight into Burp Repeater or Intruder
  for manual work. The extension polls VirtuProbe for these, so nothing needs to reach back into
  Burp.
- **Promote to test.** Send the final request together with its response, and VirtuProbe stores the
  response as a baseline and turns it into a one step test with an assertion. A finding becomes a
  regression test in one click.

## Requirements

- Burp Suite with the Montoya API (Burp 2023.1 or later).
- VirtuProbe Studio running on the same machine (or reachable), with the Burp bridge enabled.
- Java 21 to build.

## Build

```bash
mvn -q package
```

The extension jar is written to `target/virtuprobe-bridge-<version>.jar`.

## Install

In Burp: **Extensions -> Installed -> Add**, extension type **Java**, then select
`target/virtuprobe-bridge-<version>.jar`.

## Configure

Open the **VirtuProbe** tab in Burp and enter the host, port and API token from VirtuProbe's Burp
bridge settings panel. The default is `127.0.0.1:10100`. Use **Test connection** to confirm.

## Roadmap

- Slice 1: config and connection test, send to VirtuProbe (single, bulk, verbatim), send to Repeater
  and Intruder, promote to test.
- Later: VirtuProbe as an auth engine for Burp (run a VirtuProbe chain and inject the result into the
  current request), Burp project to VirtuProbe project sync, response backed assertion suggestions.

## License

MIT. See [LICENSE](LICENSE).
