//! YarmiplayServerTV with device access and the file relay on, for YarmiplayServerTest in `shared`:
//! `cargo run -- <port> <open|password|approved> [password] [--vanilla]`.
//! Pending devices named "...approve..." are approved and "...deny..." denied; approved devices whose
//! user is named "...revoke..." are removed and kicked 3 seconds after they log in.

use std::collections::HashSet;
use std::sync::Arc;
use std::time::Duration;
use yarmiplayservertv_lib::relay::Relay;
use yarmiplayservertv_lib::syncplay::devices::DeviceStore;
use yarmiplayservertv_lib::syncplay::{Extensions, SyncplayAccess, SyncplayOptions, SyncplayServer};

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt().with_env_filter("info,yarmiplayservertv_lib=debug").init();
    yarmiplayservertv_lib::net::install_crypto_provider();
    let mut args = std::env::args().skip(1);
    let port: u16 = args.next().and_then(|p| p.parse().ok()).unwrap_or(8999);
    let access = match args.next().as_deref() {
        Some("approved") => SyncplayAccess::Approved,
        Some("open") => SyncplayAccess::Open,
        _ => SyncplayAccess::Password,
    };
    let (flags, rest): (Vec<String>, Vec<String>) = args.partition(|a| a.starts_with("--"));
    let vanilla_mode = flags.iter().any(|f| f == "--vanilla");
    let password = rest.into_iter().next().unwrap_or_default();
    let opts = SyncplayOptions { access, password, vanilla_mode, file_relay: true, ..SyncplayOptions::default() };
    let devices = Arc::new(DeviceStore::in_memory());
    println!("server id {}", devices.server_id());
    let ext = Extensions {
        relay: Some(Relay::new(std::env::temp_dir().join(format!("yarmiplay-relay-{port}")), 1 << 30, true)),
        devices: Some(devices.clone()),
        ..Extensions::default()
    };
    let server = Arc::new(SyncplayServer::start(port, opts, Arc::default(), Arc::new(|| {}), ext).await.expect("bind"));
    println!("listening on {}", server.port);
    let host = server.clone();
    tokio::spawn(async move {
        let mut revoked = HashSet::new();
        loop {
            tokio::time::sleep(Duration::from_millis(300)).await;
            let status = devices.status();
            for p in &status.pending {
                let name = p.name.to_lowercase();
                if !p.connected {
                    // Clears requests left by earlier test runs, which would hit the per-IP pending limit.
                    let _ = devices.deny(&p.fingerprint);
                } else if name.contains("approve") {
                    println!("approving {} ({})", p.name, p.fingerprint);
                    let _ = devices.approve(&p.fingerprint);
                } else if name.contains("deny") {
                    println!("denying {} ({})", p.name, p.fingerprint);
                    let _ = devices.deny(&p.fingerprint);
                }
            }
            for d in status.approved.iter().filter(|d| d.last_username.contains("revoke")) {
                if revoked.insert(d.fingerprint.clone()) {
                    let fp = d.fingerprint.clone();
                    let (devices, host) = (devices.clone(), host.clone());
                    tokio::spawn(async move {
                        tokio::time::sleep(Duration::from_secs(3)).await;
                        println!("revoking {fp}");
                        let _ = devices.remove(&fp);
                        host.kick_device(&fp);
                    });
                }
            }
        }
    });
    tokio::signal::ctrl_c().await.ok();
}
