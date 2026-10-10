# Changelog

All notable changes to AWAGAM Android are documented in this file, which is (mostly) AI-generated and (always) human-edited. Dependency updates may or may not be called out specifically.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.0] - 2026-10-10

### Added

* Added a local resolver option to forward DNS queries to a resolver running on the device, like InviZible Pro’s DNSCrypt in proxy mode

## [1.0.2] - 2026-10-07

### Fixed

* Fixed internationalized domains with characters like “ß” not being blocked, by converting them the way browsers do (UTS #46)
* Fixed Pi-hole export, whose TLD rules Pi-hole rejected in lists and whose domain rules missed subdomains (entries now use ABP-style syntax)
* Fixed Pi-hole and AdGuard Home exports containing URL rules those tools can’t apply (URLs are now listed as skipped, like in the hosts export)

### Changed

* Updated invalid blocklist entries and groups without a name to be skipped with a warning instead of failing the whole blocklist

## [1.0.1] - 2026-10-05

### Changed

* Updated dependencies and store graphic

## [1.0.0] - 2026-08-31

### Added

* Released initial version