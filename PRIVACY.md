# AWAGAM Android Privacy Policy

Last updated: August 6, 2026

## Summary

AWAGAM collects no personal data. All processing happens locally on your device.

## Data Processing

### What the App Does

AWAGAM creates a local VPN to filter DNS queries. When you visit a website, your device asks “what’s the IP address of example.com?” AWAGAM intercepts this question, checks it against your blocklists, and either blocks it (returns 0.0.0.0) or forwards it to your chosen DNS resolver.

### Data Stored on Device

| Data | Purpose | Location |
| --- | --- | --- |
| Enabled/disabled state | Remember your preference | App preferences |
| Selected DNS provider | Forward queries to your choice of resolver | App preferences |
| Blocklist URLs you add | Fetch blocklists | App preferences |
| Cached blocklist content | Offline filtering | App storage |
| Temporary disable timer | Resume after timeout | App preferences |
| Auto-start setting | Resume protection after a restart | App preferences |
| DNS query statistics | Display in Statistics screen | App storage |

All data is stored locally. Nothing is synced to external servers.

### No Backup or Transfer

The app opts out of Android’s backup and transfer system. None of the above is copied into your Google account, and none of it is carried over by a device-to-device transfer when you set up a new phone.

This is deliberate. Which blocklists you subscribe to may say something about you, and that is not something to just place in anyone’s cloud storage. The trade is that a new device starts empty: use `Export config` under Settings to move your setup yourself, to a destination you choose.

### Network Connections

The app makes two types of network requests:

1. **DNS queries:** Forwarded to your selected upstream resolver:

   - DNS4EU (default): `protective.joindns4.eu` (variants: child, noads, child-noads, unfiltered)
   - Cloudflare: `cloudflare-dns.com`
   - Google: `dns.google`
   - Quad9: `dns.quad9.net`
   - OpenDNS: `doh.opendns.com`
   - AdGuard: `dns.adguard.com`

2. **Blocklist fetches:** HTTPS requests to URLs you configure

No other network connections are made. No analytics, telemetry, or tracking.

### Data Not Collected

* No personal information
* No device identifiers
* No usage statistics
* No crash reports
* No browsing history
* No DNS query logs

## Third-Party Services

The app uses DNS-over-HTTPS (DoH) providers for upstream DNS resolution. Their privacy policies apply to DNS queries:

* DNS4EU: https://joindns4.eu/privacy-policy
* Cloudflare: https://www.cloudflare.com/privacypolicy/
* Google: https://policies.google.com/privacy
* Quad9: https://quad9.net/privacy/policy/
* OpenDNS: https://www.cisco.com/c/en/us/about/legal/privacy-full.html
* AdGuard: https://adguard-dns.io/en/privacy.html

## Your Rights

* All data is under your control
* Uninstalling the app removes all stored data, and leaves no backup behind
* You can export your configuration at any time

## Open Source

This app is free software, licensed under the GNU General Public License. You can review the license and verify any claims made in this document by reviewing the code [on GitHub](https://github.com/iadefensa/awagam-android).

## Contact

For questions, email info@iadefensa.com.